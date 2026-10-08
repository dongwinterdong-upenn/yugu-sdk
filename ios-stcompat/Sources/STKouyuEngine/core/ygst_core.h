/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portable C99 core of the STKouyuEngine compatibility layer.
 *
 * Everything that does not need an Apple framework lives here so that it can be compiled and
 * unit tested on Linux: signing, form field mapping, multipart assembly, WAV handling, VAD,
 * retry decisions, error mapping and result envelope assembly. The Objective-C layer only moves
 * bytes between this core and NSURLSession / AVFoundation.
 *
 * Internal header. Not part of the public module.
 */
#ifndef YGST_CORE_H
#define YGST_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YGST_SDK_VERSION "2.0.0"
#define YGST_USER_AGENT "yugu-ios-stcompat-sdk/" YGST_SDK_VERSION
#define YGST_DEFAULT_BASE_URL "https://open.shengzhiai.com"

/* Shengtong compat errIds owned by this layer (spec/errors.json compatErrIds). */
#define YGST_ERRID_RETRYABLE 20009
#define YGST_ERRID_AUDIO_FILE_MISSING 60001
#define YGST_ERRID_AUDIO_EMPTY 60002
#define YGST_ERRID_CORETYPE_UNSUPPORTED 60003
#define YGST_ERRID_MIC_UNAVAILABLE 60004
#define YGST_ERRID_AUDIO_TOO_SHORT 60005
#define YGST_ERRID_REFTEXT_EMPTY 60006
#define YGST_ERRID_NOT_INITIALIZED 60007
#define YGST_ERRID_ENGINE_BUSY 60008
#define YGST_ERRID_AUDIO_TOO_LARGE 60009

/* Local SDK codes (spec/errors.json local). */
#define YGST_LOCAL_NETWORK 90001
#define YGST_LOCAL_TIMEOUT 90002
#define YGST_LOCAL_CANCELLED 90003
#define YGST_LOCAL_PROTOCOL 90005
#define YGST_LOCAL_RESULT_TIMEOUT 90007
#define YGST_LOCAL_TLS 90011

/* Audio limits: errId 60005 below 1 s, 60009 above 50 MB or 300 s (spec/errors.json). */
#define YGST_MAX_AUDIO_BYTES (50u * 1024u * 1024u)
#define YGST_MIN_AUDIO_MS 1000
#define YGST_MAX_AUDIO_MS 300000

/* ------------------------------------------------------------------ buffer and strings */

typedef struct {
    unsigned char *data;
    size_t len;
    size_t cap;
    int oom; /* sticky: set once an allocation failed, later appends are ignored */
} ygst_buf;

void ygst_buf_init(ygst_buf *b);
void ygst_buf_free(ygst_buf *b);
void ygst_buf_reset(ygst_buf *b);
int ygst_buf_reserve(ygst_buf *b, size_t extra);
int ygst_buf_append(ygst_buf *b, const void *p, size_t n);
int ygst_buf_append_str(ygst_buf *b, const char *s);
int ygst_buf_append_char(ygst_buf *b, char c);
int ygst_buf_append_int(ygst_buf *b, long long v);
/* Returns a NUL-terminated view of the content (the NUL is not counted in len). Never NULL. */
const char *ygst_buf_cstr(ygst_buf *b);

char *ygst_strdup(const char *s);
int ygst_strcaseeq(const char *a, const char *b);
int ygst_has_suffix_ci(const char *s, const char *suffix);
/* Copies s into out (cap bytes) without leading and trailing ASCII whitespace. */
size_t ygst_trim_copy(const char *s, char *out, size_t cap);
int ygst_is_blank(const char *s);

/* ------------------------------------------------------------------ crypto */

typedef struct {
    uint32_t h[8];
    uint64_t total;
    unsigned char block[64];
    size_t used;
} ygst_sha256_ctx;

void ygst_sha256_init(ygst_sha256_ctx *c);
void ygst_sha256_update(ygst_sha256_ctx *c, const void *data, size_t n);
void ygst_sha256_final(ygst_sha256_ctx *c, unsigned char out[32]);
void ygst_sha256(const void *data, size_t n, unsigned char out[32]);
void ygst_hmac_sha256(const void *key, size_t key_len, const void *msg, size_t msg_len, unsigned char out[32]);
/* Standard Base64 with padding. Returns the encoded length or 0 when cap is too small. */
size_t ygst_base64_encode(const unsigned char *in, size_t n, char *out, size_t cap);
void ygst_hex_lower(const unsigned char *in, size_t n, char *out);

/* ------------------------------------------------------------------ form fields and signing */

typedef struct {
    char *name;
    char *value;
} ygst_field;

typedef struct {
    ygst_field *items;
    size_t count;
    size_t cap;
    int oom;
} ygst_fields;

void ygst_fields_init(ygst_fields *f);
void ygst_fields_free(ygst_fields *f);
/* Sets name to value, replacing an existing field of the same name in place. value NULL removes. */
int ygst_fields_set(ygst_fields *f, const char *name, const char *value);
const char *ygst_fields_get(const ygst_fields *f, const char *name);
int ygst_fields_remove(ygst_fields *f, const char *name);
int ygst_fields_copy(const ygst_fields *src, ygst_fields *dst);

/* Compares two UTF-8 strings by UTF-16 code units, the order of Java String.compareTo and of
 * JavaScript Array.prototype.sort, which the platform signature check uses. */
int ygst_utf16_compare(const char *a, const char *b);

/* key1=value1&key2=value2 over fields with a non-empty value, sorted by key. */
int ygst_sign_payload(const ygst_fields *f, ygst_buf *out);
/* Base64(HMAC_SHA256(payload, secret)). out receives 44 characters plus NUL. */
int ygst_sign(const ygst_fields *f, const char *secret, char out[45]);

/* ------------------------------------------------------------------ JSON */

typedef struct {
    const char *p;
    size_t n;
} ygst_span;

/* Validates that s holds exactly one JSON value (plus whitespace). root receives the value. */
int ygst_json_parse(const char *s, size_t n, ygst_span *root);
/* 'o' object, 'a' array, 's' string, 'n' number, 't' true, 'f' false, 'z' null, 0 invalid. */
int ygst_json_type(ygst_span v);
/* 1 found, 0 absent, -1 malformed. With duplicate keys the last one wins. */
int ygst_json_member(ygst_span obj, const char *key, ygst_span *out);
/* Iterates an array. *pos must start at 0. Returns 1 with an element, 0 at the end, -1 malformed. */
int ygst_json_array_next(ygst_span arr, size_t *pos, ygst_span *out);
/* Iterates an object. *pos must start at 0. key receives the raw key token (with quotes). */
int ygst_json_object_next(ygst_span obj, size_t *pos, ygst_span *key, ygst_span *value);
/* Appends the unescaped UTF-8 text of a string token. */
int ygst_json_string(ygst_span v, ygst_buf *out);
int ygst_json_int(ygst_span v, long long *out);
int ygst_json_number(ygst_span v, double *out);
int ygst_json_bool(ygst_span v, int *out);
/* Appends s as a quoted JSON string. */
int ygst_json_escape(ygst_buf *out, const char *s);
int ygst_json_escape_n(ygst_buf *out, const char *s, size_t n);

/* ------------------------------------------------------------------ core types */

typedef enum {
    YGST_CT_SUPPORTED = 0,
    YGST_CT_UNSUPPORTED = 1, /* a Shengtong coreType the platform does not evaluate */
    YGST_CT_UNKNOWN = 2      /* not a Shengtong coreType at all */
} ygst_ct_status;

/* Maps a KYTestType value to the Shengtong coreType string. NULL when there is no string. */
const char *ygst_coretype_for_enum(unsigned long kytesttype);
size_t ygst_coretype_enum_count(void);
ygst_ct_status ygst_coretype_check(const char *core_type);
/* coreTypeNS wins when it is not blank, otherwise the enum value is mapped. */
ygst_ct_status ygst_coretype_resolve(const char *core_type_ns, unsigned long kytesttype, char *out, size_t cap);
/* Chinese kernels (*.cn and pinyin), where refPinyin may replace refText. */
int ygst_coretype_is_chinese(const char *core_type);
/* Paragraph kernels (para.*): DESIGN 6 result-shape alignment always asks for word details. */
int ygst_coretype_is_paragraph(const char *core_type);
/* Fields every request of this coreType carries: paragraph_need_word_score=1 for para.*.
 * An existing field keeps its position and gets the value. */
int ygst_fields_apply_coretype_rules(ygst_fields *f, const char *core_type);
/* Local request validation before any I/O: 0, 60003 (coreType) or 60006 (refText). */
int ygst_validate_request(ygst_ct_status status, const char *core_type, const char *ref_text,
                          const char *ref_pinyin);

/* ------------------------------------------------------------------ KYTestConfig to form fields */

typedef struct {
    const char *core_type;         /* resolved coreType, selects the coreType rules */
    const char *ref_text;
    const char *ref_pinyin;
    int attach_audio_url;          /* BOOL attachAudioUrl */
    int phoneme_option;            /* KYPhonemeOption: 0 unset, 1 CMU, 2 KK, 3 IPA88 */
    int phoneme_output;            /* BOOL phoneme_output */
    int age_group;                 /* KYAgeGroupSupportOption: 0 unset, 1..3 */
    int mode;                      /* KYModeType: 0 school (engine default), 1 home */
    int paragraph_need_word_score; /* BOOL isParagraphNeedWordScore */
    double scale;
    double precision;
    double slack;
    const char *keywords;
    int q_type;                    /* KYQType, 0 is not sent */
    const char *customized_lexicon_json;
    const char *negative_reftext;
    const char *dict_dialect;
    int detect_nonscorable;        /* BOOL */
    const char *customized_pron_json;
    int output_rawtext;            /* BOOL */
    int vad_detection;             /* BOOL vad_detection */
    const char *keypoints_json;
    double keypoints_weight;
    const char *negative_keypoints_json;
    int punctuate;                 /* BOOL */
    double readtype_diagnosis;
    double itn;
    const char *request_json;
    const ygst_fields *custom_params; /* customParams, values already converted to text */
} ygst_test_params;

void ygst_test_params_init(ygst_test_params *p);
int ygst_params_to_fields(const ygst_test_params *p, ygst_fields *out);
/* Shortest text that reads back as the same double, plain decimal, no exponent. */
int ygst_fmt_double(double v, char *out, size_t cap);
/* Field names a multipart header can carry without escaping: non-empty, no quote, CR or LF. */
int ygst_field_name_ok(const char *name);

/* ------------------------------------------------------------------ multipart */

int ygst_multipart_boundary(const unsigned char rnd[16], char out[64]);
int ygst_multipart_content_type(const char *boundary, char *out, size_t cap);
/* Text fields in insertion order without a part Content-Type, then the file part.
 * Returns 0, -1 on bad input or allocation failure, -2 when the boundary occurs in the content. */
int ygst_multipart_build(const ygst_fields *fields, const char *boundary, const char *file_field,
                         const char *filename, const char *file_content_type, const unsigned char *file,
                         size_t file_len, ygst_buf *out);
/* Normalises an audioType or file extension to the extension used for the upload. */
const char *ygst_audio_ext(const char *audio_type);
const char *ygst_audio_content_type(const char *ext);
/* 1 when the audio type means 16-bit PCM wrapped in WAV (wav, pcm, empty). */
int ygst_audio_type_is_pcm(const char *audio_type);

/* ------------------------------------------------------------------ WAV and audio checks */

typedef struct {
    int format;      /* 1 PCM, 3 float, 0xFFFE extensible */
    int channels;
    int sample_rate;
    int bits;
    size_t data_offset;
    size_t data_len;
} ygst_wav_info;

void ygst_wav_header(unsigned char out[44], uint32_t data_len, int sample_rate, int channels, int bits);
/* 0 when a RIFF/WAVE file with fmt and data chunks was found. */
int ygst_wav_parse(const unsigned char *p, size_t n, ygst_wav_info *info);
int ygst_is_riff_wave(const unsigned char *p, size_t n);
long ygst_pcm_duration_ms(size_t bytes, int sample_rate, int channels, int bits);
/* Checks the bytes about to be uploaded. 0 or a compat errId: 60002 empty, 60009 above 50 MB,
 * for PCM WAV also 60002 without data, 60005 below 1 s and 60009 above 300 s. */
int ygst_audio_check_upload(const unsigned char *data, size_t len);
/* Checks raw PCM before it is wrapped in a WAV header. */
int ygst_audio_check_pcm(size_t pcm_len, int sample_rate, int channels, int bits);

/* ------------------------------------------------------------------ VAD and intensity */

typedef struct {
    int sample_rate;
    int frame_samples;
    int seek_ms;
    int ref_length_ms;
    int status;           /* 0 not speaking yet, 1 speaking, 2 speech ended */
    int ended_edge;       /* 1 when the last process call moved the status to 2 */
    int speech_run;       /* consecutive speech frames */
    int speaking_ms;
    int silence_ms;
    int intensity;        /* 0..100 of the last complete frame */
    double noise_db;
    double acc_sumsq;
    int acc_n;
    int has_carry;
    unsigned char carry;
} ygst_vad;

/* seek and ref_length use Shengtong units of 10 ms. seek <= 0 means 60. */
void ygst_vad_init(ygst_vad *v, int sample_rate, double seek_10ms, double ref_length_10ms);
int ygst_vad_process(ygst_vad *v, const int16_t *pcm, size_t samples);
/* Little-endian PCM16 bytes; an odd trailing byte is carried to the next call. */
int ygst_vad_process_bytes(ygst_vad *v, const unsigned char *bytes, size_t n);
int ygst_sound_intensity_db(double db);
int ygst_sound_intensity(const int16_t *pcm, size_t samples);

/* Countdown for kyTestEngineDidRecordTick: remaining milliseconds and remaining percent 0..100. */
void ygst_tick_compute(double duration_ms, double elapsed_ms, double *millis_left, double *percent_left);

/* ------------------------------------------------------------------ errors table (generated) */

typedef struct {
    int code;
    const char *name;
    const char *category;
    int retryable;
    int http;
    const char *message;
} ygst_error_entry;

typedef struct {
    int err_id;
    const char *message;
} ygst_compat_entry;

extern const ygst_error_entry ygst_table_errors[];
extern const size_t ygst_table_errors_count;
extern const ygst_error_entry ygst_table_warnings[];
extern const size_t ygst_table_warnings_count;
extern const ygst_error_entry ygst_table_local[];
extern const size_t ygst_table_local_count;
extern const ygst_compat_entry ygst_table_compat[];
extern const size_t ygst_table_compat_count;
extern const int ygst_table_retryable_http[];
extern const size_t ygst_table_retryable_http_count;
extern const char *const ygst_table_version;

const ygst_error_entry *ygst_error_find(int code);
const ygst_error_entry *ygst_warning_find(int code);
const ygst_error_entry *ygst_local_find(int code);
const char *ygst_compat_message(int err_id);
const char *ygst_http_fallback_category(int status);
int ygst_http_retryable(int status);

/* ------------------------------------------------------------------ retry policy */

typedef struct {
    uint64_t s;
} ygst_rng;

void ygst_rng_seed(ygst_rng *r, uint64_t seed);
uint64_t ygst_rng_next(ygst_rng *r);
double ygst_rng_uniform(ygst_rng *r); /* [0, 1) */

#define YGST_MAX_ERR_IDS 16

typedef struct {
    int max_retries;         /* 2: three attempts per submission */
    int initial_delay_ms;    /* 200 */
    double multiplier;       /* 2.0 */
    int max_delay_ms;        /* 4000 */
    double jitter;           /* 0.3 */
    int respect_retry_after; /* 1 */
    int max_retry_after_ms;  /* 30000 */
    int total_timeout_ms;    /* 300000 for one logical call including waits */
    int auto_retry;          /* KYTestConfig.autoRetry */
    int max_auto_retries;    /* 2 extra submissions */
    int err_ids[YGST_MAX_ERR_IDS];
    int n_err_ids;           /* defaults to {20009} */
} ygst_retry_policy;

void ygst_retry_policy_default(ygst_retry_policy *p);
/* Adds an errId given as text or number. Returns 0, -1 when invalid or the list is full. */
int ygst_retry_policy_add_err_id(ygst_retry_policy *p, long long err_id);
int ygst_retry_policy_has_err_id(const ygst_retry_policy *p, int err_id);
/* delay(n) = min(max, initial * multiplier^(n-1)) * (1 + U(-jitter, +jitter)), n >= 1 */
long ygst_backoff_delay_ms(const ygst_retry_policy *p, int n, ygst_rng *rng);
/* Retry-After as delta seconds or IMF-fixdate. 0 when parsed, -1 otherwise. */
int ygst_parse_retry_after(const char *value, int64_t now_ms, long *out_ms);

/* Retry rule of DESIGN 2.3: local code, then business code, then HTTP status. */
int ygst_is_retryable(int local_code, int biz_code, int http_status);
/* errId delivered to the Shengtong callback for a failure. */
int ygst_errid_for(int local_code, int biz_code, int http_status, int retryable);

/* ------------------------------------------------------------------ response classification */

typedef struct {
    int http_status;           /* 0 when no HTTP response was received */
    int local_code;            /* 0 or YGST_LOCAL_* */
    const char *body;
    size_t body_len;
    long retry_after_ms;       /* -1 when the header is absent */
    int replayed;              /* Idempotency-Replayed: true */
    const char *local_message; /* optional platform error text for local failures */
} ygst_outcome;

typedef struct {
    int success;
    ygst_span result;          /* raw JSON value of "result", points into the body */
    ygst_buf record_id;
    int has_record_id;
    ygst_span record_id_raw;   /* the recordId token as sent by the platform */
    ygst_buf audio_url;
    int has_audio_url;
    int http_status;
    int local_code;
    int biz_code;
    int retryable;
    int err_id;
    ygst_buf message;
} ygst_classification;

void ygst_classification_init(ygst_classification *c);
void ygst_classification_free(ygst_classification *c);
int ygst_classify(const ygst_outcome *o, ygst_classification *c);
/* error text of the compat error JSON: for 20009 "<20009 message>: HTTP 503 code=50000 <platform
 * message> (attempts 3)", otherwise the platform or local message, else the errId message. */
int ygst_error_message(const ygst_classification *c, int err_id, int attempts, ygst_buf *out);
/* errId for a non-retryable HTTP failure without a business code, by the httpFallback category. */
int ygst_http_fallback_code(int status);

/* ------------------------------------------------------------------ call controller */

typedef enum { YGST_ACT_SUCCESS = 1, YGST_ACT_RETRY = 2, YGST_ACT_FAIL = 3 } ygst_action_kind;

typedef struct {
    ygst_action_kind kind;
    long delay_ms; /* for RETRY */
    int err_id;    /* for FAIL */
    int auto_retry; /* 1 when the RETRY is a new autoRetry submission */
} ygst_action;

typedef struct {
    ygst_retry_policy policy;
    ygst_rng rng;
    int64_t start_ms;
    int attempt;        /* attempts made in the current submission */
    int submission;     /* 0 for the first submission, then autoRetry submissions */
    int total_attempts;
} ygst_call;

void ygst_call_init(ygst_call *c, const ygst_retry_policy *p, uint64_t seed, int64_t now_ms);
/* Feed the classification of the attempt that just finished; returns what to do next. */
ygst_action ygst_call_next(ygst_call *c, const ygst_classification *cls, long retry_after_ms, int64_t now_ms);

/* ------------------------------------------------------------------ envelope */

/* Shengtong result-shape alignment (DESIGN 6): every result.sentences[].details[] item without
 * "overall" gets "overall" copied from scores.overall and, when it has no "pronunciation",
 * "pronunciation" copied from scores.pronunciation, appended at the end of the item. Nothing else
 * changes: all other bytes are copied as the platform sent them. Returns the number of members
 * added, -1 on allocation failure. */
int ygst_align_result(ygst_span result, ygst_buf *out);

/* "YYYY-MM-DD HH:MM:SS:mmm" in the time zone given as an offset from UTC in minutes. */
int ygst_format_dt(int64_t epoch_ms, int tz_offset_min, char out[32]);
/* record_id_json is the raw recordId token (NULL: member omitted). The result goes through
 * ygst_align_result. */
int ygst_envelope_result(ygst_buf *out, const char *token_id, const char *record_id_json, const char *app_key,
                         const char *user_id, const char *ref_text, const char *dt_last_response,
                         ygst_span result_raw, const char *params_json, const char *audio_url);
int ygst_envelope_error(ygst_buf *out, const char *token_id, int err_id, const char *message,
                        const char *app_key);
int ygst_params_json(ygst_buf *out, const char *app_key, const char *user_id, int64_t timestamp_sec,
                     const char *audio_type, int sample_rate, int channel, int sample_bytes,
                     const char *core_type, const char *token_id, const ygst_fields *request_fields);

/* ------------------------------------------------------------------ misc */

typedef enum {
    YGST_BASE_CUSTOM = 0,
    YGST_BASE_DEFAULT_EMPTY = 1,
    YGST_BASE_DEFAULT_SHENGTONG = 2,
    YGST_BASE_ERR_SCHEME = 3, /* not http, https, ws or wss: initEngine fails */
    YGST_BASE_ERR_HOST = 4    /* no host: initEngine fails */
} ygst_base_kind;

/* KYStartEngineConfig.server to a REST base URL (see DESIGN 6, setServerAddress). On an error
 * out still holds the default base. */
ygst_base_kind ygst_resolve_base_url(const char *server, char *out, size_t cap);
int ygst_build_url(const char *base, const char *core_type, char *out, size_t cap);
int ygst_token_id(const unsigned char rnd[16], char out[33]);
int ygst_idempotency_key_ok(const char *key);
void ygst_mask_app_key(const char *app_key, char out[16]);

#ifdef __cplusplus
}
#endif

#endif /* YGST_CORE_H */
