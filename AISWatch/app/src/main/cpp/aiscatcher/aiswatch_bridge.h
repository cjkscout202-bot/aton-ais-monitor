/* AIS-catcher 복조 브리지 C API (C에서 호출) */
#ifndef AISWATCH_BRIDGE_H
#define AISWATCH_BRIDGE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* 파이프라인 초기화 (1.536MHz CU8). 성공 0, 실패 음수 */
int  aiswatch_aisc_init(void);
/* CU8 IQ 블록 처리 (nbytes = 샘플수*2) */
void aiswatch_aisc_process(const uint8_t* iq, int nbytes);
/* 디코딩된 NMEA 한 줄 꺼내기 (길이 반환, 없으면 0) */
int  aiswatch_aisc_next(char* buf, int buflen);
/* 정리 */
void aiswatch_aisc_cleanup(void);

#ifdef __cplusplus
}
#endif
#endif
