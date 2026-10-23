// AIS-catcher 복조기 브리지 — 우리 IQ(CU8) → NMEA
// rtl-ais 대신 AIS-catcher의 ModelDefault(PhaseSearch coherent) 사용
#include <cstdint>
#include <string>
#include <deque>
#include <mutex>
#include <vector>
#include <cstdio>
#include <cstring>

#include "Common.h"
#include "Stream.h"
#include "Model.h"
#include "AIS.h"
#include "Device/Device.h"

// 종료 요청 스텁 (앱에서는 안 씀)
void StopRequest() {}

using namespace AIS;

// ── IQ를 파이프라인에 밀어넣는 스텁 장치 ──
class PushDevice : public Device::Device {
public:
    PushDevice() {
        setFormat(Format::CU8);
        setSampleRate(288000);
    }
    void Play() override { Device::Device::Play(); }
    // CU8 IQ 바이트 블록을 파이프라인으로 전송
    void pushIQ(const void* data, int nbytes) {
        RAW r;
        r.format = Format::CU8;
        r.data = (void*)data;
        r.size = nbytes;
        TAG tag;
        out.Send(&r, 1, tag);
    }
};

// ── 디코딩된 Message → NMEA 문자열 큐 ──
class NMEASink : public StreamIn<Message> {
public:
    std::deque<std::string> q;
    std::mutex m;
    void Receive(const Message* data, int len, TAG& tag) override {
        std::lock_guard<std::mutex> lk(m);
        // AIS-catcher TAG의 신호세기(level, dB)·주파수오차(ppm)를 NMEA 뒤에 태그로 부착
        // → AISDataStore가 ,PWR:x,PPM:y 를 파싱해 실시간 RF 모니터/신호세기 표시
        char tagbuf[48];
        snprintf(tagbuf, sizeof(tagbuf), ",PWR:%.1f,PPM:%.1f", tag.level, tag.ppm);
        for (int i = 0; i < len; i++) {
            for (const auto& s : data[i].sentences()) {
                q.push_back(s + tagbuf);
            }
        }
    }
    bool pop(std::string& out_s) {
        std::lock_guard<std::mutex> lk(m);
        if (q.empty()) return false;
        out_s = q.front();
        q.pop_front();
        return true;
    }
};

static PushDevice*   g_dev  = nullptr;
static ModelDefault* g_model = nullptr;
static NMEASink*     g_sink = nullptr;

extern "C" {

// 초기화: 1.536MHz CU8 파이프라인 구성
int aiswatch_aisc_init() {
    try {
        if (g_model) return 0; // 이미 초기화됨
        g_dev  = new PushDevice();
        g_model = new ModelDefault();
        g_sink = new NMEASink();

        g_dev->setSampleRate(288000);
        g_dev->setFormat(Format::CU8);

        // 검증된 AIS-catcher 배선: convert→DS2×N→ROT→CGF→FC→ScatterPLL→PhaseSearch→Decoder
        // 288000 = 28.8MHz÷100 (RTL 하드웨어가 정확히 생성 가능한 레이트, AIS-catcher 앱 기본값)
        g_model->buildModel('A', 'B', 288000, false, g_dev);
        g_model->Output() >> *g_sink;

        g_dev->Play();
        return 0;
    } catch (const std::exception& e) {
        return -1;
    } catch (...) {
        return -2;
    }
}

// IQ 블록 처리 (CU8 바이트, nbytes = 샘플수*2)
void aiswatch_aisc_process(const uint8_t* iq, int nbytes) {
    if (g_dev) g_dev->pushIQ(iq, nbytes);
}

// 디코딩된 NMEA 한 줄 꺼내기 (없으면 0)
int aiswatch_aisc_next(char* buf, int buflen) {
    if (!g_sink) return 0;
    std::string s;
    if (!g_sink->pop(s)) return 0;
    int n = (int)s.size(); if (n >= buflen) n = buflen - 1;
    memcpy(buf, s.data(), n); buf[n] = 0;
    return n;
}

void aiswatch_aisc_cleanup() {
    delete g_sink;  g_sink = nullptr;
    delete g_model; g_model = nullptr;
    delete g_dev;   g_dev = nullptr;
}

} // extern "C"
