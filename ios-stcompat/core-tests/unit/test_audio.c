/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * WAV header and parser, local audio checks, VAD on synthetic and recorded audio, sound
 * intensity and the tick countdown.
 */
#include "ygt.h"

#include <math.h>

static void test_wav_header_bytes(void) {
    unsigned char h[44];
    static const unsigned char want[44] = {'R', 'I', 'F', 'F', 0x24, 0x7D, 0x00, 0x00, 'W', 'A', 'V', 'E', 'f',
                                           'm', 't', ' ', 16,   0,    0,    0,    1,   0,   1,   0,   0x80, 0x3E,
                                           0,   0,   0x00, 0x7D, 0,    0,    2,    0,   16,  0,   'd', 'a',  't',
                                           'a', 0x00, 0x7D, 0x00, 0x00};
    ygst_wav_header(h, 32000, 16000, 1, 16);
    CHECK(memcmp(h, want, 44) == 0);
    ygst_wav_header(h, 0xFFFFFFF0u, 16000, 1, 16);
    CHECK(h[4] == 0xFF && h[5] == 0xFF && h[6] == 0xFF && h[7] == 0xFF);
}

static void test_wav_parse_own_and_fixtures(void) {
    static const char *names[] = {"silent", "en_apple", "low_volume", "zh_short"};
    unsigned char buf[44 + 100];
    ygst_wav_info info;
    size_t i;
    ygst_wav_header(buf, 100, 16000, 1, 16);
    memset(buf + 44, 0, 100);
    CHECK_INT(ygst_wav_parse(buf, sizeof buf, &info), 0);
    CHECK_INT(info.data_offset, 44);
    CHECK_INT(info.data_len, 100);
    CHECK_INT(info.format, 1);
    for (i = 0; i < 4; i++) {
        char rel[128];
        size_t len = 0;
        char *wav;
        snprintf(rel, sizeof rel, "fixtures/audio/%s.wav", names[i]);
        wav = ygt_read_spec(rel, &len);
        CHECK(wav != NULL);
        if (!wav) continue;
        CHECK_INT(ygst_wav_parse((unsigned char *)wav, len, &info), 0);
        CHECK_INT(info.format, 1);
        CHECK_INT(info.channels, 1);
        CHECK_INT(info.sample_rate, 16000);
        CHECK_INT(info.bits, 16);
        CHECK(info.data_offset > 44); /* the fixtures carry a LIST chunk before data */
        CHECK_INT(info.data_offset + info.data_len, len);
        CHECK(ygst_pcm_duration_ms(info.data_len, 16000, 1, 16) >= 1000);
        CHECK_INT(ygst_audio_check_upload((unsigned char *)wav, len), 0);
        free(wav);
    }
}

static void test_wav_parse_rejects(void) {
    unsigned char buf[64];
    ygst_wav_info info;
    memset(buf, 0, sizeof buf);
    CHECK_INT(ygst_wav_parse(buf, 8, &info), -1);
    memcpy(buf, "RIFX", 4);
    CHECK_INT(ygst_wav_parse(buf, sizeof buf, &info), -1);
    /* data before fmt */
    memcpy(buf, "RIFF\0\0\0\0WAVEdata\4\0\0\0abcd", 24);
    CHECK_INT(ygst_wav_parse(buf, 24, &info), -1);
    /* truncated chunk */
    memcpy(buf, "RIFF\0\0\0\0WAVEJUNK\xff\0\0\0", 20);
    CHECK_INT(ygst_wav_parse(buf, 24, &info), -1);
    /* fmt chunk too small */
    memcpy(buf, "RIFF\0\0\0\0WAVEfmt \4\0\0\0abcd", 24);
    CHECK_INT(ygst_wav_parse(buf, 24, &info), -1);
    /* streaming header with data size 0 is clamped to the bytes present */
    ygst_wav_header(buf, 0, 16000, 1, 16);
    CHECK_INT(ygst_wav_parse(buf, 64, &info), 0);
    CHECK_INT(info.data_len, 20);
    CHECK(ygst_is_riff_wave(buf, 12));
    CHECK(!ygst_is_riff_wave(buf, 11));
}

static void test_audio_checks(void) {
    unsigned char *big;
    unsigned char wav[44 + 32000];
    CHECK_INT(ygst_audio_check_upload(NULL, 0), 60002);
    CHECK_INT(ygst_audio_check_upload(wav, 0), 60002);
    ygst_wav_header(wav, 0, 16000, 1, 16);
    CHECK_INT(ygst_audio_check_upload(wav, 44), 60002);
    memset(wav + 44, 0, 32000);
    ygst_wav_header(wav, 16000, 16000, 1, 16); /* 0.5 s */
    CHECK_INT(ygst_audio_check_upload(wav, 44 + 16000), 60005);
    ygst_wav_header(wav, 32000, 16000, 1, 16); /* 1.0 s */
    CHECK_INT(ygst_audio_check_upload(wav, sizeof wav), 0);
    CHECK_INT(ygst_audio_check_upload((const unsigned char *)"ID3\x03 not a wav", 15), 0);
    {
        /* WAV of 8 kHz 8 bit mono: exactly 300 s passes, 300 s plus one sample is 60009 */
        unsigned char *w = (unsigned char *)calloc(1, 44 + 2400001);
        CHECK(w != NULL);
        if (w) {
            ygst_wav_header(w, 2400000, 8000, 1, 8);
            CHECK_INT(ygst_audio_check_upload(w, 44 + 2400000), 0);
            ygst_wav_header(w, 2400001, 8000, 1, 8);
            CHECK_INT(ygst_audio_check_upload(w, 44 + 2400001), 60009);
            free(w);
        }
    }
    big = (unsigned char *)calloc(1, YGST_MAX_AUDIO_BYTES + 1);
    CHECK(big != NULL);
    if (big) {
        CHECK_INT(ygst_audio_check_upload(big, YGST_MAX_AUDIO_BYTES + 1), 60009);
        CHECK_INT(ygst_audio_check_upload(big, YGST_MAX_AUDIO_BYTES), 0);
        free(big);
    }
    CHECK_INT(ygst_audio_check_pcm(0, 16000, 1, 16), 60002);
    CHECK_INT(ygst_audio_check_pcm(31999, 16000, 1, 16), 60005);
    CHECK_INT(ygst_audio_check_pcm(32000, 16000, 1, 16), 0);
    CHECK_INT(ygst_audio_check_pcm(YGST_MAX_AUDIO_BYTES, 16000, 1, 16), 60009);
    /* 300 s is the longest accepted audio, one sample more is 60009 */
    CHECK_INT(ygst_audio_check_pcm(9600000, 16000, 1, 16), 0);
    CHECK_INT(ygst_audio_check_pcm(9600002, 16000, 1, 16), 60009);
    CHECK_INT(ygst_audio_check_pcm(2400000, 8000, 1, 8), 0);
    CHECK_INT(ygst_audio_check_pcm(2400001, 8000, 1, 8), 60009);
    CHECK_INT(ygst_audio_check_pcm(1000, 0, 1, 16), 0); /* unknown format: size checks only */
    CHECK_INT(ygst_pcm_duration_ms(32000, 16000, 1, 16), 1000);
    CHECK_INT(ygst_pcm_duration_ms(32000, 16000, 2, 16), 500);
    CHECK_INT(ygst_pcm_duration_ms(32000, 8000, 1, 8), 4000);
    CHECK_INT(ygst_pcm_duration_ms(10, 0, 1, 16), -1);
}

/* ---------------------------------------------------------------- VAD */

static void tone(int16_t *out, size_t n, double dbfs, size_t phase) {
    double amp = 32767.0 * pow(10.0, dbfs / 20.0) * sqrt(2.0);
    size_t i;
    for (i = 0; i < n; i++) out[i] = (int16_t)(amp * sin(2.0 * 3.14159265358979 * 440.0 * (double)(i + phase) / 16000.0));
}

static void test_vad_synthetic(void) {
    ygst_vad v;
    int16_t buf[16000];
    int i;
    int saw_end = 0;
    ygst_vad_init(&v, 16000, 60, 0);
    CHECK_INT(v.seek_ms, 600);
    memset(buf, 0, sizeof buf);
    CHECK_INT(ygst_vad_process(&v, buf, 16000), 0); /* 1 s silence */
    CHECK_INT(v.intensity, 0);
    tone(buf, 8000, -20.0, 0);
    CHECK_INT(ygst_vad_process(&v, buf, 8000), 1); /* 0.5 s speech */
    CHECK(v.intensity >= 60 && v.intensity <= 70);
    memset(buf, 0, sizeof buf);
    for (i = 0; i < 8; i++) { /* 8 x 100 ms silence */
        ygst_vad_process(&v, buf, 1600);
        if (v.ended_edge) {
            saw_end = i + 1;
            break;
        }
    }
    CHECK_INT(v.status, 2);
    CHECK_INT(saw_end, 6); /* ended after 600 ms of silence */
    ygst_vad_process(&v, buf, 1600);
    CHECK_INT(v.ended_edge, 0);
    CHECK_INT(v.status, 2);
    tone(buf, 1600, -20.0, 0);
    CHECK_INT(ygst_vad_process(&v, buf, 1600), 1); /* speech again after the end */
}

static void test_vad_ref_length_and_seek(void) {
    ygst_vad v;
    int16_t buf[16000];
    int ms = 0;
    /* seek 20 (200 ms), ref_length 100 (1 s): a 300 ms utterance stays 1 for one second */
    ygst_vad_init(&v, 16000, 20, 100);
    tone(buf, 4800, -15.0, 0);
    ygst_vad_process(&v, buf, 4800);
    CHECK_INT(v.status, 1);
    memset(buf, 0, sizeof buf);
    while (v.status == 1 && ms < 3000) {
        ygst_vad_process(&v, buf, 160);
        ms += 10;
    }
    CHECK_INT(v.status, 2);
    CHECK(ms >= 690 && ms <= 760); /* 1000 ms after the start, 300 ms of it was speech */
    ygst_vad_init(&v, 0, 0, 0);
    CHECK_INT(v.sample_rate, 16000);
    CHECK_INT(v.seek_ms, 600);
    ygst_vad_init(&v, 50, 1, 0);
    CHECK_INT(v.frame_samples, 1);
}

static void test_vad_bytes_equals_samples(void) {
    ygst_vad a, b;
    int16_t pcm[9600];
    unsigned char bytes[19200];
    size_t i, pos;
    tone(pcm, 4800, -18.0, 0);
    memset(pcm + 4800, 0, 4800 * sizeof(int16_t));
    for (i = 0; i < 9600; i++) {
        bytes[2 * i] = (unsigned char)((uint16_t)pcm[i] & 0xFF);
        bytes[2 * i + 1] = (unsigned char)((uint16_t)pcm[i] >> 8);
    }
    ygst_vad_init(&a, 16000, 10, 0);
    ygst_vad_init(&b, 16000, 10, 0);
    ygst_vad_process(&a, pcm, 9600);
    for (pos = 0; pos < sizeof bytes;) {
        size_t take = (pos / 7) % 2 ? 333 : 777; /* odd chunk sizes split samples */
        if (take > sizeof bytes - pos) take = sizeof bytes - pos;
        ygst_vad_process_bytes(&b, bytes + pos, take);
        pos += take;
    }
    CHECK_INT(a.status, b.status);
    CHECK_INT(a.status, 2);
    CHECK_INT(a.intensity, b.intensity);
    CHECK_INT(b.has_carry, 0);
}

static void run_fixture_vad(const char *rel, int *max_status, int *max_intensity) {
    size_t len = 0;
    char *wav = ygt_read_spec(rel, &len);
    ygst_wav_info info;
    ygst_vad v;
    size_t off;
    *max_status = -1;
    *max_intensity = -1;
    if (!wav) return;
    if (ygst_wav_parse((unsigned char *)wav, len, &info) == 0) {
        ygst_vad_init(&v, info.sample_rate, 60, 0);
        *max_status = 0;
        *max_intensity = 0;
        for (off = 0; off < info.data_len; off += 3200) {
            size_t take = info.data_len - off < 3200 ? info.data_len - off : 3200;
            int st = ygst_vad_process_bytes(&v, (unsigned char *)wav + info.data_offset + off, take);
            if (st > *max_status) *max_status = st;
            if (v.intensity > *max_intensity) *max_intensity = v.intensity;
        }
    }
    free(wav);
}

static void test_vad_fixtures(void) {
    int st, inten;
    run_fixture_vad("fixtures/audio/silent.wav", &st, &inten);
    CHECK_INT(st, 0);
    CHECK(inten <= 20);
    run_fixture_vad("fixtures/audio/zh_short.wav", &st, &inten);
    CHECK(st >= 1);
    CHECK(inten > 40);
    run_fixture_vad("fixtures/audio/en_apple.wav", &st, &inten);
    CHECK(st >= 1);
    run_fixture_vad("fixtures/audio/low_volume.wav", &st, &inten);
    CHECK(st >= 0);
    printf("     low_volume.wav: max VAD status %d, max intensity %d\n", st, inten);
}

static void test_intensity_scale(void) {
    int16_t buf[1600];
    size_t i;
    memset(buf, 0, sizeof buf);
    CHECK_INT(ygst_sound_intensity(buf, 1600), 0);
    CHECK_INT(ygst_sound_intensity(buf, 0), 0);
    for (i = 0; i < 1600; i++) buf[i] = (int16_t)(i % 2 ? 32767 : -32767);
    CHECK_INT(ygst_sound_intensity(buf, 1600), 100);
    CHECK_INT(ygst_sound_intensity_db(-30.0), 50);
    CHECK_INT(ygst_sound_intensity_db(-90.0), 0);
    CHECK_INT(ygst_sound_intensity_db(6.0), 100);
}

static void test_tick(void) {
    double left = -1, pct = -1;
    ygst_tick_compute(10000, 2500, &left, &pct);
    CHECK(left == 7500 && pct == 75);
    ygst_tick_compute(10000, 12000, &left, &pct);
    CHECK(left == 0 && pct == 0);
    ygst_tick_compute(0, 100, &left, &pct);
    CHECK(left == 0 && pct == 0);
    ygst_tick_compute(5000, -10, &left, &pct);
    CHECK(left == 5000 && pct == 100);
}

void suite_audio(void) {
    RUN("audio", test_wav_header_bytes);
    RUN("audio", test_wav_parse_own_and_fixtures);
    RUN("audio", test_wav_parse_rejects);
    RUN("audio", test_audio_checks);
    RUN("audio", test_vad_synthetic);
    RUN("audio", test_vad_ref_length_and_seek);
    RUN("audio", test_vad_bytes_equals_samples);
    RUN("audio", test_vad_fixtures);
    RUN("audio", test_intensity_scale);
    RUN("audio", test_tick);
}
