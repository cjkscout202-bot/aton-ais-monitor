/*
 *    sounddecoder.cpp
 *
 *    This file is part of AISDecoder.
 *
 *    Copyright (C) 2013
 *      Astra Paging Ltd / AISHub (info@aishub.net)
 *
 *    AISDecoder is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    AISDecoder uses parts of GNUAIS project (http://gnuais.sourceforge.net/)
 *
 */

#include <string.h>
#include <stdio.h>
//#include "config.h"
#ifdef WIN32
	#include <fcntl.h>
#endif

#include "lib/receiver.h"
#include "lib/hmalloc.h"

#define MAX_FILENAME_SIZE 512
#define ERROR_MESSAGE_LENGTH 1024
#include "sounddecoder.h"
#ifdef __ANDROID__
extern void aiswatch_diag(const char *fmt, ...);
#else
#define aiswatch_diag(...)
#endif
#include <time.h>


char errorSoundDecoder[ERROR_MESSAGE_LENGTH];

static struct receiver *rx_a=NULL;
static struct receiver *rx_b=NULL;

static short *buffer=NULL;
static int buffer_l=0;
static int buffer_read=0;
static int channels=0;
static Sound_Channels sound_channels;
static FILE *fp=NULL;
static void readBuffers();
static time_t tprev=0;
static int time_print_stats=0;

int initSoundDecoder(int buf_len,int _time_print_stats, int add_sample_num) 
{
	sound_channels=SOUND_CHANNELS_STEREO;
	channels = sound_channels == SOUND_CHANNELS_MONO ? 1 : 2;
	time_print_stats=_time_print_stats;
	tprev=time(NULL); // for decoder statistics
    buffer = (short *) hmalloc(channels*sizeof(short)*buf_len);
    rx_a = init_receiver('A', 2, 0, add_sample_num);
    rx_b = init_receiver('B', 2, 1, add_sample_num);
    return 1;
}

void run_mem_decoder(short * buf, int len,int max_buf_len)
{	
	int offset=0;
	int bytes_in_len=len*channels;
	char * p=(char *) buf;
	while(bytes_in_len > max_buf_len )
	{
		memcpy(buffer,p+offset,max_buf_len);
		buffer_read=max_buf_len/(channels*sizeof(short));
		bytes_in_len-=max_buf_len;
		offset+=max_buf_len;
		readBuffers();
	}
	memcpy(buffer,p+offset,bytes_in_len);
	buffer_read=bytes_in_len/(channels*sizeof(short));
	readBuffers();

	/* Android 진단: 3초마다 복조 통계 (CRC오류>0 이면 신호는 잡히나 품질문제) */
	{ static time_t _ld=0; time_t _n=time(NULL);
	  if(_n - _ld >= 3){ _ld=_n;
	    struct demod_state_t *da = rx_a->decoder; struct demod_state_t *db = rx_b->decoder;
	    aiswatch_diag("복조통계 A:정상%d CRC오류%d 크기오류%d B:정상%d CRC오류%d 크기오류%d",
	        da->receivedframes, da->lostframes, da->lostframes2,
	        db->receivedframes, db->lostframes, db->lostframes2);
	    { int _mn=32767,_mx=-32768; long _sa=0; int _j,_lim=len*channels; if(_lim>4000)_lim=4000;
	      for(_j=0;_j<_lim;_j++){ int _v=buf[_j]; if(_v<_mn)_mn=_v; if(_v>_mx)_mx=_v; _sa+=(_v<0?-_v:_v); }
	      aiswatch_diag("복조출력 min=%d max=%d 평균절대=%ld (밋밋하면 복조약함)", _mn, _mx, _lim>0?_sa/_lim:0); } } }

	if(time_print_stats && (time(NULL)-tprev >= time_print_stats))
	{
		struct demod_state_t *d = rx_a->decoder;
		tprev=time(NULL);
		fprintf(stderr,
				"A: Received correctly: %d packets, wrong CRC: %d packets, wrong size: %d packets\n",
				d->receivedframes, d->lostframes,
				d->lostframes2);
		d = rx_b->decoder;
			fprintf(stderr,
				"B: Received correctly: %d packets, wrong CRC: %d packets, wrong size: %d packets\n",
				d->receivedframes, d->lostframes,
				d->lostframes2);
	}
}
void runSoundDecoder(int *stop) {
    while (!*stop) {
        buffer_read = fread(buffer, channels * sizeof(short), buffer_l, fp);
        readBuffers();
    }
}

static void readBuffers() {
    if (buffer_read <= 0) return;
    if (rx_a != NULL && sound_channels != SOUND_CHANNELS_RIGHT)
        receiver_run(rx_a, buffer, buffer_read);

    if (rx_b != NULL &&
        (sound_channels == SOUND_CHANNELS_STEREO || sound_channels == SOUND_CHANNELS_RIGHT)
    ) receiver_run(rx_b, buffer, buffer_read);
}

void freeSoundDecoder(void) {
    if (fp != NULL) {
        fclose(fp);
        fp=NULL;
    }

    if (rx_a != NULL) {
        free_receiver(rx_a);
        rx_a=NULL;
    }

    if (rx_b != NULL) {
        free_receiver(rx_b);
        rx_b=NULL;
    }
    if (buffer != NULL) {
        hfree(buffer);
        buffer = NULL;
    }
}
