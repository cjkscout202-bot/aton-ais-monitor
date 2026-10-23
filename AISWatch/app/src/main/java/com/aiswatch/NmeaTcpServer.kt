package com.aiswatch

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * NMEA TCP 브로드캐스트 서버 (기본 포트 10110).
 *
 * 외부 앱(ECDIS·플로터 등)이 127.0.0.1:10110 으로 접속하면
 * 수신된 AIS NMEA 문장을 실시간으로 전달한다.
 *  - 다중 클라이언트 지원(CopyOnWriteArrayList)
 *  - 끊어진 소켓 자동 정리
 *  - MainActivity: start() / broadcast(line) / stop() / isRunning() / clientCount() / txCount()
 */
class NmeaTcpServer(private val port: Int = 10110) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    private val clients = CopyOnWriteArrayList<Socket>()
    private val txCounter = AtomicLong(0)

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(TAG, "NMEA TCP 서버 시작 :$port")
                while (running) {
                    try {
                        val sock = ss.accept()
                        try { sock.tcpNoDelay = true } catch (_: Exception) {}
                        clients.add(sock)
                        Log.i(TAG, "클라이언트 접속: ${sock.inetAddress} (총 ${clients.size})")
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "서버 시작 실패(:$port): ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** NMEA 한 줄을 모든 클라이언트에 전송 (CRLF 보장) */
    fun broadcast(line: String) {
        if (!running || clients.isEmpty()) return
        val data = (line.trimEnd('\r', '\n') + "\r\n").toByteArray(Charsets.US_ASCII)
        val dead = ArrayList<Socket>()
        for (c in clients) {
            try {
                val os = c.getOutputStream()
                os.write(data); os.flush()
                txCounter.incrementAndGet()
            } catch (e: Exception) {
                dead.add(c)
            }
        }
        for (d in dead) {
            try { d.close() } catch (_: Exception) {}
            clients.remove(d)
        }
    }

    fun clientCount(): Int = clients.size
    fun txCount(): Long = txCounter.get()
    fun isRunning(): Boolean = running

    fun stop() {
        running = false
        for (c in clients) { try { c.close() } catch (_: Exception) {} }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { acceptThread?.interrupt() } catch (_: Exception) {}
        acceptThread = null
    }

    companion object { private const val TAG = "NmeaTcpServer" }
}
