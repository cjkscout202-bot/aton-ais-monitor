package com.aiswatch

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB 시리얼 NMEA AIS 수신기 입력 모듈 (A61Z 최적화).
 *
 * A61Z 출력 특성:
 *   - NMEA 문장을 \r\n으로 2~3조각 분리해서 전송
 *   - 타임스탬프(HH:MM:SS.mmm)가 조각 앞에 붙거나 조각 사이에 삽입됨
 *   - 조각1이 !로 시작, 조각2·3은 이어지는 데이터
 *   - 체크섬(*XX)의 두 번째 hex 글자가 마지막 조각에 올 수 있음
 *
 * 처리 전략:
 *   1) 타임스탬프 정규식으로 제거
 *   2) 줄에서 '!' 찾아 앞(이전 조각 끝)과 뒤(새 문장 시작) 분리
 *   3) '!' 없는 줄은 현재 버퍼에 이어붙임
 *   4) *XX 완성 시점에만 체크섬 검증 후 onLine 콜백
 *   5) 버퍼가 너무 커지면 강제 초기화 (비정상 데이터 방어)
 */
class UsbSerialNmea(
    private val usbManager: UsbManager,
    var baud: Int = 38400,
    var dataBits: Int = 8,
    var parity: Int = UsbSerialPort.PARITY_NONE,
    var stopBits: Int = UsbSerialPort.STOPBITS_1
) {
    @Volatile private var running = false
    private var port: UsbSerialPort? = null
    private var thread: Thread? = null
    var deviceName: String = "USB AIS"; private set

    private val TS = Regex("""\d{2}:\d{2}:\d{2}[.:]\d{3}\s*""")
    private var dumpWriter: FileWriter? = null  // 원시 데이터 덤프용
    @Volatile private var onConfigCb: ((String) -> Unit)? = null  // $AN... 설정응답 콜백

    fun isRunning() = running

    fun start(device: UsbDevice,
              onLine: (String) -> Unit,
              onRaw: ((String) -> Unit)? = null,
              onConfig: ((String) -> Unit)? = null): Boolean {
        this.onConfigCb = onConfig
        stop()
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return false
        val conn = usbManager.openDevice(driver.device) ?: run {
            Log.e(TAG, "openDevice 실패"); return false
        }
        val p = driver.ports.firstOrNull() ?: return false
        try {
            p.open(conn)
            p.setParameters(baud, dataBits, stopBits, parity)
            try { p.dtr = true; p.rts = true } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "포트 open 실패: ${e.message}")
            try { p.close() } catch (_: Exception) {}
            return false
        }
        port = p
        deviceName = device.productName ?: device.manufacturerName ?: "USB AIS"
        // 원시 데이터 덤프 파일 열기 (/sdcard/Download/haerod_raw_HHMMSS.txt)
        try {
            val sdf = SimpleDateFormat("HHmmss", Locale.getDefault())
            val fname = "haerod_raw_${sdf.format(Date())}.txt"
            val f = File("/sdcard/Download/$fname")
            dumpWriter = FileWriter(f, false)
            Log.i(TAG, "원시 덤프 시작: ${f.absolutePath}")
        } catch (e: Exception) { Log.w(TAG, "덤프 파일 열기 실패: ${e.message}") }
        running = true

        thread = Thread {
            val buf = ByteArray(4096)
            val rawBuf = StringBuilder()   // 바이트 수신 버퍼
            val lb = StringBuilder()       // 현재 조립 중인 NMEA 문장

            Log.i(TAG, "USB NMEA 수신 시작: $deviceName @ ${baud}bps")

            while (running) {
                try {
                    val n = p.read(buf, 200)
                    if (n <= 0) continue

                    val rawChunk = String(buf, 0, n, Charsets.US_ASCII)
                    rawBuf.append(rawChunk)
                    // 원시 바이트 그대로 덤프 (개행 포함)
                    try { dumpWriter?.write(rawChunk); dumpWriter?.flush() } catch (_: Exception) {}

                    // \r 또는 \n 기준으로 줄 처리
                    var eol = findEol(rawBuf)
                    while (eol >= 0) {
                        val rawLine = rawBuf.substring(0, eol)
                        rawBuf.delete(0, eol + 1)
                        eol = findEol(rawBuf)

                        // 1) 타임스탬프 제거
                        val line = TS.replace(rawLine, "").trim()
                        if (line.isEmpty()) continue

                        // 1.5) 설정 응답 문장($AN AID/ACE/ACF/AAR) 감지 → onConfig 콜백
                        //      (AIS ! 경로와 무관. ZDA/MPR 등 다른 $ 문장은 무시)
                        if (line.startsWith("$") && line.length >= 6) {
                            val fmt = line.substring(3, 6)
                            if (fmt == "AID" || fmt == "ACE" || fmt == "ACF" || fmt == "AAR") {
                                if (isValidChecksum(line)) {
                                    try { onConfigCb?.invoke(line) } catch (_: Exception) {}
                                }
                                continue   // 설정 문장은 여기서 처리 끝 (AIS 파싱 안 함)
                            }
                        }

                        // 2) '!' 위치 찾기
                        val bang = line.indexOf('!')

                        when {
                            bang > 0 -> {
                                // 이 줄 앞쪽 = 이전 문장의 마지막 조각
                                // 이 줄 뒤쪽 = 새 문장의 시작
                                val tail = line.substring(0, bang).trim()
                                val head = line.substring(bang)

                                if (lb.isNotEmpty() && tail.isNotEmpty()) {
                                    lb.append(tail)
                                    if (checkAndEmit(lb.toString(), onRaw, onLine))
                                        lb.setLength(0)
                                    // 완성 안 되도 버리지 않음 — 드물지만 tail이 길 수 있음
                                }

                                // 이전 문장이 완성 안 됐으면 여기서 포기하고 새 문장 시작
                                lb.setLength(0)
                                lb.append(head)
                            }
                            bang == 0 -> {
                                // 새 문장 시작 — 이전 버퍼는 완성 여부와 무관하게 교체
                                // (완성 안 됐으면 데이터 손상, 포기하고 새 문장 우선)
                                if (lb.isNotEmpty()) {
                                    checkAndEmit(lb.toString(), onRaw, onLine)
                                    lb.setLength(0)
                                }
                                lb.append(line)
                            }
                            else -> {
                                // '!' 없는 조각 — 현재 버퍼에 이어붙임
                                if (lb.isNotEmpty()) {
                                    lb.append(line)
                                } else {
                                    // lb가 비어있는데 '!'도 없으면 쓰레기 데이터
                                    Log.v(TAG, "쓰레기 조각 무시: ${line.take(30)}")
                                }
                            }
                        }

                        // 3) 현재 버퍼 완성 확인
                        if (lb.isNotEmpty() && checkAndEmit(lb.toString(), onRaw, onLine)) {
                            lb.setLength(0)
                        }

                        // 4) 버퍼 안전장치 (1024바이트 초과 시 리셋)
                        if (lb.length > 1024) {
                            Log.w(TAG, "버퍼 초과 리셋: ${lb.take(40)}")
                            lb.setLength(0)
                        }
                    }

                    if (rawBuf.length > 16384) rawBuf.setLength(0)

                } catch (_: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, "read: ${e.message}"); break }
            }
            Log.i(TAG, "USB NMEA 수신 종료")
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    /**
     * 문장이 완성됐으면(*XX) onLine 콜백 호출 후 true.
     * 완성되지 않았으면 false.
     */
    private fun checkAndEmit(line: String,
                             onRaw: ((String) -> Unit)?,
                             onLine: (String) -> Unit): Boolean {
        val star = line.lastIndexOf('*')
        if (star < 0 || star + 3 > line.length) return false
        val csStr = line.substring(star + 1, star + 3)
        if (!csStr.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) return false

        // 완성된 문장 — 진단 로그
        try { onRaw?.invoke(line) } catch (_: Exception) {}
        val parts = line.split(",")
        if (parts.size >= 6 && parts[5].isNotEmpty()) {
            val v = parts[5][0].code - 48
            val mt = if (v > 39) v - 8 else v
            Log.d(TAG, "RX MT=$mt len=${line.length} ${line.take(50)}")
        }

        if (isValidAis(line)) {
            try { onLine(line) } catch (e: Exception) { Log.e(TAG, "onLine: ${e.message}") }
        } else {
            Log.w(TAG, "체크섬 불일치: ${line.take(60)}")
        }
        return true
    }

    private fun findEol(sb: StringBuilder): Int {
        for (i in sb.indices) if (sb[i] == '\r' || sb[i] == '\n') return i
        return -1
    }

    /** 설정 문장 송신 (CRLF 자동 부가). 같은 포트로 write. 성공 시 true */
    fun writeLine(sentence: String): Boolean {
        val p = port ?: return false
        return try {
            val out = (if (sentence.endsWith("\r\n")) sentence else sentence + "\r\n")
                .toByteArray(Charsets.US_ASCII)
            p.write(out, 1000)
            Log.i(TAG, "TX: ${sentence.trim()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeLine 실패: ${e.message}"); false
        }
    }

    /** NMEA 체크섬 검증 ($ 와 * 사이 XOR) */
    private fun isValidChecksum(line: String): Boolean {
        val star = line.lastIndexOf('*')
        if (star < 1 || star + 3 > line.length) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor line[i].code
        return try { cs == line.substring(star + 1, star + 3).toInt(16) } catch (_: Exception) { false }
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
        try { dumpWriter?.close() } catch (_: Exception) {}; dumpWriter = null
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    private fun isValidAis(line: String): Boolean {
        if (!line.startsWith("!AIVDM") && !line.startsWith("!AIVDO") &&
            !line.startsWith("!BSVDM") && !line.startsWith("!ABVDM")) return false
        val star = line.lastIndexOf('*')
        if (star < 1 || star + 3 > line.length) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor line[i].code
        return try { cs == line.substring(star + 1, star + 3).toInt(16) } catch (_: Exception) { false }
    }

    companion object {
        private const val TAG = "UsbSerialNmea"
        fun isSerial(dev: UsbDevice): Boolean =
            try { UsbSerialProber.getDefaultProber().probeDevice(dev) != null } catch (_: Exception) { false }
    }
}
