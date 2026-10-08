/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * multipart/form-data assembly (RFC 7578) for POST /{coreType}. Text fields carry no filename,
 * so the platform treats them as form parameters and they are exactly the signed set; the audio
 * is a file part and is not signed.
 */
#include "ygst_core.h"

#include <stdio.h>
#include <string.h>

int ygst_multipart_boundary(const unsigned char rnd[16], char out[64]) {
    char hex[33];
    ygst_hex_lower(rnd, 16, hex);
    snprintf(out, 64, "----YuguSTCompat%s", hex);
    return 0;
}

int ygst_multipart_content_type(const char *boundary, char *out, size_t cap) {
    int n;
    if (!boundary || !out) return -1;
    n = snprintf(out, cap, "multipart/form-data; boundary=%s", boundary);
    return (n < 0 || (size_t)n >= cap) ? -1 : 0;
}

static int contains(const unsigned char *hay, size_t n, const char *needle) {
    size_t m = strlen(needle);
    size_t i;
    if (m == 0 || n < m) return 0;
    for (i = 0; i + m <= n; i++) {
        if (hay[i] == (unsigned char)needle[0] && memcmp(hay + i, needle, m) == 0) return 1;
    }
    return 0;
}

int ygst_multipart_build(const ygst_fields *fields, const char *boundary, const char *file_field,
                         const char *filename, const char *file_content_type, const unsigned char *file,
                         size_t file_len, ygst_buf *out) {
    size_t i;
    if (!boundary || !boundary[0] || strlen(boundary) > 70 || !file_field || !filename) return -1;
    if (!ygst_field_name_ok(file_field) || !ygst_field_name_ok(filename)) return -1;
    for (i = 0; i < fields->count; i++) {
        const char *v = fields->items[i].value ? fields->items[i].value : "";
        if (!ygst_field_name_ok(fields->items[i].name)) return -1;
        if (contains((const unsigned char *)v, strlen(v), boundary)) return -2;
    }
    if (file && contains(file, file_len, boundary)) return -2;
    for (i = 0; i < fields->count; i++) {
        ygst_buf_append_str(out, "--");
        ygst_buf_append_str(out, boundary);
        ygst_buf_append_str(out, "\r\nContent-Disposition: form-data; name=\"");
        ygst_buf_append_str(out, fields->items[i].name);
        ygst_buf_append_str(out, "\"\r\n\r\n");
        ygst_buf_append_str(out, fields->items[i].value ? fields->items[i].value : "");
        ygst_buf_append_str(out, "\r\n");
    }
    ygst_buf_append_str(out, "--");
    ygst_buf_append_str(out, boundary);
    ygst_buf_append_str(out, "\r\nContent-Disposition: form-data; name=\"");
    ygst_buf_append_str(out, file_field);
    ygst_buf_append_str(out, "\"; filename=\"");
    ygst_buf_append_str(out, filename);
    ygst_buf_append_str(out, "\"\r\nContent-Type: ");
    ygst_buf_append_str(out, file_content_type ? file_content_type : "application/octet-stream");
    ygst_buf_append_str(out, "\r\n\r\n");
    if (file_len) ygst_buf_append(out, file, file_len);
    ygst_buf_append_str(out, "\r\n--");
    ygst_buf_append_str(out, boundary);
    ygst_buf_append_str(out, "--\r\n");
    return out->oom ? -1 : 0;
}

/* Upload extension for an audioType or a file extension. Unknown types upload as-is. */
const char *ygst_audio_ext(const char *audio_type) {
    static const char *const KNOWN[] = {"wav", "mp3", "ogg", "amr", "flv", "opus", "aac",
                                        "spx", "speex", "silk", "slk", "m4a", "pcm"};
    char t[16];
    size_t i;
    if (ygst_is_blank(audio_type)) return "wav";
    ygst_trim_copy(audio_type, t, sizeof t);
    if (t[0] == '.') memmove(t, t + 1, strlen(t));
    for (i = 0; i < sizeof KNOWN / sizeof KNOWN[0]; i++) {
        if (ygst_strcaseeq(t, KNOWN[i])) return strcmp(KNOWN[i], "pcm") == 0 ? "wav" : KNOWN[i];
    }
    if (ygst_strcaseeq(t, "wave")) return "wav";
    if (ygst_strcaseeq(t, "mpeg")) return "mp3";
    return "bin";
}

const char *ygst_audio_content_type(const char *ext) {
    if (!ext) return "application/octet-stream";
    if (ygst_strcaseeq(ext, "wav")) return "audio/wav";
    if (ygst_strcaseeq(ext, "mp3")) return "audio/mpeg";
    if (ygst_strcaseeq(ext, "ogg") || ygst_strcaseeq(ext, "spx") || ygst_strcaseeq(ext, "speex")) return "audio/ogg";
    if (ygst_strcaseeq(ext, "opus")) return "audio/opus";
    if (ygst_strcaseeq(ext, "amr")) return "audio/amr";
    if (ygst_strcaseeq(ext, "aac")) return "audio/aac";
    if (ygst_strcaseeq(ext, "m4a")) return "audio/mp4";
    if (ygst_strcaseeq(ext, "flv")) return "video/x-flv";
    return "application/octet-stream";
}

int ygst_audio_type_is_pcm(const char *audio_type) {
    char t[16];
    if (ygst_is_blank(audio_type)) return 1;
    ygst_trim_copy(audio_type, t, sizeof t);
    return ygst_strcaseeq(t, "wav") || ygst_strcaseeq(t, "pcm") || ygst_strcaseeq(t, "wave") ||
           ygst_strcaseeq(t, ".wav") || ygst_strcaseeq(t, ".pcm");
}
