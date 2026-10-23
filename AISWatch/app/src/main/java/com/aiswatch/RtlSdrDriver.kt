package com.aiswatch

import android.content.Context
import android.hardware.usb.*
import android.util.Log

/**
 * RTL-SDR USB 드라이버
 * Android USB Host API로 RTL-SDR 장치를 열고 JNI(C)에 fd 전달.
 *
 * ※ 복구 호환 버전:
 *   - 최신 MainActivity 호출부(open(device,ppm,gain) / tunerName() / clearDiag() / getDiagLog())에 맞춤.
 *   - 네이티브(aiswatch-jni, rtl-ais 경로)는 startReceiving(fd,ppm)만 제공하므로 gain은 보관만 함.
 *   - 신호세기(PWR)·게인 제어·튜너명 네이티브 조회는 AIS-catcher 브리지 복구 시 확장.
 */
class RtlSdrDriver(private val context: Context) {

    companion object {
        private const val TAG = "AISWatch-USB"

        /* RTL-SDR 장치 식별자 (VID=0x0BDA Realtek) */
        private val RTL_DEVICES = listOf(
            Pair(0x0BDA, 0x2832),
            Pair(0x0BDA, 0x2838),
            Pair(0x0BDA, 0x2840),
            Pair(0x0BDA, 0x2848),
            Pair(0x0BDA, 0x2850),
        )

        /* JNI 네이티브 함수 (rtl-ais 기반 aiswatch-jni) */
        @JvmStatic external fun startReceiving(fd: Int, ppm: Int): Int
        @JvmStatic external fun stopReceiving()
        @JvmStatic external fun nextNmeaMessage(): String?
        @JvmStatic external fun isRunning(): Boolean

        init { System.loadLibrary("aiswatch-jni") }

        /* ── 진단 로그 (네이티브 대체: Kotlin 측 간이 버퍼) ── */
        private val diag = StringBuilder()
        @JvmStatic fun clearDiag() { synchronized(diag) { diag.setLength(0) } }
        @JvmStatic fun getDiagLog(): String = synchronized(diag) { diag.toString() }
        internal fun diagLine(s: String) {
            synchronized(diag) {
                diag.append(s).append('\n')
                if (diag.length > 4000) diag.delete(0, diag.length - 4000)
            }
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var gain10: Int = 420          // 0.1dB 단위 (호환용 보관)
    private var tuner: String = "RTL2832U" // 네이티브 튜너 조회 복구 전 기본값

    /** USB에 연결된 RTL-SDR 장치 탐색 */
    fun findDevice(): UsbDevice? {
        for ((_, device) in usbManager.deviceList) {
            if (RTL_DEVICES.any { it.first == device.vendorId && it.second == device.productId }) {
                Log.i(TAG, "RTL-SDR 발견: VID=0x${device.vendorId.toString(16).uppercase()}" +
                        " PID=0x${device.productId.toString(16).uppercase()} [${device.deviceName}]")
                return device
            }
        }
        Log.w(TAG, "RTL-SDR 장치 없음. USB-C OTG 케이블 연결을 확인하세요.")
        return null
    }

    /** 장치 USB 권한 보유 여부 */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * 장치 열기 및 AIS 수신 시작.
     * @param ppm  주파수 오차 (0=기본)
     * @param gain 0.1dB 단위 게인 (현 네이티브는 미사용, 보관만)
     */
    fun open(device: UsbDevice, ppm: Int = 0, gain: Int = 420): Boolean {
        gain10 = gain
        return try {
            usbConnection = usbManager.openDevice(device)
            if (usbConnection == null) {
                Log.e(TAG, "USB 장치 열기 실패 — 권한 없음")
                diagLine("openDevice 실패 (권한?)")
                return false
            }
            usbDevice = device
            val fd = usbConnection!!.fileDescriptor
            diagLine("USB fd=$fd ppm=$ppm gain=${gain / 10.0}dB")
            Log.i(TAG, "USB fd=$fd, AIS 수신 시작 (ppm=$ppm, gain=${gain / 10.0}dB)")
            val ret = startReceiving(fd, ppm)
            if (ret != 0) {
                Log.e(TAG, "JNI startReceiving 실패: ret=$ret")
                diagLine("startReceiving 실패 ret=$ret")
                close()
                return false
            }
            diagLine("startReceiving OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "open() 예외: ${e.message}")
            diagLine("open 예외 ${e.message}")
            false
        }
    }

    /** AIS 수신 중지 및 USB 해제 */
    fun close() {
        try { stopReceiving() } catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        usbConnection = null
        usbDevice = null
        Log.i(TAG, "RTL-SDR 닫힘")
    }

    /** 다음 NMEA 문장 (없으면 null) */
    fun pollNmea(): String? = try { nextNmeaMessage() } catch (_: Exception) { null }

    /** 현재 수신 중 여부 */
    fun running(): Boolean = try { isRunning() } catch (_: Exception) { false }

    /** 연결된 장치 이름 */
    fun deviceName(): String = usbDevice?.manufacturerName ?: usbDevice?.productName ?: "RTL-SDR"

    /** 튜너명 (네이티브 조회 복구 전 기본값) */
    fun tunerName(): String = tuner
}
