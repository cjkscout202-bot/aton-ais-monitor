package com.aiswatch

import android.content.Context
import android.hardware.usb.*
import android.util.Log

/**
 * RTL-SDR USB 드라이버
 * Android USB Host API로 RTL-SDR 장치를 열고 JNI(C)에 fd 전달
 *
 * RTL-SDR 벤더/프로덕트 ID:
 *   VID=0x0BDA (Realtek), PID=0x2832 (RTL2832U)
 *   VID=0x0BDA, PID=0x2838 (RTL2838U)
 */
class RtlSdrDriver(private val context: Context) {

    companion object {
        private const val TAG = "AISWatch-USB"

        /* RTL-SDR 장치 식별자 */
        private val RTL_DEVICES = listOf(
            Pair(0x0BDA, 0x2832),
            Pair(0x0BDA, 0x2838),
            Pair(0x0BDA, 0x2840),
            Pair(0x0BDA, 0x2848),
            Pair(0x0BDA, 0x2850),
        )

        /* JNI 네이티브 함수 선언 */
        @JvmStatic external fun startReceiving(fd: Int, ppm: Int, gain: Int): Int
        @JvmStatic external fun stopReceiving()
        @JvmStatic external fun nextNmeaMessage(): String?
        @JvmStatic external fun isRunning(): Boolean
        @JvmStatic external fun getDiagLog(): String
        @JvmStatic external fun clearDiag()

        init {
            System.loadLibrary("aiswatch-jni")
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    /** USB에 연결된 RTL-SDR 장치 탐색 */
    fun findDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (RTL_DEVICES.any { it.first == device.vendorId && it.second == device.productId }) {
                Log.i(TAG, "RTL-SDR 발견: VID=0x${device.vendorId.toString(16).uppercase()}" +
                        " PID=0x${device.productId.toString(16).uppercase()}" +
                        " [${device.deviceName}]")
                return device
            }
        }
        Log.w(TAG, "RTL-SDR 장치 없음. USB-C OTG 케이블로 연결했는지 확인하세요.")
        return null
    }

    /** 장치에 대한 USB 권한이 있는지 확인 */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * 장치 열기 및 AIS 수신 시작
     * @param device  USB 장치
     * @param ppm     주파수 오차 (0이면 자동, 보통 -50~+50)
     * @return 성공 여부
     */
    fun open(device: UsbDevice, ppm: Int = 0, gain: Int = 420): Boolean {
        try {
            usbConnection = usbManager.openDevice(device)
            if (usbConnection == null) {
                Log.e(TAG, "USB 장치 열기 실패 — 권한이 없습니다.")
                return false
            }
            usbDevice = device
            val fd = usbConnection!!.fileDescriptor
            Log.i(TAG, "USB fd=$fd, AIS 수신 시작 (ppm=$ppm)")

            val ret = startReceiving(fd, ppm, gain)
            if (ret != 0) {
                Log.e(TAG, "JNI startReceiving 실패: ret=$ret")
                close()
                return false
            }
            Log.i(TAG, "AIS 수신 시작 완료")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "open() 예외: ${e.message}")
            return false
        }
    }

    /** AIS 수신 중지 및 USB 연결 해제 */
    fun close() {
        try {
            stopReceiving()
        } catch (_: Exception) {}
        try {
            usbConnection?.close()
        } catch (_: Exception) {}
        usbConnection = null
        usbDevice = null
        Log.i(TAG, "RTL-SDR 닫힘")
    }

    /** 다음 NMEA 문장 반환 (없으면 null) */
    fun pollNmea(): String? = try { nextNmeaMessage() } catch (_: Exception) { null }

    /** 현재 수신 중 여부 */
    fun running(): Boolean = try { isRunning() } catch (_: Exception) { false }

    /** 연결된 장치 이름 */
    fun deviceName(): String = usbDevice?.manufacturerName ?: "RTL-SDR"

    /** 튜너명 (표시용; 네이티브 튜너 조회 미제공 버전 기본값) */
    fun tunerName(): String = "RTL-SDR"
}
