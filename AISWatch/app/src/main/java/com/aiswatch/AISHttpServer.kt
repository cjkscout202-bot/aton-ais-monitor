package com.aiswatch

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * 해로드 AIS 내장 HTTP 서버 (NanoHTTPD 기반)
 * 포트 8100에서 웹앱과 REST API 제공
 */
class AISHttpServer(
    private val context: Context,
    private val dataStore: AISDataStore,
    port: Int = 8100
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "AISWatch-HTTP"
    }

    /** 설정 문장 시리얼 송신 콜백 (MainActivity가 주입). 미연결 시 null */
    @Volatile var serialSender: ((String) -> Boolean)? = null
    /** 시리얼 Baud 전환 콜백 (MainActivity가 주입) */
    @Volatile var baudSetter: ((Int) -> Boolean)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveWebApp()
                uri == "/data.json"               -> serveData()
                uri == "/nmea.json"               -> serveNmea()
                uri == "/raw.json"                -> serveRaw()
                uri.startsWith("/history/")        -> serveHistory(uri)
                uri.startsWith("/msg6/")           -> serveMsg6(uri)
                uri.startsWith("/msg8/")           -> serveMsg8(uri)
                uri.startsWith("/periods/")       -> servePeriods(uri)
                uri == "/livrf.json"              -> serveLiveRf()
                uri == "/config/send"             -> serveConfigSend(session)
                uri == "/config/log"              -> serveConfigLog()
                uri == "/config/clear"            -> { dataStore.clearConfigLog(); jsonResponse("{\"ok\":true}") }
                uri == "/config/baud"             -> serveConfigBaud(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve 오류: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "500 Error: ${e.message}"
            )
        }
    }

    /** 웹앱 HTML 제공 (assets/index.html) */
    private fun serveWebApp(): Response {
        val html = context.assets.open("index.html")
            .bufferedReader(Charsets.UTF_8).readText()
        return newFixedLengthResponse(Response.Status.OK, "text/html;charset=UTF-8", html)
    }

    /** /data.json — 실시간 데이터 */
    private fun serveData(): Response {
        val json = dataStore.toDataJson()
        return jsonResponse(json)
    }

    /** /raw.json — 필터 전 원문 스트림 (진단용) */
    private fun serveRaw(): Response {
        val json = JSONObject().apply { put("raw", dataStore.getRawLog()) }
        return jsonResponse(json.toString())
    }

    /** /nmea.json — 최근 30분 NMEA 로그 (탭 열람용) */
    private fun serveNmea(): Response {
        val json = JSONObject().apply { put("nmea", dataStore.getNmeaFull()) }
        return jsonResponse(json.toString())
    }

    /** /history/{mmsi}.json */
    private fun serveHistory(uri: String): Response {
        val mmsi = uri.removePrefix("/history/").removeSuffix(".json").trim()
        val json = JSONObject().apply {
            put("mmsi", mmsi)
            put("history", dataStore.getHistory(mmsi))
        }
        return jsonResponse(json.toString())
    }

    /** /msg6/{mmsi}.json */
    private fun serveMsg6(uri: String): Response {
        val mmsi = uri.removePrefix("/msg6/").removeSuffix(".json").trim()
        val json = JSONObject().apply {
            put("mmsi", mmsi)
            put("records", dataStore.getMsg6(mmsi))
        }
        return jsonResponse(json.toString())
    }

    /** /msg8/{mmsi}.json */
    private fun serveMsg8(uri: String): Response {
        val mmsi = uri.removePrefix("/msg8/").removeSuffix(".json").trim()
        val json = JSONObject().apply {
            put("mmsi", mmsi)
            put("records", dataStore.getMsg8(mmsi))
        }
        return jsonResponse(json.toString())
    }

    /** /periods/MMSI — MT21/6/8 평균 수신 주기 반환 */
    private fun servePeriods(uri: String): Response {
        val mmsi = uri.removePrefix("/periods/").removeSuffix(".json").trimEnd('/')
        val json = dataStore.getPeriods(mmsi).toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    /** /livrf.json — 실시간 RF 모니터 (MMSI 무관, 최근 수신 신호 세기순) */
    private fun serveLiveRf(): Response {
        return jsonResponse(dataStore.getLiveRf(1800).toString())
    }

    /** /config/send?s=<문장> — 설정/Query 문장을 시리얼로 송신 (읽기=Query도 이 경로) */
    private fun serveConfigSend(session: IHTTPSession): Response {
        var s = session.parameters["s"]?.firstOrNull()?.trim()
        if (s.isNullOrEmpty()) {   // 폴백: 쿼리스트링 직접 파싱
            s = session.queryParameterString
                ?.split("&")?.firstOrNull { it.startsWith("s=") }
                ?.substringAfter("s=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.trim()
        }
        if (s.isNullOrEmpty() || !s.startsWith("$")) {
            return jsonResponse("{\"ok\":false,\"err\":\"invalid sentence\"}")
        }
        val sender = serialSender
        if (sender == null) {
            return jsonResponse("{\"ok\":false,\"err\":\"serial not connected\"}")
        }
        val ok = try { sender(s) } catch (e: Exception) { false }
        if (ok) dataStore.logConfig("TX", s)
        return jsonResponse("{\"ok\":$ok}")
    }

    /** /config/baud?rate=115200 — 시리얼 Baud 전환 후 재연결 */
    private fun serveConfigBaud(session: IHTTPSession): Response {
        val rate = session.parameters["rate"]?.firstOrNull()?.toIntOrNull()
            ?: session.queryParameterString?.substringAfter("rate=", "")?.toIntOrNull()
        if (rate != 38400 && rate != 115200) return jsonResponse("{\"ok\":false,\"err\":\"rate must be 38400 or 115200\"}")
        val ok = try { baudSetter?.invoke(rate) ?: false } catch (e: Exception) { false }
        return jsonResponse("{\"ok\":$ok,\"rate\":$rate}")
    }

    /** /config/log — 설정 송수신 로그 (TX/RX) */
    private fun serveConfigLog(): Response {
        val json = JSONObject().apply { put("log", dataStore.getConfigLog()) }
        return jsonResponse(json.toString())
    }

    private fun jsonResponse(json: String): Response {
        val r = newFixedLengthResponse(
            Response.Status.OK, "application/json;charset=UTF-8", json
        )
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }
}
