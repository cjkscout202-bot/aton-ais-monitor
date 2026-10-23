package com.aiswatch

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * AIS 데이터 파싱 및 저장소
 * ais_server_v6.py의 파싱 로직을 Kotlin으로 이식
 */
class AISDataStore {

    companion object {
        private const val TAG = "AISWatch-Parser"
        private const val AIS_CHARSET = "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#\$%&'()*+,-./0123456789:;<=>?"

        private val EPFD = mapOf(
            0 to "0-미정의", 1 to "1-GPS", 2 to "2-GLONASS",
            3 to "3-GPS+GLONASS", 4 to "4-Loran-C", 5 to "5-Chayka",
            6 to "6-통합항법", 7 to "7-측량값", 8 to "8-Galileo", 15 to "15-내부GNSS"
        )

        private val ATON_KO = mapOf(
            0 to "미지정", 1 to "기준점", 2 to "레이콘",
            3 to "고정구조물(해상)", 5 to "단일등", 6 to "섹터등",
            7 to "도등(전방등)", 8 to "도등(후방등)",
            9 to "방위표지(북)", 10 to "방위표지(동)", 11 to "방위표지(남)", 12 to "방위표지(서)",
            13 to "측면표지(좌현)", 14 to "측면표지(우현)",
            17 to "고립장해표지", 18 to "안전수역표지", 19 to "특수표지",
            20 to "방위부표(북)", 21 to "방위부표(동)", 22 to "방위부표(남)", 23 to "방위부표(서)",
            24 to "측면부표(좌현)", 25 to "측면부표(우현)",
            28 to "고립장해부표", 29 to "안전수역부표", 30 to "특수부표",
            31 to "등선/LANBY/해양구조물"
        )
    }

    /* ─── 데이터 저장소 ─────────────────────────────── */
    val vessels      = ConcurrentHashMap<String, JSONObject>()
    val atons        = ConcurrentHashMap<String, JSONObject>()
    val atonHistory  = ConcurrentHashMap<String, ArrayDeque<JSONObject>>()
    val msg6Data     = ConcurrentHashMap<String, ArrayDeque<JSONObject>>()
    val msg8Data     = ConcurrentHashMap<String, ArrayDeque<JSONObject>>()
    val nmeaLog      = ArrayDeque<JSONObject>()

    /* ─── 수신 통계 ──────────────────────────────────── */
    val totalSentences = AtomicLong(0)
    val totalVessels   = AtomicLong(0)
    val totalAtons     = AtomicLong(0)
    val totalMsg6      = AtomicLong(0)
    val mt21RxTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    val mt6RxTimes  = ConcurrentHashMap<String, ArrayDeque<Long>>()
    val mt8RxTimes  = ConcurrentHashMap<String, ArrayDeque<Long>>()
    val totalMsg8      = AtomicLong(0)
    var lastRxTime     = 0L

    /* ─── 신호세기 저장 (RTL-SDR 전용, PWR=dB, PPM=주파수오차) ───── */
    val signalPwr   = ConcurrentHashMap<String, ArrayDeque<Float>>()  // MMSI별 최근 PWR 이력
    val signalLast  = ConcurrentHashMap<String, FloatArray>()         // MMSI별 [최신PWR, 최신PPM, 시각(초)]
    val signalMt    = ConcurrentHashMap<String, Int>()                // MMSI별 최신 메시지 타입
    val signalCh    = ConcurrentHashMap<String, String>()             // MMSI별 최신 채널(A/B)
    // 채널별 최근 신호 (3A 채널 세기 막대용): key="A"/"B" → 최근 (시각ms, pwr)
    val chSignals   = ConcurrentHashMap<String, ArrayDeque<FloatArray>>()
    // 전체 노이즈 플로어 추정용: 최근 수신 PWR들의 저분위값 근사
    private val noiseWindow = ArrayDeque<Float>()
    // 실시간 피크 추적 (안테나 방향 탐색용): 최근 창에서 관측된 최고 PWR
    @Volatile private var peakPwr = -999f
    @Volatile private var peakAt  = 0L

    private fun recordSignal(mmsi: String, pwr: Float?, ppm: Float?, mt: Int = 0, ch: String? = null) {
        if (pwr == null) return
        val dq = signalPwr.getOrPut(mmsi) { ArrayDeque() }
        synchronized(dq) { dq.addFirst(pwr); while (dq.size > 30) dq.removeLast() }
        signalLast[mmsi] = floatArrayOf(pwr, ppm ?: 0f, (System.currentTimeMillis()/1000L).toFloat())
        if (mt != 0) signalMt[mmsi] = mt
        synchronized(noiseWindow) { noiseWindow.addLast(pwr); while (noiseWindow.size > 200) noiseWindow.removeFirst() }
        // 피크 갱신 (5초 지나면 리셋)
        val nowMs = System.currentTimeMillis()
        if (pwr > peakPwr || nowMs - peakAt > 5000) { peakPwr = pwr; peakAt = nowMs }
        // 채널 기록 (3A 채널 세기 막대용)
        if (ch == "A" || ch == "B") {
            signalCh[mmsi] = ch
            val cdq = chSignals.getOrPut(ch) { ArrayDeque() }
            synchronized(cdq) { cdq.addLast(floatArrayOf(nowMs.toFloat(), pwr)); while (cdq.size > 300) cdq.removeFirst() }
        }
    }

    /** 노이즈 플로어 근사 = 최근 수신 PWR들의 하위 20% 평균 (SNR 계산용) */
    private fun noiseFloor(): Float {
        val snap = synchronized(noiseWindow) { noiseWindow.toList() }
        if (snap.size < 5) return Float.NaN
        val sorted = snap.sorted()
        val n = maxOf(1, sorted.size / 5)
        return sorted.take(n).average().toFloat()
    }

    /** 최근 60초 수신 문장 수 */
    private fun rxPerMin(): Int {
        val now = System.currentTimeMillis()
        return synchronized(rxWindow) { rxWindow.count { now - it <= 60_000 } }
    }

    /**
     * 실시간 RF 모니터: 최근 windowSec초(기본 30분) 내 수신된 모든 송신원을 신호세기순 반환.
     * MMSI 지정 없이 "이 자리에서 가장 강한(가까운) 신호 + 채널별 활성도"를 파악하는 현장 점검용.
     */
    fun getLiveRf(windowSec: Int = 1800): JSONObject = JSONObject().apply {
        val nowS = System.currentTimeMillis() / 1000L
        val nowMs = System.currentTimeMillis()
        val nf = noiseFloor()
        val list = ArrayList<JSONObject>()
        var strongestPwr = -999f; var strongestMmsi = ""
        var sum = 0.0; var cnt = 0
        for ((mmsi, last) in signalLast) {
            val age = nowS - last[2].toLong()
            if (age > windowSec) continue
            val pwr = last[0]
            // 최근 이력 중 최고치(peak) = 30분 내 이 송신원의 최강 신호
            val hist = signalPwr[mmsi]?.let { synchronized(it){ it.toList() } } ?: emptyList()
            val peak = (hist.maxOrNull() ?: pwr)
            val o = JSONObject()
            o.put("mmsi", mmsi)
            o.put("pwr", (pwr * 10).toInt() / 10.0)          // 최신 세기
            o.put("peak", (peak * 10).toInt() / 10.0)        // 30분 내 최고 세기
            o.put("ppm", (last[1] * 10).toInt() / 10.0)
            o.put("mt", signalMt[mmsi] ?: 0)
            o.put("ch", signalCh[mmsi] ?: "-")
            o.put("age", age)
            o.put("n", hist.size)
            if (!nf.isNaN()) o.put("snr", ((pwr - nf) * 10).toInt() / 10.0)
            list.add(o)
            sum += pwr; cnt++
            if (peak > strongestPwr) { strongestPwr = peak; strongestMmsi = mmsi }
        }
        // 30분 내 최고 세기(peak) 내림차순 정렬
        list.sortByDescending { it.getDouble("peak") }
        val sorted = JSONArray(); for (o in list) sorted.put(o)
        put("list", sorted)
        put("count", cnt)
        put("window_min", windowSec / 60)
        if (cnt > 0) {
            put("max_pwr", (strongestPwr * 10).toInt() / 10.0)
            put("max_mmsi", strongestMmsi)
            put("max_mt", signalMt[strongestMmsi] ?: 0)
            put("avg_pwr", (sum / cnt * 10).toInt() / 10.0)
            if (!nf.isNaN()) {
                put("noise_floor", (nf * 10).toInt() / 10.0)
                put("max_snr", ((strongestPwr - nf) * 10).toInt() / 10.0)
            }
        }
        // ── 채널별 세기 (3A: AIS 2채널 시각화) ──
        put("channels", JSONObject().apply {
            for (chName in listOf("A", "B")) {
                val cdq = chSignals[chName]?.let { synchronized(it){ it.toList() } } ?: emptyList()
                // 최근 10초 내 신호만 (현재 활성도)
                val recent = cdq.filter { nowMs - it[0] <= 10_000L }.map { it[1] }
                val o = JSONObject()
                if (recent.isNotEmpty()) {
                    o.put("max", (recent.maxOrNull()!! * 10).toInt() / 10.0)
                    o.put("avg", (recent.average() * 10).toInt() / 10.0)
                    o.put("cnt", recent.size)
                } else {
                    o.put("cnt", 0)
                }
                // 30분 누적 수신 수
                o.put("total", cdq.count { nowMs - it[0] <= windowSec*1000L })
                put(chName, o)
            }
            put("freq_a", "161.975"); put("freq_b", "162.025")
        })
        put("rpm", rxPerMin())
    }

    /* 입력 소스/출력 상태 (MainActivity 가 갱신) */
    @Volatile var inputMode:   String = "-"
    @Volatile var inputDevice: String = "-"
    @Volatile var nmeaOut:     String = "OFF"
    // 원문 스트림 버퍼 (isValidAis 필터 전 모든 수신줄, 최대 500줄)
    private val rawLog = ArrayDeque<String>()
    val startTime      = System.currentTimeMillis()
    val rxWindow       = ArrayDeque<Long>()

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /* ─── NMEA 처리 진입점 ───────────────────────────── */
    private val fragBuf = ConcurrentHashMap<String, Array<String?>>()

    fun processNmea(line: String) {
        try {
            val parts = line.trim().split(",")
            // prefix: 표준(AI) + 확장(BS/AB/SA 등 수신기별 talker ID)
            val p0 = parts[0]
            val isAis = p0.contains("VDM") || p0.contains("VDO")
            if (parts.size < 6 || !isAis) return

            // 브리지가 NMEA 뒤에 부착한 신호정보(,PWR:x,PPM:y) 추출 (RTL-SDR만 존재)
            var sigPwr: Float? = null
            var sigPpm: Float? = null
            for (p in parts) {
                when {
                    p.startsWith("PWR:") -> sigPwr = p.removePrefix("PWR:").toFloatOrNull()
                    p.startsWith("PPM:") -> sigPpm = p.removePrefix("PPM:").toFloatOrNull()
                }
            }

            totalSentences.incrementAndGet()
            lastRxTime = System.currentTimeMillis()
            synchronized(rxWindow) {
                rxWindow.addLast(lastRxTime)
                while (rxWindow.size > 600) rxWindow.removeFirst()
            }

            val total = parts[1].toIntOrNull() ?: 1
            val num   = parts[2].toIntOrNull() ?: 1
            val payload: String
            val fill: Int
            if (total > 1) {
                val _seq = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: ""
                val _ch  = parts.getOrNull(4) ?: ""
                // seqId 있으면 seq+채널, 없으면 total+채널로 key 생성.
                // (payload 첫 글자는 프래그먼트마다 달라 키에 포함하면 재조립 실패 → 제외)
                val key = if (_seq.isNotBlank()) "${_seq}-${_ch}" else "t${total}-${_ch}"
                val buf = fragBuf.getOrPut(key) { arrayOfNulls(total) }
                if (num in 1..total) buf[num - 1] = parts[5]
                if (buf.any { it == null }) return
                payload = buf.joinToString("") { it ?: "" }
                fill = parts.getOrNull(6)?.substringBefore("*")?.toIntOrNull() ?: 0
                fragBuf.remove(key)
            } else {
                payload = parts[5]
                fill = parts.getOrNull(6)?.substringBefore("*")?.toIntOrNull() ?: 0
            }
            val bits = payloadToBits(payload, fill)
            if (bits.length < 38) return
            val mt   = bits(bits, 0, 6)

            val tag = when (mt) {
                1, 2, 3, 5, 18, 24 -> "vessel"
                6  -> "msg6"
                8  -> "msg8"
                21 -> "aton"
                else -> "other"
            }

            val ch = parts.getOrNull(4)?.takeIf { it == "A" || it == "B" }

            if (mt != 21) addNmea(line, mt, tag)   // MT21은 parseMessage에서 finalTag 확정 후 기록
            parseMessage(bits, mt, line, sigPwr, sigPpm, ch)

            // 오래된 데이터 정리
            cleanExpired()

        } catch (e: Exception) {
            Log.e(TAG, "processNmea 오류: ${e.message}")
        }
    }

    /* ─── 메시지 타입별 파싱 ─────────────────────────── */
    private fun parseMessage(bits: String, mt: Int, raw: String,
                             sigPwr: Float? = null, sigPpm: Float? = null, sigCh: String? = null) {
        val mmsi = bits(bits, 8, 38).toString()
        val now  = System.currentTimeMillis() / 1000.0
        recordSignal(mmsi, sigPwr, sigPpm, mt, sigCh)   // 모든 타입 공통: 송신원 MMSI별 신호세기 기록

        when (mt) {
            1, 2, 3 -> {
                val sog = bits(bits, 50, 60) / 10.0
                val lon = bits(bits, 61, 89, signed=true) / 600000.0
                val lat = bits(bits, 89, 116, signed=true) / 600000.0
                val cog = bits(bits, 116, 128) / 10.0
                val hdg = bits(bits, 128, 137)
                val st  = bits(bits, 38, 42)
                if (abs(lat) > 90 || abs(lon) > 180 || (lat == 0.0 && lon == 0.0)) return
                val prev = vessels[mmsi] ?: JSONObject()
                vessels[mmsi] = JSONObject().apply {
                    put("mmsi", mmsi); put("name", prev.optString("name",""))
                    put("lat", lat.round(6)); put("lon", lon.round(6))
                    put("sog", sog.round(1)); put("cog", cog.round(1))
                    put("heading", hdg); put("nav_status", st); put("msg_type", mt)
                    put("ship_type", prev.optInt("ship_type",0))
                    put("destination", prev.optString("destination",""))
                    put("last_seen", now)
                }
                totalVessels.incrementAndGet()
            }

            5 -> {
                if (bits.length < 420) return
                val name = bstr(bits, 112, 20)
                val st   = bits(bits, 232, 240)
                val dest = bstr(bits, 302, 20)
                val obj  = vessels.getOrPut(mmsi) { JSONObject().apply {
                    put("mmsi",mmsi); put("lat",0); put("lon",0)
                    put("sog",0); put("cog",0); put("heading",0)
                    put("nav_status",0); put("msg_type",5); put("last_seen",now)
                }}
                obj.put("name", name); obj.put("ship_type", st); obj.put("destination", dest)
            }

            24 -> {
                val partNo = bits(bits, 38, 40)
                val obj = vessels.getOrPut(mmsi) { JSONObject().apply {
                    put("mmsi",mmsi); put("lat",0); put("lon",0)
                    put("sog",0); put("cog",0); put("heading",0)
                    put("nav_status",0); put("msg_type",24); put("last_seen",now)
                }}
                if (partNo == 0) {
                    if (bits.length >= 160) { val nm = bstr(bits, 40, 20); if (nm.isNotBlank()) obj.put("name", nm) }
                } else {
                    if (bits.length >= 48) obj.put("ship_type", bits(bits, 40, 48))
                }
            }

            18 -> {
                val sog = bits(bits, 46, 56) / 10.0
                val lon = bits(bits, 57, 85, signed=true) / 600000.0
                val lat = bits(bits, 85, 112, signed=true) / 600000.0
                val cog = bits(bits, 112, 124) / 10.0
                val hdg = bits(bits, 124, 133)
                if (abs(lat) > 90 || abs(lon) > 180 || (lat == 0.0 && lon == 0.0)) return
                val prev = vessels[mmsi] ?: JSONObject()
                vessels[mmsi] = JSONObject().apply {
                    put("mmsi",mmsi); put("name",prev.optString("name",""))
                    put("lat",lat.round(6)); put("lon",lon.round(6))
                    put("sog",sog.round(1)); put("cog",cog.round(1))
                    put("heading",hdg); put("nav_status",0); put("msg_type",18)
                    put("ship_type",prev.optInt("ship_type",0)); put("destination","")
                    put("last_seen",now)
                }
                totalVessels.incrementAndGet()
            }

            21 -> {
                val atonType = bits(bits, 38, 43)
                val name     = bstr(bits, 43, 20)
                val accuracy = if (bits.length >= 164) bits(bits, 163, 164) else 0  // 1=고정밀(≤10m), 0=저정밀(>10m)
                val lon      = bits(bits, 164, 192, signed=true) / 600000.0
                val lat      = bits(bits, 192, 219, signed=true) / 600000.0
                val dimA     = if (bits.length >= 228) bits(bits, 219, 228) else 0
                val dimB     = if (bits.length >= 237) bits(bits, 228, 237) else 0
                val dimC     = if (bits.length >= 243) bits(bits, 237, 243) else 0
                val dimD     = if (bits.length >= 249) bits(bits, 243, 249) else 0
                val epfd     = if (bits.length >= 253) bits(bits, 249, 253) else 0
                val utcSec   = if (bits.length >= 259) bits(bits, 253, 259) else 63
                val offPos   = if (bits.length >= 260) bits(bits, 259, 260) else 0
                val atonStat = if (bits.length >= 268) bits(bits, 260, 268) else 0
                val raim     = if (bits.length >= 269) bits(bits, 268, 269) else 0
                var virtual  = if (bits.length >= 270) bits(bits, 269, 270) else 0
                val nameExt  = if (bits.length > 272) bstr(bits, 272, 14) else ""
                val fullName = (name + nameExt).trim()
                if ("SYNTHETIC" in fullName.uppercase() || "VIRTUAL" in fullName.uppercase()) virtual = 1

                // ── MT21 수신 자체는 위치 유효성과 무관하게 항상 기록 ──
                //   (장비 점검 목적: "송신하고 있다"는 사실이 위치보다 우선)
                val finalTag = if (virtual==1) "aton_virtual" else "aton_physical"
                recordRx(mt21RxTimes, mmsi)          // 수신주기
                addNmea(raw, mt, finalTag)           // NMEA 로그
                totalAtons.incrementAndGet()         // 카운트

                // 위치 유효성 (지도·좌표 이력에만 적용)
                val posValid = !(abs(lat) >= 91 || abs(lon) >= 181 || (lat == 0.0 && lon == 0.0))
                val koName = ATON_KO[atonType] ?: "유형$atonType"
                val prevAton = atons[mmsi]
                atons[mmsi] = JSONObject().apply {
                    put("mmsi", mmsi); put("name", fullName.ifEmpty { "AtoN-$mmsi" })
                    // 위치가 유효하면 갱신, 아니면 직전 위치 유지(있으면)
                    if (posValid) { put("lat", lat.round(6)); put("lon", lon.round(6)) }
                    else if (prevAton != null && prevAton.has("lat")) {
                        put("lat", prevAton.getDouble("lat")); put("lon", prevAton.getDouble("lon"))
                    }
                    put("aton_type", atonType); put("aton_name_ko", koName)
                    put("accuracy", accuracy); put("virtual", virtual == 1); put("off_position", offPos == 1)
                    put("epfd", epfd); put("epfd_name", EPFD[epfd] ?: "EPFD$epfd")
                    put("utc_sec", utcSec); put("raim", raim == 1)
                    put("aton_status", atonStat); put("msg_type", 21)
                    put("pos_valid", posValid)
                    put("last_seen", now)
                    if (dimA+dimB+dimC+dimD > 0) {
                        put("dim_a",dimA); put("dim_b",dimB)
                        put("dim_c",dimC); put("dim_d",dimD)
                    }
                }
                // 좌표 이력은 위치가 유효할 때만
                if (posValid) {
                    val rec = JSONObject().apply {
                        put("ts", tf.format(Date())); put("ts_full", df.format(Date()))
                        put("lat", lat.round(6)); put("lon", lon.round(6))
                        put("off_position", offPos==1); put("virtual", virtual==1)
                        put("aton_status", atonStat); put("epfd", epfd)
                        put("epfd_name", EPFD[epfd] ?: "EPFD$epfd")
                        put("utc_sec", utcSec); put("raim", raim==1); put("raw", raw)
                    }
                    atonHistory.getOrPut(mmsi) { ArrayDeque() }.apply {
                        addFirst(rec); while (size > 100) removeLast()
                    }
                }
            }

            6 -> {
                if (bits.length < 120) return
                val dac  = bits(bits, 72, 82)
                val fi   = bits(bits, 82, 88)
                val atonM = bits(bits, 88, 118).toString()
                val func = bits(bits, 118, 120)
                if (dac != 440 || fi !in listOf(51,55)) return
                if (bits.length < 220) return
                val year=bits(bits,120,134); val month=bits(bits,134,138); val day=bits(bits,138,143)
                val hour=bits(bits,143,148); val min=bits(bits,148,154);  val sec=bits(bits,154,160)
                val dtStr = "%04d-%02d-%02d %02d:%02d:%02d".format(year,month,day,hour,min,sec)
                val latRaw=bits(bits,160,184,signed=true); val lonRaw=bits(bits,184,209,signed=true)
                val latDeg=latRaw/60000.0; val lonDeg=lonRaw/60000.0
                val lantern=bits(bits,209,210); val racon=bits(bits,210,212)
                val battery=bits(bits,212,213); val charger=bits(bits,213,214)
                val solar=bits(bits,214,215); val dataLog=bits(bits,215,217)
                val mainV  = if(bits.length>=228) bits(bits,219,228)/10.0 else null
                val solarV = if(bits.length>=237) bits(bits,228,237)/10.0 else null
                val battV  = if(bits.length>=246) bits(bits,237,246)/10.0 else null
                val aisI   = if(bits.length>=267) bits(bits,255,267)/100.0 else null
                val lantI  = if(bits.length>=279) bits(bits,267,279)/100.0 else null
                val dlI    = if(bits.length>=291) bits(bits,279,291)/100.0 else null
                val chgPos = if(bits.length>=303) bits(bits,291,303)/100.0 else null
                val chgNeg = if(bits.length>=315) bits(bits,303,315)/100.0 else null
                val rec = JSONObject().apply {
                    put("ts",tf.format(Date())); put("ts_full",df.format(Date()))
                    put("fi",fi); put("aton_mmsi",atonM); put("function",func)
                    put("device_dt",dtStr)
                    if(abs(latDeg)<=90) put("lat",latDeg.round(5))
                    if(abs(lonDeg)<=180) put("lon",lonDeg.round(5))
                    put("lantern",lantern); put("racon",racon); put("battery",battery)
                    put("charger",charger); put("solar",solar); put("data_logger",dataLog)
                    mainV?.let{put("main_v",it.round(2))}; solarV?.let{put("solar_v",it.round(2))}
                    battV?.let{put("batt_v",it.round(2))}; aisI?.let{put("ais_i",it.round(2))}
                    lantI?.let{put("lantern_i",it.round(2))}; dlI?.let{put("dl_i",it.round(2))}
                    chgPos?.let{put("chg_pos",it.round(2))}; chgNeg?.let{put("chg_neg",it.round(2))}
                    put("raw",raw)
                }
                msg6Data.getOrPut(atonM) { ArrayDeque() }.apply {
                    addFirst(rec); while(size>50) removeLast()
                }
                recordRx(mt6RxTimes, atonM)   // 수신주기 분석용 시각 기록
                totalMsg6.incrementAndGet()
                if (abs(latDeg) <= 90.0 && abs(lonDeg) <= 180.0 && (latDeg != 0.0 || lonDeg != 0.0)) {
                    val aObj = atons.getOrPut(atonM) { JSONObject().apply {
                        put("mmsi", atonM); put("msg_type", 21); put("virtual", false)
                        put("aton_type", 0); put("lat", 0); put("lon", 0)
                    }}
                    if (!aObj.has("lat") || aObj.optDouble("lat", 0.0) == 0.0) {
                        aObj.put("lat", latDeg.round(5)); aObj.put("lon", lonDeg.round(5))
                    }
                    aObj.put("last_seen", now); aObj.put("has_msg6", true)
                }
            }

            8 -> {
                if (bits.length < 56) return
                val dac = bits(bits, 40, 50); val fi = bits(bits, 50, 56)
                if (dac != 1 || fi !in listOf(11,31)) return
                if (bits.length < 200) return
                val lonRaw=bits(bits,56,81,signed=true); val latRaw=bits(bits,81,105,signed=true)
                val lonDeg=lonRaw/60000.0; val latDeg=latRaw/60000.0
                val utcDay=bits(bits,106,111); val utcHr=bits(bits,111,116); val utcMin=bits(bits,116,122)
                val windSp = bits(bits,122,129).let{ if(it==127) null else it }
                val windGu = bits(bits,129,136).let{ if(it==127) null else it }
                val windDi = bits(bits,136,145).let{ if(it==360) null else it }
                val airT   = bits(bits,154,165,signed=true).let{ if(it==-1024) null else it/10.0 }
                val humi   = bits(bits,165,172).let{ if(it==101) null else it }
                val press  = bits(bits,182,191)
                val pressV = if(press==511) null else if(press<=401) press+799 else null
                val wlevel = if(bits.length>=213) bits(bits,201,213) else null
                val wlevM  = wlevel?.let{ if(it>=4001) null else (it/100.0)-10.0 }
                val waveH  = if(bits.length>=262) bits(bits,254,262)/10.0 else null
                val waterT = if(bits.length>=314) bits(bits,304,314,signed=true).let{ if(it==501) null else it/10.0 } else null
                val rec = JSONObject().apply {
                    put("ts",tf.format(Date())); put("ts_full",df.format(Date()))
                    put("fi",fi); put("src_mmsi",mmsi)
                    if(abs(latDeg)<=90) put("lat",latDeg.round(5))
                    if(abs(lonDeg)<=180) put("lon",lonDeg.round(5))
                    put("utc","%02d일 %02d:%02d".format(utcDay,utcHr,utcMin))
                    windSp?.let{put("wind_speed",it)}; windGu?.let{put("wind_gust",it)}
                    windDi?.let{put("wind_dir",it)}; airT?.let{put("air_temp",it.round(1))}
                    humi?.let{put("humidity",it)}; pressV?.let{put("pressure",it)}
                    wlevM?.let{put("water_level",it.round(2))}
                    waveH?.let{put("wave_height",it.round(1))}
                    waterT?.let{put("water_temp",it.round(1))}
                    put("raw",raw)
                }
                msg8Data.getOrPut(mmsi) { ArrayDeque() }.apply {
                    addFirst(rec); while(size>50) removeLast()
                }
                recordRx(mt8RxTimes, mmsi)   // 수신주기 분석용 시각 기록
                totalMsg8.incrementAndGet()
            }
        }
    }

    /* ─── NMEA 로그 ──────────────────────────────────── */
    @Synchronized
    private fun addNmea(line: String, mt: Int, tag: String) {
        val nowMs = System.currentTimeMillis()
        nmeaLog.addFirst(JSONObject().apply {
            put("ts", tf.format(Date())); put("raw", line)
            put("mt", mt); put("tag", tag); put("t", nowMs)
        })
        val cutoff = nowMs - 30 * 60 * 1000L   // 최근 30분 보관
        while (nmeaLog.isNotEmpty() && nmeaLog.last().optLong("t", 0L) < cutoff) nmeaLog.removeLast()
        while (nmeaLog.size > 1500) nmeaLog.removeLast()
    }

    @Synchronized
    private fun updateNmeaTag(raw: String, tag: String) {
        nmeaLog.firstOrNull { it.optString("raw") == raw }?.put("tag", tag)
    }

    /* ─── 만료 정리 ──────────────────────────────────── */
    private fun cleanExpired() {
        val now = System.currentTimeMillis() / 1000.0
        vessels.entries.removeIf  { now - it.value.optDouble("last_seen",0.0) > 600  }
        atons.entries.removeIf    { now - it.value.optDouble("last_seen",0.0) > 3600 }
    }

    /* ─── API 응답 생성 ──────────────────────────────── */
    fun toDataJson(): String {
        val now = System.currentTimeMillis()
        val rpm = synchronized(rxWindow) {
            rxWindow.count { now - it <= 60_000 }
        }
        return JSONObject().apply {
            put("vessels",    JSONArray(vessels.values.toList()))
            put("atons",      JSONArray(atons.values.map { a ->
                val m = a.optString("mmsi", "")
                JSONObject(a.toString()).apply {
                    put("has_msg6", (msg6Data[m]?.size ?: 0) > 0)
                    put("has_msg8", (msg8Data[m]?.size ?: 0) > 0)
                }
            }))
            put("nmea",       JSONArray(nmeaLog.take(200)))
            put("msg6_count", msg6Data.values.sumOf { it.size })
            put("msg8_count", msg8Data.values.sumOf { it.size })
            put("stats", JSONObject().apply {
                put("ais_catcher_ok",  true)
                put("total_sentences", totalSentences.get())
                put("total_vessels",   totalVessels.get())
                put("total_atons",     totalAtons.get())
                put("total_msg6",      totalMsg6.get())
                put("total_msg8",      totalMsg8.get())
                put("rx_per_min",      rpm)
                put("uptime_sec",      (now - startTime) / 1000)
                put("last_rx",         lastRxTime / 1000.0)
                put("input_mode",      inputMode)
                put("input_device",    inputDevice)
                put("nmea_out",        nmeaOut)
            })
        }.toString()
    }

    /** 메시지 수신시각을 최신이 앞(addFirst)이 되도록 기록. 최근 50개만 유지 */
    private fun recordRx(map: ConcurrentHashMap<String, ArrayDeque<Long>>, key: String) {
        val dq = map.getOrPut(key) { ArrayDeque() }
        synchronized(dq) {
            dq.addFirst(System.currentTimeMillis())
            while (dq.size > 50) dq.removeLast()
        }
    }

    fun calcPeriod(times: ArrayDeque<Long>?): Double? {
        if (times == null) return null
        val list = synchronized(times) { times.toList() }
        if (list.size < 2) return null
        val diffs = (0 until list.size-1).map { (list[it]-list[it+1])/1000.0 }.filter { it>0 }
        return if (diffs.isEmpty()) null else diffs.average()
    }
    fun getPeriods(mmsi: String) = JSONObject().apply {
        calcPeriod(mt21RxTimes[mmsi])?.let { put("mt21", it.toLong()) }
        calcPeriod(mt6RxTimes[mmsi])?.let  { put("mt6",  it.toLong()) }
        calcPeriod(mt8RxTimes[mmsi])?.let  { put("mt8",  it.toLong()) }
        put("mt21_cnt", mt21RxTimes[mmsi]?.size ?: 0)
        put("mt6_cnt",  mt6RxTimes[mmsi]?.size  ?: 0)
        put("mt8_cnt",  mt8RxTimes[mmsi]?.size  ?: 0)
        // ── 수신 시각 목록 (최근 20개, epoch ms, 최신순) — 시각·간격 평가용 ──
        put("mt21_times", rxTimesArray(mt21RxTimes[mmsi]))
        put("mt6_times",  rxTimesArray(mt6RxTimes[mmsi]))
        put("mt8_times",  rxTimesArray(mt8RxTimes[mmsi]))
        // ── 신호세기 (RTL-SDR 전용) ──
        val last = signalLast[mmsi]
        if (last != null) {
            val pwr = last[0]
            put("rf_pwr", (pwr * 10).toInt() / 10.0)        // 최신 신호세기(dB)
            put("rf_ppm", (last[1] * 10).toInt() / 10.0)    // 주파수오차
            // 최근 평균 PWR
            val hist = signalPwr[mmsi]?.let { synchronized(it){ it.toList() } } ?: emptyList()
            if (hist.isNotEmpty()) put("rf_avg", (hist.average() * 10).toInt() / 10.0)
            put("rf_n", hist.size)
            // SNR 근사 = 신호세기 - 노이즈플로어
            val nf = noiseFloor()
            if (!nf.isNaN()) put("rf_snr", ((pwr - nf) * 10).toInt() / 10.0)
            put("rf_age", (System.currentTimeMillis()/1000L) - last[2].toLong())  // 마지막 수신 경과(초)
        }
    }
    private fun rxTimesArray(dq: ArrayDeque<Long>?): JSONArray {
        val list = dq?.let { synchronized(it) { it.toList() } } ?: emptyList()
        return JSONArray(list.take(20))   // 최신순(addFirst) epoch ms
    }
    fun getHistory(mmsi: String) = JSONArray(atonHistory[mmsi]?.take(50) ?: emptyList<JSONObject>())
    fun getMsg6(mmsi: String)    = JSONArray(msg6Data[mmsi]?.take(50) ?: emptyList<JSONObject>())
    fun getMsg8(mmsi: String)    = JSONArray(msg8Data[mmsi]?.take(50) ?: emptyList<JSONObject>())
    @Synchronized fun getNmeaFull(): JSONArray = JSONArray(nmeaLog.take(1500))

    /** isValidAis 필터 전 모든 수신 원문 기록 (진단용) */
    @Synchronized fun addRaw(line: String) {
        rawLog.addFirst(line)
        while (rawLog.size > 500) rawLog.removeLast()
    }
    @Synchronized fun getRawLog(): JSONArray = JSONArray(rawLog.take(200))

    /* ─── AIS 비트 처리 유틸 ─────────────────────────── */
    private fun payloadToBits(payload: String, fill: Int): String {
        val sb = StringBuilder()
        for (c in payload) {
            var v = c.code - 48
            if (v > 39) v -= 8
            v = v.coerceIn(0, 63)
            sb.append(v.toString(2).padStart(6, '0'))
        }
        val s = sb.toString()
        return if (fill > 0 && s.length >= fill) s.dropLast(fill) else s
    }

    private fun bits(b: String, s: Int, e: Int, signed: Boolean = false): Int {
        if (s >= b.length) return 0
        val chunk = b.substring(s, minOf(e, b.length))
        if (chunk.isEmpty()) return 0
        var v = chunk.toInt(2)
        if (signed && chunk[0] == '1') v -= (1 shl chunk.length)
        return v
    }

    private fun bstr(b: String, s: Int, n: Int): String {
        val sb = StringBuilder()
        for (i in 0 until n) {
            val c = bits(b, s + i*6, s + i*6 + 6)
            if (c == 0) break
            if (c in AIS_CHARSET.indices) sb.append(AIS_CHARSET[c])
        }
        return sb.toString().trim('@', ' ')
    }

    private fun Double.round(n: Int): Double {
        val factor = Math.pow(10.0, n.toDouble())
        return Math.round(this * factor) / factor
    }

    /* ─── 설정 통신 (Step 2: AtoN 설정 송수신 로그) ───────────────── */
    private val configLog = ArrayDeque<JSONObject>()                 // 최근 송수신 (최신 앞)
    private val configLast = ConcurrentHashMap<String, String>()     // formatter → 최신 응답 문장

    /** dir="TX"(송신) 또는 "RX"(수신). RX는 formatter별 최신값도 저장 */
    fun logConfig(dir: String, line: String) {
        val o = JSONObject().apply {
            put("dir", dir); put("line", line)
            put("ts", System.currentTimeMillis() / 1000)
        }
        synchronized(configLog) { configLog.addFirst(o); while (configLog.size > 100) configLog.removeLast() }
        if (dir == "RX" && line.length >= 6) configLast[line.substring(3, 6)] = line
    }
    fun getConfigLog(): JSONArray = JSONArray(synchronized(configLog) { configLog.toList() })
    fun getConfigLast(fmt: String): String? = configLast[fmt]
    fun clearConfigLog() { synchronized(configLog) { configLog.clear() }; configLast.clear() }
}
