/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * WAV header writer and reader, local audio checks, a frame energy VAD with an adaptive noise
 * floor, the 0..100 sound intensity scale and the countdown values of the tick callback.
 */
#include "ygst_core.h"

#include <math.h>
#include <string.h>

static void put_le32(unsigned char *p, uint32_t v) {
    p[0] = (unsigned char)v;
    p[1] = (unsigned char)(v >> 8);
    p[2] = (unsigned char)(v >> 16);
    p[3] = (unsigned char)(v >> 24);
}

static void put_le16(unsigned char *p, uint16_t v) {
    p[0] = (unsigned char)v;
    p[1] = (unsigned char)(v >> 8);
}

static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint16_t le16(const unsigned char *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

void ygst_wav_header(unsigned char out[44], uint32_t data_len, int sample_rate, int channels, int bits) {
    uint32_t block = (uint32_t)(channels * (bits / 8));
    memcpy(out, "RIFF", 4);
    put_le32(out + 4, data_len > 0xFFFFFFFFu - 36u ? 0xFFFFFFFFu : data_len + 36u);
    memcpy(out + 8, "WAVE", 4);
    memcpy(out + 12, "fmt ", 4);
    put_le32(out + 16, 16);
    put_le16(out + 20, 1);
    put_le16(out + 22, (uint16_t)channels);
    put_le32(out + 24, (uint32_t)sample_rate);
    put_le32(out + 28, (uint32_t)sample_rate * block);
    put_le16(out + 32, (uint16_t)block);
    put_le16(out + 34, (uint16_t)bits);
    memcpy(out + 36, "data", 4);
    put_le32(out + 40, data_len);
}

int ygst_is_riff_wave(const unsigned char *p, size_t n) {
    return n >= 12 && memcmp(p, "RIFF", 4) == 0 && memcmp(p + 8, "WAVE", 4) == 0;
}

int ygst_wav_parse(const unsigned char *p, size_t n, ygst_wav_info *info) {
    size_t pos = 12;
    int have_fmt = 0;
    memset(info, 0, sizeof *info);
    if (!ygst_is_riff_wave(p, n)) return -1;
    while (pos + 8 <= n) {
        uint32_t size = le32(p + pos + 4);
        const unsigned char *id = p + pos;
        size_t body = pos + 8;
        if (memcmp(id, "fmt ", 4) == 0) {
            if (size < 16 || body + 16 > n) return -1;
            info->format = le16(p + body);
            info->channels = le16(p + body + 2);
            info->sample_rate = (int)le32(p + body + 4);
            info->bits = le16(p + body + 14);
            have_fmt = 1;
        } else if (memcmp(id, "data", 4) == 0) {
            if (!have_fmt) return -1;
            info->data_offset = body;
            /* Streaming writers leave 0 or 0xFFFFFFFF; clamp to what the file holds. */
            info->data_len = (size == 0 || size > n - body) ? n - body : size;
            return 0;
        }
        if (size > n - body) return -1;
        pos = body + size + (size & 1u);
    }
    return -1;
}

long ygst_pcm_duration_ms(size_t bytes, int sample_rate, int channels, int bits) {
    double per_second;
    if (sample_rate <= 0 || channels <= 0 || bits <= 0) return -1;
    per_second = (double)sample_rate * channels * (bits / 8.0);
    if (per_second <= 0) return -1;
    return (long)((double)bytes * 1000.0 / per_second);
}

/* Exact duration test without rounding: bytes * 1000 compared with ms * bytes per second. */
static int pcm_longer_than(size_t bytes, int sample_rate, int channels, int bits, long ms) {
    double per_second = (double)sample_rate * channels * (bits / 8.0);
    if (per_second <= 0) return 0;
    return (double)bytes * 1000.0 > (double)ms * per_second;
}

int ygst_audio_check_pcm(size_t pcm_len, int sample_rate, int channels, int bits) {
    long ms;
    if (pcm_len == 0) return YGST_ERRID_AUDIO_EMPTY;
    if (pcm_len > YGST_MAX_AUDIO_BYTES - 44u) return YGST_ERRID_AUDIO_TOO_LARGE;
    ms = ygst_pcm_duration_ms(pcm_len, sample_rate, channels, bits);
    if (ms >= 0 && ms < YGST_MIN_AUDIO_MS) return YGST_ERRID_AUDIO_TOO_SHORT;
    if (pcm_longer_than(pcm_len, sample_rate, channels, bits, YGST_MAX_AUDIO_MS)) return YGST_ERRID_AUDIO_TOO_LARGE;
    return 0;
}

int ygst_audio_check_upload(const unsigned char *data, size_t len) {
    ygst_wav_info info;
    if (!data || len == 0) return YGST_ERRID_AUDIO_EMPTY;
    if (len > YGST_MAX_AUDIO_BYTES) return YGST_ERRID_AUDIO_TOO_LARGE;
    if (ygst_wav_parse(data, len, &info) == 0) {
        long ms;
        if (info.data_len == 0) return YGST_ERRID_AUDIO_EMPTY;
        if (info.format == 1 || info.format == 3 || info.format == 0xFFFE) {
            ms = ygst_pcm_duration_ms(info.data_len, info.sample_rate, info.channels, info.bits);
            if (ms >= 0 && ms < YGST_MIN_AUDIO_MS) return YGST_ERRID_AUDIO_TOO_SHORT;
            if (pcm_longer_than(info.data_len, info.sample_rate, info.channels, info.bits, YGST_MAX_AUDIO_MS))
                return YGST_ERRID_AUDIO_TOO_LARGE;
        }
    }
    return 0;
}

/* ------------------------------------------------------------------ VAD */

#define VAD_ABS_DB (-42.0)      /* a frame quieter than this is never speech */
#define VAD_OVER_NOISE_DB 10.0 /* speech must also exceed the noise floor by this much */
#define VAD_START_FRAMES 3     /* 30 ms of speech starts an utterance */
#define VAD_NOISE_MIN_DB (-90.0)
#define VAD_NOISE_MAX_DB (-35.0)

void ygst_vad_init(ygst_vad *v, int sample_rate, double seek_10ms, double ref_length_10ms) {
    memset(v, 0, sizeof *v);
    v->sample_rate = sample_rate > 0 ? sample_rate : 16000;
    v->frame_samples = v->sample_rate / 100;
    if (v->frame_samples <= 0) v->frame_samples = 1;
    v->seek_ms = (int)((seek_10ms > 0 ? seek_10ms : 60) * 10.0);
    v->ref_length_ms = ref_length_10ms > 0 ? (int)(ref_length_10ms * 10.0) : 0;
    v->noise_db = -60.0;
}

int ygst_sound_intensity_db(double db) {
    double x = (db + 60.0) * 100.0 / 60.0;
    if (x < 0) x = 0;
    if (x > 100) x = 100;
    return (int)(x + 0.5);
}

static double rms_db(double sumsq, int n) {
    double rms;
    if (n <= 0) return -120.0;
    rms = sqrt(sumsq / n);
    if (rms < 1.0) return -120.0;
    return 20.0 * log10(rms / 32768.0);
}

int ygst_sound_intensity(const int16_t *pcm, size_t samples) {
    double sumsq = 0;
    size_t i;
    for (i = 0; i < samples; i++) sumsq += (double)pcm[i] * pcm[i];
    return ygst_sound_intensity_db(rms_db(sumsq, (int)samples));
}

static void vad_frame(ygst_vad *v, double db) {
    double threshold = v->noise_db + VAD_OVER_NOISE_DB;
    int speech;
    if (threshold < VAD_ABS_DB) threshold = VAD_ABS_DB;
    speech = db > threshold;
    v->intensity = ygst_sound_intensity_db(db);
    if (!speech) {
        v->noise_db = v->noise_db * 0.95 + db * 0.05;
        if (v->noise_db < VAD_NOISE_MIN_DB) v->noise_db = VAD_NOISE_MIN_DB;
        if (v->noise_db > VAD_NOISE_MAX_DB) v->noise_db = VAD_NOISE_MAX_DB;
    }
    v->speech_run = speech ? v->speech_run + 1 : 0;
    if (v->status != 1) {
        if (v->speech_run >= VAD_START_FRAMES) {
            v->status = 1;
            v->speaking_ms = 0;
            v->silence_ms = 0;
        }
        return;
    }
    v->speaking_ms += 10;
    v->silence_ms = speech ? 0 : v->silence_ms + 10;
    if (v->silence_ms >= v->seek_ms && v->speaking_ms >= v->ref_length_ms) {
        v->status = 2;
        v->ended_edge = 1;
    }
}

int ygst_vad_process(ygst_vad *v, const int16_t *pcm, size_t samples) {
    size_t i;
    v->ended_edge = 0;
    for (i = 0; i < samples; i++) {
        v->acc_sumsq += (double)pcm[i] * pcm[i];
        v->acc_n++;
        if (v->acc_n >= v->frame_samples) {
            vad_frame(v, rms_db(v->acc_sumsq, v->acc_n));
            v->acc_sumsq = 0;
            v->acc_n = 0;
        }
    }
    return v->status;
}

int ygst_vad_process_bytes(ygst_vad *v, const unsigned char *bytes, size_t n) {
    int16_t chunk[256];
    size_t used = 0;
    int ended = 0;
    size_t i = 0;
    if (v->has_carry && n > 0) {
        chunk[used++] = (int16_t)(uint16_t)(v->carry | (bytes[0] << 8));
        v->has_carry = 0;
        i = 1;
    }
    for (; i + 1 < n; i += 2) {
        chunk[used++] = (int16_t)(uint16_t)(bytes[i] | (bytes[i + 1] << 8));
        if (used == sizeof chunk / sizeof chunk[0]) {
            ygst_vad_process(v, chunk, used);
            ended |= v->ended_edge;
            used = 0;
        }
    }
    if (i < n) {
        v->carry = bytes[i];
        v->has_carry = 1;
    }
    if (used) {
        ygst_vad_process(v, chunk, used);
        ended |= v->ended_edge;
    }
    v->ended_edge = ended;
    return v->status;
}

void ygst_tick_compute(double duration_ms, double elapsed_ms, double *millis_left, double *percent_left) {
    double left;
    if (duration_ms <= 0) {
        *millis_left = 0;
        *percent_left = 0;
        return;
    }
    left = duration_ms - (elapsed_ms > 0 ? elapsed_ms : 0);
    if (left < 0) left = 0;
    *millis_left = left;
    *percent_left = left * 100.0 / duration_ms;
}
