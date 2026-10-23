cmake_minimum_required(VERSION 3.22.1)
project("aiswatch")

set(CMAKE_C_STANDARD 11)

# ── 헤더 경로 ────────────────────────────────────────────
set(RTLSDR_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include")

# ── rtl-ais + JNI 소스 파일 ──────────────────────────────
set(RTLAIS_SOURCES
    rtl-ais/rtl_ais.c
    rtl-ais/convenience.c
    rtl-ais/aisdecoder/aisdecoder.c
    rtl-ais/aisdecoder/sounddecoder.c
    rtl-ais/aisdecoder/lib/filter.c
    rtl-ais/aisdecoder/lib/hmalloc.c
    rtl-ais/aisdecoder/lib/protodec.c
    rtl-ais/aisdecoder/lib/receiver.c
    rtlsdr_android.c
)

# ── JNI 공유 라이브러리 ───────────────────────────────────
add_library(aiswatch-jni SHARED ${RTLAIS_SOURCES})

# ── 헤더 경로 ────────────────────────────────────────────
target_include_directories(aiswatch-jni PRIVATE
    ${RTLSDR_INCLUDE_DIR}
    rtl-ais
    rtl-ais/aisdecoder
    rtl-ais/aisdecoder/lib
)

# ── librtlsdr.so (사전 빌드, jniLibs에서 자동 패키징) ────
# jniLibs/에 .so가 있으면 APK에 자동 포함됨
# find_library로 시스템 경로에서 찾기
find_library(RTLSDR_LIB rtlsdr)

# ── 컴파일 정의 ──────────────────────────────────────────
target_compile_definitions(aiswatch-jni PRIVATE
    ANDROID
    _REENTRANT
)

# ── 링크 ─────────────────────────────────────────────────
target_link_libraries(aiswatch-jni
    ${RTLSDR_LIB}
    log
    pthread
    m
)
