package com.aiswatch

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * AtoN AIS 메인 액티비티
 * - USB 권한 요청
 * - RTL-SDR 장치 연결
 * - WebView로 웹앱 표시 (localhost:8100)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AISWatch-Main"
        private const val ACTION_USB_PERMISSION = "com.aiswatch.USB_PERMISSION"
    }

    private lateinit var webView: WebView
    private lateinit var driver: RtlSdrDriver
    private lateinit var dataStore: AISDataStore
    private var httpServer: AISHttpServer? = null

    /* NMEA 수집 스레드 */
    private var pollThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var nmeaCount = 0
    private var currentGain = 420   // 0.1dB 단위 (42.0dB 시작)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectRunnable = Runnable { dispatchUsb() }
    private val usbMgr by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val nmeaTcp = NmeaTcpServer(10110)
    private val serialNmea by lazy { UsbSerialNmea(usbMgr) }
    @Volatile private var inputActive: String = "none"
    private var inputMode: String = "auto"   // auto / rtl / nmea (연결방법 수동선택)
    @Volatile private var currentSerialDevice: UsbDevice? = null   // Baud 재연결용

    /* USB 권한 응답 BroadcastReceiver */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        Log.i(TAG, "USB 권한 허용: ${device.deviceName}")
                        onPermissionGranted(device)
                    } else {
                        Log.w(TAG, "USB 권한 거부")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB 장치 연결 감지 → 자동 연결")
                    Toast.makeText(context, "🔌 USB 장치 연결 감지 — 자동 연결", Toast.LENGTH_SHORT).show()
                    mainHandler.removeCallbacks(connectRunnable)
                    mainHandler.postDelayed(connectRunnable, 700)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB 장치 분리 감지")
                    mainHandler.removeCallbacks(connectRunnable)
                    running = false
                    try { pollThread?.join(500) } catch (_: Exception) {}
                    try { driver.close() } catch (_: Exception) {}
                    try { serialNmea.stop() } catch (_: Exception) {}
                    inputActive = "none"
                    dataStore.inputMode = "-"; dataStore.inputDevice = "-"
                    Toast.makeText(context, "🔌 USB 장치 분리됨", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        driver    = RtlSdrDriver(this)
        dataStore = AISDataStore()

        startHttpServer()   // WebView 로드보다 먼저: 포트 바인딩 후 접속하도록
        setupWebView()
        currentGain = getSharedPreferences("aiswatch", MODE_PRIVATE).getInt("gain", 420)
        try { val nl = android.Manifest.permission.ACCESS_FINE_LOCATION
            if (checkSelfPermission(nl) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(nl, android.Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
        } catch (_: Exception) {}
        try { nmeaTcp.start() } catch (_: Exception) {}
        startStatusUpdater()
        dispatchUsb()

        val usbFilter = IntentFilter(ACTION_USB_PERMISSION)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbReceiver, usbFilter, RECEIVER_NOT_EXPORTED)
    }

    /* ─── WebView 설정 ──────────────────────────────── */
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled     = true
            domStorageEnabled     = true
            allowFileAccess       = true
            loadsImagesAutomatically = true
            mixedContentMode      = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setGeolocationEnabled(true)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }
        }
        webView.webViewClient   = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "WebView 로드 완료: $url")
            }
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) view?.postDelayed({ view.loadUrl("http://localhost:8100") }, 600)
            }
        }
        /* 앱 내장 웹앱 로드 */
        webView.loadUrl("http://localhost:8100")
    }

    /* ─── HTTP 서버 시작 ────────────────────────────── */
    private fun startHttpServer() {
        try {
            httpServer = AISHttpServer(this, dataStore, 8100)
            httpServer!!.serialSender = { line -> serialNmea.writeLine(line) }   // 설정 송신 연결
            httpServer!!.baudSetter = { rate -> setSerialBaud(rate) }            // Baud 전환 연결
            httpServer!!.start()
            Log.i(TAG, "HTTP 서버 시작: http://localhost:8100")
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 서버 시작 실패: ${e.message}")
        }
    }

    /* ─── RTL-SDR 연결 ──────────────────────────────── */
    /* USB 자동 판별: RTL-SDR ↔ USB 시리얼 NMEA */
    private fun dispatchUsb() {
        val rtl = try { driver.findDevice() } catch (_: Exception) { null }
        val serial = try { usbMgr.deviceList.values.firstOrNull { UsbSerialNmea.isSerial(it) } } catch (_: Exception) { null }
        when (inputMode) {
            "rtl"  -> { if (rtl != null) connectRtlSdr() else { Log.w(TAG,"RTL-SDR 미감지"); return } }
            "nmea" -> { if (serial != null) connectSerialNmea(serial) else { Log.w(TAG,"USB NMEA 미감지"); return } }
        }
        if (rtl != null) { connectRtlSdr(); return }
        if (serial != null) { connectSerialNmea(serial); return }
        // 장치 미감지 시 조용히 대기 (팝업 표시 안 함)
    }

    private fun onPermissionGranted(device: UsbDevice) {
        val isRtl = try { driver.findDevice()?.deviceName == device.deviceName } catch (_: Exception) { false }
        if (isRtl) startAIS(device)
        else if (UsbSerialNmea.isSerial(device)) connectSerialNmea(device)
        else startAIS(device)
    }

    /* USB 시리얼 NMEA 수신 시작 */
    private fun connectSerialNmea(device: UsbDevice) {
        if (running) { running = false; try { pollThread?.join(500) } catch (_: Exception) {}; try { driver.close() } catch (_: Exception) {} }
        try { serialNmea.stop() } catch (_: Exception) {}
        if (!usbMgr.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
            usbMgr.requestPermission(device, pi)
            Toast.makeText(this, "USB NMEA 권한을 요청합니다...", Toast.LENGTH_LONG).show()
            return
        }
        nmeaCount = 0
        serialNmea.baud = getSharedPreferences("aiswatch", MODE_PRIVATE).getInt("serial_baud", 38400)
        val ok = serialNmea.start(device,
            onLine = { line ->
                nmeaCount++
                dataStore.processNmea(line)
                nmeaTcp.broadcast(line)
            },
            onRaw = { raw -> dataStore.addRaw(raw) },
            onConfig = { resp -> dataStore.logConfig("RX", resp) }   // $AN 설정응답 저장
        )
        if (ok) {
            inputActive = "nmea"
            currentSerialDevice = device
            dataStore.inputMode = "USB NMEA AIS"; dataStore.inputDevice = serialNmea.deviceName
            Toast.makeText(this, "✅ USB NMEA 수신 시작! (${serialNmea.deviceName} @ ${serialNmea.baud})", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "USB NMEA 연결 실패")
        }
    }

    /** 설정 탭에서 호출: 시리얼 Baud 전환 후 재연결 (38400/115200). 저장됨 */
    fun setSerialBaud(rate: Int): Boolean {
        getSharedPreferences("aiswatch", MODE_PRIVATE).edit().putInt("serial_baud", rate).apply()
        val dev = currentSerialDevice
            ?: try { usbMgr.deviceList.values.firstOrNull { UsbSerialNmea.isSerial(it) } } catch (_: Exception) { null }
            ?: return false
        mainHandler.post { connectSerialNmea(dev) }
        return true
    }

    /* NMEA 출력 상태 주기 갱신 */
    private fun startStatusUpdater() {
        val r = object : Runnable {
            override fun run() {
                dataStore.nmeaOut = if (nmeaTcp.isRunning())
                    "ON C:${nmeaTcp.clientCount()} TX:${nmeaTcp.txCount()}" else "OFF"
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.postDelayed(r, 2000)
    }

    private fun connectRtlSdr() {
        // 이전 수신 세션이 있으면 먼저 안전하게 정리 (재연결 크래시 방지)
        if (running) {
            running = false
            try { pollThread?.join(800) } catch (_: Exception) {}
            try { driver.close() } catch (_: Exception) {}
        }
        try { RtlSdrDriver.clearDiag() } catch (_: Exception) {}
        nmeaCount = 0
        val device = driver.findDevice()
        if (device == null) {
            Log.w(TAG, "RTL-SDR 동글이 감지되지 않습니다. " +
                "USB-C OTG 케이블로 동글을 연결한 후\n'재연결' 버튼을 누르세요.")
            return
        }

        if (driver.hasPermission(device)) {
            startAIS(device)
        } else {
            /* USB 권한 요청 */
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            usbManager.requestPermission(device, pi)
            Toast.makeText(this, "RTL-SDR USB 권한을 요청합니다...", Toast.LENGTH_LONG).show()
        }
    }

    /* ─── AIS 수신 시작 ─────────────────────────────── */
    private fun startAIS(device: UsbDevice) {
        /* PPM=0 고정 (AIS-catcher도 PPM 0으로 동작) */
        val ppm = 0

        if (!driver.open(device, ppm, currentGain)) {
            Log.e(TAG, "RTL-SDR 장치 열기 실패")
            return
        }

        Toast.makeText(this, "✅ AIS 수신 시작! (${driver.deviceName()} · ${driver.tunerName()})", Toast.LENGTH_SHORT).show()
        inputActive = "rtl"
        dataStore.inputMode = "RTL-SDR AIS"; dataStore.inputDevice = "${driver.deviceName()} · ${driver.tunerName()}"

        /* NMEA 폴링 스레드 시작 */
        running = true
        pollThread = Thread {
            Log.i(TAG, "NMEA 폴링 시작")
            while (running) {
                try {
                    val nmea = driver.pollNmea()
                    if (nmea != null && nmea.isNotEmpty()) {
                        nmeaCount++
                        dataStore.processNmea(nmea)
                        nmeaTcp.broadcast(nmea)
                    } else {
                        Thread.sleep(5)
                    }
                } catch (_: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, "폴링 오류: ${e.message}") }
            }
            Log.i(TAG, "NMEA 폴링 종료")
        }.also { it.isDaemon = true; it.start() }

        /* 8초 후: 데이터가 0건일 때만 안내 (정상 수신 시엔 팝업 없음) */
        Handler(Looper.getMainLooper()).postDelayed({
            if (nmeaCount == 0) {
                Log.w(TAG, "수신 시작됐으나 데이터 0건")
            }
        }, 8000)
    }

    /* ─── UI 헬퍼 ──────────────────────────────────── */
    private fun showGuide(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("⚓ AtoN AIS")
                .setMessage(msg)
                .setPositiveButton("재연결") { _, _ -> connectRtlSdr() }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun showDiag(title: String, msg: String) {
        val diag = try { RtlSdrDriver.getDiagLog() } catch (e: Exception) { "(진단 로그 없음: ${e.message})" }
        runOnUiThread {
            val _diagText = msg + "\n\n[현재 게인 ${currentGain / 10.0}dB]\n" +
                "평균진폭이 15~60이 되도록 게인을 조절하세요.\n" +
                "──── 네이티브 진단 ────\n" +
                (if (diag.isBlank()) "(로그 없음)" else diag)
            val _dens = resources.displayMetrics.density
            val _tv = android.widget.TextView(this).apply {
                text = _diagText
                textSize = 12f
                setTextIsSelectable(true)
                setPadding((16 * _dens).toInt(), (8 * _dens).toInt(), (16 * _dens).toInt(), 0)
            }
            val _sv = android.widget.ScrollView(this).apply { addView(_tv) }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(_sv)
                .setPositiveButton("＋4dB") { _, _ -> adjustGain(40) }
                .setNeutralButton("－4dB") { _, _ -> adjustGain(-40) }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun adjustGain(delta: Int) {
        currentGain = (currentGain + delta).coerceIn(0, 500)
        getSharedPreferences("aiswatch", MODE_PRIVATE).edit().putInt("gain", currentGain).apply()
        Toast.makeText(this, "게인 ${currentGain / 10.0}dB 로 재연결...", Toast.LENGTH_SHORT).show()
        connectRtlSdr()
    }

    private fun showError(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("⚠ 오류")
                .setMessage(msg)
                .setPositiveButton("재시도") { _, _ -> connectRtlSdr() }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    /* ─── 생명주기 ──────────────────────────────────── */
    override fun onBackPressed() {
        if (webView.canGoBack()) { webView.goBack(); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("AtoN AIS")
            .setMessage("프로그램을 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ -> finish() }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        pollThread?.interrupt()
        driver.close()
        httpServer?.stop()
        try { serialNmea.stop() } catch (_: Exception) {}
        try { nmeaTcp.stop() } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
