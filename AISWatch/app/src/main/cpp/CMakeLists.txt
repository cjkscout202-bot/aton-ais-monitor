cmake_minimum_required(VERSION 3.22.1)
project("aiswatch")
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(INC "${CMAKE_SOURCE_DIR}/include")

# ── librtlsdr (소스 빌드, fd 오픈 패치 포함) ──
set(LIBRTLSDR_SOURCES
    librtlsdr/librtlsdr.c
    librtlsdr/tuner_e4k.c
    librtlsdr/tuner_fc0012.c
    librtlsdr/tuner_fc0013.c
    librtlsdr/tuner_fc2580.c
    librtlsdr/tuner_r82xx.c
)

# ── rtl-ais + JNI ──
set(RTLAIS_SOURCES
    rtl-ais/rtl_ais.c
    rtl-ais/convenience.c
    rtl-ais/aisdecoder/aisdecoder.c
    rtl-ais/aisdecoder/sounddecoder.c
    rtl-ais/aisdecoder/lib/filter.c
    rtl-ais/aisdecoder/lib/hmalloc.c
    rtl-ais/aisdecoder/lib/protodec.c
    rtl-ais/aisdecoder/lib/receiver.c
    rtl-ais/tcp_listener/tcp_listener.c
    rtlsdr_android.c
)

# ── AIS-catcher 복조 코어 (C++17) ──
set(AISC aiscatcher/Source)
set(AISCATCHER_SOURCES
    ${AISC}/aiswatch_bridge.cpp
    ${AISC}/DSP/Demod.cpp
    ${AISC}/DSP/DSP.cpp
    ${AISC}/DSP/Model.cpp
    ${AISC}/DSP/Decoder/V2/V2Engine.cpp
    ${AISC}/Marine/AIS.cpp
    ${AISC}/Marine/Message.cpp
    ${AISC}/Marine/NMEA.cpp
    ${AISC}/Library/Logger.cpp
    ${AISC}/Utilities/Parse.cpp
    ${AISC}/Utilities/Helper.cpp
    ${AISC}/Utilities/Convert.cpp
    ${AISC}/Utilities/StreamHelpers.cpp
    ${AISC}/JSON/Parser.cpp
    ${AISC}/JSON/JSON.cpp
    ${AISC}/JSON/Keys.cpp
    ${AISC}/Aviation/Beast.cpp
    ${AISC}/Aviation/Basestation.cpp
    ${AISC}/Aviation/ADSB.cpp
)
set_source_files_properties(${AISCATCHER_SOURCES} PROPERTIES COMPILE_FLAGS "-DNDEBUG")

add_library(aiswatch-jni SHARED
    ${LIBRTLSDR_SOURCES} ${RTLAIS_SOURCES} ${AISCATCHER_SOURCES})

target_include_directories(aiswatch-jni PRIVATE
    ${INC}
    librtlsdr
    rtl-ais
    rtl-ais/aisdecoder
    rtl-ais/aisdecoder/lib
    rtl-ais/tcp_listener
    ${AISC}
    ${AISC}/DSP
    ${AISC}/DSP/Decoder/V2
    ${AISC}/Library
    ${AISC}/Marine
    ${AISC}/Utilities
    ${AISC}/JSON
    ${AISC}/Aviation
    ${AISC}/Device
    ${AISC}/Application
    ${AISC}/IO
    ${AISC}/Control
    ${AISC}/Tracking
    ${AISC}/Web
    ${AISC}/DBMS
)

target_compile_definitions(aiswatch-jni PRIVATE ANDROID _REENTRANT)

add_library(usbrtl SHARED IMPORTED)
set_target_properties(usbrtl PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librtlsdr.so")

find_library(log-lib log)
target_link_libraries(aiswatch-jni usbrtl ${log-lib})
target_link_options(aiswatch-jni PRIVATE "-Wl,-Bsymbolic")
