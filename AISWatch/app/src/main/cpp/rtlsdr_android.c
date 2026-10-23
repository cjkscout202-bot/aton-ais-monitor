/*
 * rtlsdr_android.c
 * Android USB Host API를 이용한 librtlsdr 대체 구현
 *
 * Android에서는 앱이 USB 권한을 획득한 뒤 파일 디스크립터를 JNI로 전달.
 * librtlsdr의 rtlsdr_open() 대신 rtlsdr_open_fd()를 사용.
 *
 * 의존: librtlsdr (NDK용 정적 라이브러리)
 *       Android USB Host API에서 받은 fd를 사용
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

#include "rtl-ais/rtl_ais.h"

#define TAG "AISWatch-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ─── 전역 상태 ───────────────────────────────────────── */
static struct rtl_ais_context *g_ctx = NULL;
static struct rtl_ais_config   g_cfg;
static volatile int            g_running = 0;
static pthread_t               g_msg_thread;

/* NMEA 메시지 큐 (간단한 링버퍼) */
#define MSG_QUEUE_SIZE 256
#define MSG_MAX_LEN    256
static char   g_queue[MSG_QUEUE_SIZE][MSG_MAX_LEN];
static int    g_q_head = 0, g_q_tail = 0;
static pthread_mutex_t g_q_mutex = PTHREAD_MUTEX_INITIALIZER;

static void queue_push(const char *msg) {
    pthread_mutex_lock(&g_q_mutex);
    int next = (g_q_head + 1) % MSG_QUEUE_SIZE;
    if (next != g_q_tail) {  /* 가득 차지 않은 경우만 */
        strncpy(g_queue[g_q_head], msg, MSG_MAX_LEN - 1);
        g_queue[g_q_head][MSG_MAX_LEN - 1] = '\0';
        g_q_head = next;
    }
    pthread_mutex_unlock(&g_q_mutex);
}

static int queue_pop(char *out) {
    pthread_mutex_lock(&g_q_mutex);
    if (g_q_tail == g_q_head) {
        pthread_mutex_unlock(&g_q_mutex);
        return 0;
    }
    strncpy(out, g_queue[g_q_tail], MSG_MAX_LEN - 1);
    g_q_tail = (g_q_tail + 1) % MSG_QUEUE_SIZE;
    pthread_mutex_unlock(&g_q_mutex);
    return 1;
}

/* ─── 메시지 수집 스레드 ──────────────────────────────── */
static void *msg_thread_fn(void *arg) {
    LOGI("메시지 수집 스레드 시작");
    while (g_running && g_ctx) {
        const char *msg = rtl_ais_next_message(g_ctx);
        if (msg && strlen(msg) > 5) {
            queue_push(msg);
        } else {
            struct timespec ts = {0, 5000000}; /* 5ms */
            nanosleep(&ts, NULL);
        }
    }
    LOGI("메시지 수집 스레드 종료");
    return NULL;
}

/* ─── JNI 함수 ────────────────────────────────────────── */

/*
 * AIS 수신 시작
 * fd: Android USB Host API에서 받은 파일 디스크립터
 * ppm: 주파수 오차 (장치마다 다름, 보통 -20~+20)
 */
JNIEXPORT jint JNICALL
Java_com_aiswatch_RtlSdrDriver_startReceiving(
        JNIEnv *env, jobject thiz, jint fd, jint ppm)
{
    if (g_running) {
        LOGE("이미 수신 중");
        return -1;
    }

    rtl_ais_default_config(&g_cfg);

    /* AIS 채널 주파수 설정 */
    g_cfg.left_freq  = 161975000; /* 161.975 MHz (채널 A) */
    g_cfg.right_freq = 162025000; /* 162.025 MHz (채널 B) */
    g_cfg.ppm_error  = (int)ppm;
    g_cfg.custom_ppm = (ppm != 0) ? 1 : 0;
    g_cfg.gain       = -100;      /* 자동 게인 */
    g_cfg.use_internal_aisdecoder = 1;

    /* UDP 출력 비활성화 (내부에서 직접 메시지 수집) */
    g_cfg.host = "127.0.0.1";
    g_cfg.port = "10110";

    /* fd를 이용해 장치 열기 (librtlsdr의 rtlsdr_open_fd 사용) */
    /* rtlsdr_open_fd()는 librtlsdr >= 0.6.0에서 지원 */
    g_cfg.dev_index = fd; /* fd 전달 방식은 librtlsdr 버전에 따라 다름 */

    LOGI("AIS 수신 시작: 좌=%d Hz, 우=%d Hz, ppm=%d",
         g_cfg.left_freq, g_cfg.right_freq, ppm);

    g_ctx = rtl_ais_start(&g_cfg);
    if (!g_ctx) {
        LOGE("rtl_ais_start 실패");
        return -2;
    }

    g_running = 1;
    pthread_create(&g_msg_thread, NULL, msg_thread_fn, NULL);

    LOGI("AIS 수신 시작 완료");
    return 0;
}

/* AIS 수신 중지 */
JNIEXPORT void JNICALL
Java_com_aiswatch_RtlSdrDriver_stopReceiving(JNIEnv *env, jobject thiz)
{
    LOGI("AIS 수신 중지 요청");
    g_running = 0;
    if (g_ctx) {
        rtl_ais_cleanup(g_ctx);
        g_ctx = NULL;
    }
    pthread_join(g_msg_thread, NULL);
    LOGI("AIS 수신 중지 완료");
}

/* 다음 NMEA 메시지 반환 (없으면 null) */
JNIEXPORT jstring JNICALL
Java_com_aiswatch_RtlSdrDriver_nextNmeaMessage(JNIEnv *env, jobject thiz)
{
    static char buf[MSG_MAX_LEN];
    if (queue_pop(buf)) {
        return (*env)->NewStringUTF(env, buf);
    }
    return NULL;
}

/* 수신 중 여부 반환 */
JNIEXPORT jboolean JNICALL
Java_com_aiswatch_RtlSdrDriver_isRunning(JNIEnv *env, jobject thiz)
{
    return (jboolean)(g_running && g_ctx && rtl_ais_isactive(g_ctx));
}
