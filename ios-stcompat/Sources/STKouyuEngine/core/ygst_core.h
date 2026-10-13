/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Portable C99 core of the STKouyuEngine compatibility layer.
 *
 * Everything that does not need an Apple framework lives here so that it can be compiled and
 * unit tested on Linux: signing, form field mapping, multipart assembly, WAV handling, VAD,
 * retry decisions, error mapping, result envelope assembly and, since 2.1.0, the realtime
 * feedback round over the compat WebSocket (handshake, frames, progress, reconnect decisions).
 * Since 2.1.1 the same round streams every recording by default, and the per-phase timing of an
 * evaluation (its log lines, the Server-Timing header and the platform timing object) is here.
 * Since 2.2.0 the requests, answers and decisions of TtsClient and ReportClient are here as well.
 * The Objective-C layer only moves bytes between this core and NSURLSession / AVFoundation.
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

#define YGST_SDK_VERSION "2.2.0"
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
#define YGST_LOCAL_INVALID_ARGUMENT 90010
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
/* Appends s without leading and trailing ASCII whitespace, whatever its length. NULL appends nothing. */
int ygst_buf_append_trimmed(ygst_buf *b, const char *s);

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
/* open.eval.cn (2.1.2): refText holds model answers or the question, refPinyin cannot replace it. */
int ygst_coretype_is_open_question(const char *core_type);
/* Paragraph kernels (para.*): DESIGN 6 result-shape alignment always asks for word details. */
int ygst_coretype_is_paragraph(const char *core_type);
/* Fields every request of this coreType carries: paragraph_need_word_score=1 for para.*.
 * An existing field keeps its position and gets the value. */
int ygst_fields_apply_coretype_rules(ygst_fields *f, const char *core_type);
/* Local request validation before any I/O: 0, 60003 (coreType) or 60006 (refText). */
int ygst_validate_request(ygst_ct_status status, const char *core_type, const char *ref_text,
                          const char *ref_pinyin);
/* skegn_start (2.2.0): request.coreType as text. Blank or missing is no coreType at all (UNKNOWN,
 * out ""); there is no KYTestType to fall back to. */
ygst_ct_status ygst_coretype_resolve_text(const char *core_type, char *out, size_t cap);
/* The coreType a KYTestConfig asks for, as the 60003 text names it: coreTypeNS trimmed when it is
 * not blank, else the Shengtong string of the KYTestType value, else "KYTestType <n>". */
int ygst_coretype_requested(const char *core_type_ns, unsigned long kytesttype, ygst_buf *out);
/* The error text of errId 60003 (2.2.0): "不支持的评测内核：<requested coreType, trimmed>", with
 * "未指定" for a blank or missing coreType. The first part is the 60003 text of spec/errors.json. */
int ygst_coretype_unsupported_message(const char *requested, ygst_buf *out);

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
    ygst_span root;            /* the body object of a success (2.1.1: its "timing"), p NULL otherwise */
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
/* Reading progress delivered through the result callbacks while the learner reads (2.1.0):
 * {"tokenId","recordId":"","applicationId","userId","refText","eof":0,"dtLastResponse","result":..}.
 * result_raw is the "result" object of the platform progress frame, passed through byte for byte
 * except that the first skip_words items of its "words" array are left out (ygst_words_dedupe). */
int ygst_envelope_progress(ygst_buf *out, const char *token_id, const char *app_key, const char *user_id,
                           const char *ref_text, const char *dt_last_response, ygst_span result_raw,
                           int skip_words);
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

/* ------------------------------------------------------------------ realtime feedback (2.1.0) */

/* CONTRACT 5.2 and 5.5, DESIGN 14.3, BRIEF change of 2026-10-12 06:00 UTC: with
 * KYTestConfig.realtime_feedback the round streams over the compat WebSocket wss://<base>/{coreType}
 * and the platform reports reading progress while the learner reads. Since 2.1.1 every recording
 * and 16 kHz mono PCM feed streams over the same round by default (YuguCompat.streamUpload); the
 * parameter frame asks for progress only with realtime_feedback. A compat progress frame is
 *   {"eof":0,"recordId":"","result":{"kernel_version","resource_version","bytes","readUnits",
 *    "matchedUnits","totalUnits","progress":[{word,read,matched}],"words":[Shengtong items]}}
 * where words holds the units that became read since the previous progress frame, covering units
 * [readUnits - words.length, readUnits); without tracking the frame is {"eof":0,"result":{"bytes":n}}.
 * Everything decided here is covered by the Linux tests; the Objective-C layer only moves frames
 * between this code and NSURLSessionWebSocketTask. */

#define YGST_WS_MAX_FRAME_BYTES 32000u /* audio frames, DESIGN 2.5 */

/* Appends s percent-encoded: RFC 3986 unreserved characters stay, every other byte is %XX. */
int ygst_url_encode(ygst_buf *out, const char *s);
/* WebSocket base of a REST base: https becomes wss, http becomes ws, trailing slashes go. */
int ygst_ws_base_url(const char *rest_base, char *out, size_t cap);
/* Handshake URL <ws base>/<coreType>?appKey=..&timestamp=..&nonce=..&idempotencyKey=..[&extra]&signature=..
 * The signature is the REST signer (ygst_sign) over every other query parameter, values as sent
 * before percent-encoding. extra (may be NULL) adds query parameters in their order, signed as
 * well; parameters with an empty value are left out. 0, or -1 on bad input. */
int ygst_ws_handshake_url(const char *rest_base, const char *core_type, const char *app_key, const char *secret,
                          int64_t timestamp_sec, const char *nonce, const char *idempotency_key,
                          const ygst_fields *extra, ygst_buf *out);
/* Members of the parameter frame besides the form fields (2.1.1: a recording streams over the
 * WebSocket whether or not reading progress was asked for). */
#define YGST_WSP_REALTIME_FEEDBACK 1 /* "realtime_feedback":true, only with KYTestConfig.realtime_feedback:
                                      * the platform sends progress frames; without it only the final */
#define YGST_WSP_TIMING 2            /* "timing":1, only at log level INFO or DEBUG: the final frame
                                      * carries the platform timing object */
/* Compat parameter frame: the REST form fields as JSON strings in their order, then
 * "realtime_feedback":true and "timing":1 as flags asks, then "idempotencyKey". Names the platform
 * reads as commands or that the frame sets itself (cmd, end, data, realtime_feedback,
 * idempotencyKey, and timing with YGST_WSP_TIMING) are left out. Returns how many fields were left
 * out, or -1 on bad input. */
int ygst_ws_param_frame(const ygst_fields *fields, const char *idempotency_key, int flags, ygst_buf *out);

typedef enum {
    YGST_WSF_INVALID = 0,   /* not a JSON object */
    YGST_WSF_CONNECTED = 1, /* {"event":"connected"} */
    YGST_WSF_STARTED = 2,   /* {"event":"started"} */
    YGST_WSF_PONG = 3,      /* {"event":"pong"} */
    YGST_WSF_ERROR = 4,     /* {"event":"error","code":N,"message":".."} */
    YGST_WSF_PROGRESS = 5,  /* no event, "eof":0 */
    YGST_WSF_FINAL = 6,     /* no event, eof other than 0, or a result or recordId; or {"event":"result"} */
    YGST_WSF_OTHER = 7      /* another event, or an object without any of the above */
} ygst_wsf_kind;

typedef struct {
    ygst_wsf_kind kind;
    ygst_span root;
    ygst_span result;   /* "result" of PROGRESS and FINAL, p NULL when absent */
    long long bytes;    /* PROGRESS: result.bytes, -1 when absent */
    int has_progress;   /* PROGRESS: result.progress is an array, the platform tracks progress */
    int read_units;     /* PROGRESS: readUnits, counted from progress when absent, else -1 */
    int matched_units;  /* matchedUnits, counted from progress when absent, else -1 */
    int total_units;    /* totalUnits, the length of progress when absent, else -1 */
    int has_words;      /* PROGRESS: result.words is an array */
    ygst_span words;    /* that array: Shengtong items of the units read since the previous frame */
    int word_count;     /* its items */
    int code;           /* ERROR: "code", 0 when absent */
    int replayed;       /* FINAL: "replayed": true */
} ygst_ws_frame;

/* Classifies one text frame of the platform. Spans point into text. Returns 0, -1 when invalid. */
int ygst_ws_frame_parse(const char *text, size_t n, ygst_ws_frame *f);
/* An {"event":"error"} frame as the outcome of an attempt: the business code decides like a REST
 * error body (spec/errors.json); a frame without a code is a server failure and retryable. */
int ygst_ws_classify_error(ygst_span frame, ygst_classification *c);

/* Reconnect rule of the drop-ins (BRIEF change 06:00 UTC): after a reconnect the platform restarts
 * progress from zero while the audio is replayed. The words of a frame cover the units
 * [readUnits - word_count, readUnits); items below the number of units already delivered in this
 * evaluation are dropped, the rest is delivered. One per evaluation. */
typedef struct {
    int delivered; /* units [0, delivered) went out through the callbacks */
} ygst_words_dedupe;

void ygst_words_dedupe_init(ygst_words_dedupe *d);
/* How many leading items of the frame's words to leave out; word_count (or more) when none is new,
 * then nothing is delivered. Otherwise the rest is delivered and counted. read_units -1 (unknown)
 * takes the items as the next ones. */
int ygst_words_dedupe_skip(ygst_words_dedupe *d, int read_units, int word_count);

/* The round controller. The caller owns the WebSocket and the round audio, feeds every event in
 * and executes the actions it gets back; the controller makes every decision:
 *   - connect, parameter frame after "connected", audio after "started" from the first byte,
 *     audio as it arrives, {"cmd":"end"} at stop (or right after "started" when stop came first);
 *   - progress frames with new words through the dedupe; frames with empty or no words and
 *     bytes-only frames are not delivered;
 *   - heartbeat ping before end, connect and result timeouts;
 *   - a failure before end closes the connection and the evaluation goes over REST at stop with
 *     the same tokenId: the platform never evaluated this round;
 *   - after end every connection is an attempt of the REST call controller (ygst_call, same
 *     policy, same classification): retryable failures reconnect with the same tokenId and
 *     replay parameter frame, audio and end, until the policy gives up with the same errId and
 *     message as REST (20009 when the retries are exhausted);
 *   - the final frame is classified exactly like a REST response body. */
typedef enum {
    YGST_RT_IDLE = 0,       /* not started */
    YGST_RT_CONNECTING = 1, /* connection requested, waiting for "connected" */
    YGST_RT_STARTING = 2,   /* parameter frame sent, waiting for "started" */
    YGST_RT_STREAMING = 3,  /* audio goes out as it arrives */
    YGST_RT_ENDING = 4,     /* end sent, waiting for the final frame */
    YGST_RT_BACKOFF = 5,    /* after end: waiting for the next connection */
    YGST_RT_FALLBACK = 6,   /* failed before end: the evaluation goes over REST */
    YGST_RT_DONE = 7        /* final frame or error decided, or cancelled */
} ygst_rt_state;

/* Execute in this order. */
typedef struct {
    int close;             /* close the current connection; none of its later events matter */
    int connect;           /* open a new connection with a fresh ygst_ws_handshake_url */
    int send_params;       /* send the parameter frame (ygst_ws_param_frame) */
    size_t audio_from;     /* send round audio [audio_from, audio_to) in binary frames of at most */
    size_t audio_to;       /* max_frame_bytes each */
    int send_end;          /* send {"cmd":"end"} */
    int send_ping;         /* send a protocol ping */
    int progress;          /* deliver rt->frame.result as an eof 0 envelope (ygst_envelope_progress) */
    int words_skip;        /*   leaving out this many leading items of its words */
    int progress_held;     /* a progress frame had only words already delivered (DEBUG log) */
    int retry;             /* after end: the next connection opens in delay_ms (WARN log) */
    long delay_ms;
    int final;             /* deliver the final frame: rt->cls is its classification, spans into it */
    int fail;              /* deliver error err_id, message from ygst_rt_error_message */
    int err_id;
    int fallback;          /* evaluate over REST now: stop was requested and the round failed before end */
} ygst_rt_actions;

typedef struct {
    /* configuration, set after ygst_rt_init and before ygst_rt_start */
    ygst_retry_policy policy;  /* attempts after end, as for one REST submission */
    uint64_t seed;             /* back-off jitter */
    int connect_timeout_ms;    /* connection request to "started", default 20000 */
    int attempt_timeout_ms;    /* end to the final frame, default 60000 */
    int ping_interval_ms;      /* default 15000, 0 disables the heartbeat */
    int pong_timeout_ms;       /* default 30000 */
    size_t max_frame_bytes;    /* default YGST_WS_MAX_FRAME_BYTES */
    /* state, read only */
    ygst_rt_state state;
    int end_requested;         /* stop was called */
    int end_sent;              /* end went out on some connection: failures are attempts from now on */
    int connections;           /* connections requested */
    size_t total;              /* round audio known to the controller */
    size_t sent;               /* audio sent on the current connection */
    int64_t deadline_ms;       /* connect or result deadline of the current connection, 0 none */
    int64_t next_ping_ms;      /* 0 none */
    int64_t pong_deadline_ms;  /* 0: no ping outstanding */
    int64_t reconnect_at_ms;
    ygst_words_dedupe dedupe;
    ygst_ws_frame frame;       /* last frame given to ygst_rt_on_text, spans into that text */
    ygst_call call;            /* attempts after end */
    ygst_classification cls;   /* outcome of the last attempt after end */
    ygst_buf failure;          /* why the last connection failed, for logs */
} ygst_rt;

void ygst_rt_init(ygst_rt *rt);
void ygst_rt_free(ygst_rt *rt);
ygst_rt_actions ygst_rt_start(ygst_rt *rt, int64_t now_ms);
/* total_bytes: the whole round audio so far (it only grows). */
ygst_rt_actions ygst_rt_on_audio(ygst_rt *rt, size_t total_bytes, int64_t now_ms);
/* stopEngine: the round audio is complete. fallback in the result: evaluate over REST now. */
ygst_rt_actions ygst_rt_on_stop(ygst_rt *rt, size_t total_bytes, int64_t now_ms);
ygst_rt_actions ygst_rt_on_text(ygst_rt *rt, const char *text, size_t n, int64_t now_ms);
ygst_rt_actions ygst_rt_on_pong(ygst_rt *rt, int64_t now_ms);
/* The connection failed: local_code YGST_LOCAL_* without a response, or the HTTP status of a
 * rejected handshake (local_code 0). message: what happened, for logs and the error text. */
ygst_rt_actions ygst_rt_on_failure(ygst_rt *rt, int local_code, int http_status, const char *message,
                                   int64_t now_ms);
/* Timers: call at least every 250 ms while ygst_rt_active. */
ygst_rt_actions ygst_rt_on_tick(ygst_rt *rt, int64_t now_ms);
/* cancelEngine: no further actions. The caller closes the connection. */
void ygst_rt_cancel(ygst_rt *rt);
/* 1 while a connection is open or a reconnect is pending. */
int ygst_rt_active(const ygst_rt *rt);
/* error text of a fail action, like the REST error JSON. */
int ygst_rt_error_message(const ygst_rt *rt, int err_id, ygst_buf *out);

/* ------------------------------------------------------------------ per-phase timing (2.1.1) */

/* BRIEF 2.1.1 C and D: where the time of one evaluation goes, for one INFO summary line per
 * evaluation and DEBUG detail. Times are milliseconds of a monotonic clock, 0 when the phase did
 * not happen; durations and sizes are -1 when unknown. The platform reports its own share in the
 * Server-Timing header of every compat REST response, and in a top-level "timing" object of the
 * final JSON when the request asked for it (form field timing=1, or "timing":1 in the parameter
 * frame). The SDK asks only at log level INFO or DEBUG; the result envelope is built from
 * "result", "recordId" and "audioUrl" only, so "timing" never reaches the app. */

typedef enum {
    YGST_TM_NONE = 0,   /* answered locally: nothing went to the platform */
    YGST_TM_REST = 1,   /* one request after capture: file mode, stream upload off, not streamable */
    YGST_TM_WS = 2,     /* streamed over the compat WebSocket while capturing */
    YGST_TM_WS_REST = 3 /* the WebSocket failed before end: the request after stop evaluated it */
} ygst_tm_mode;

typedef struct {
    ygst_tm_mode mode;
    int ok;                    /* 1 result, 0 error */
    int err_id;                /* errId of an error */
    int attempts;              /* REST attempts of the evaluating call, or WebSocket connections */
    int replayed;              /* the platform replayed an earlier evaluation of this tokenId */
    int deflate;               /* WebSocket: permessage-deflate was negotiated */
    char encoding[16];         /* REST: Content-Encoding of the response, "" none */
    char fallback[160];        /* ws>rest: why the WebSocket failed before end */
    /* monotonic milliseconds */
    int64_t start_ms;          /* capture started, or a file evaluation started */
    int64_t stop_ms;           /* stopEngine, the automatic end of capture (VAD, duration, limits), or the
                                * start call of an audioPath or skegn evaluation */
    int64_t ws_connect_ms;     /* first connection requested */
    int64_t ws_handshake_ms;   /* "connected" on the first connection: the handshake is done */
    int64_t ws_params_ms;      /* first parameter frame handed to the socket */
    int64_t ws_first_audio_ms; /* first audio frame handed to the socket */
    int64_t ws_end_ms;         /* end frame written to the network (the last one, after reconnects) */
    int64_t ws_final_first_ms; /* first byte of the final frame, 0 when the transport cannot tell */
    int64_t ws_final_ms;       /* final frame received */
    int64_t rest_start_ms;     /* request of the last attempt started */
    int64_t rest_done_ms;      /* its response complete */
    int64_t parsed_ms;         /* final JSON parsed and the result envelope built */
    int64_t delivered_ms;      /* result callback returned */
    /* the REST split of the last attempt, from the transport */
    long upload_ms;            /* request start to the last byte of the body sent, with connect and TLS */
    long wait_ms;              /* last byte sent to the first byte of the response */
    long download_ms;          /* first to last byte of the response */
    /* bytes */
    long long audio_bytes;     /* PCM of the round, or the uploaded file */
    int audio_bytes_per_sec;   /* 32000 for 16 kHz 16 bit mono, 0 when the duration is not known */
    long long upload_bytes;    /* REST request body */
    long long queued_at_stop;  /* WebSocket: captured audio not yet written to the network at stop */
    long long wire_bytes;      /* the result as received, before decompression (REST body, final frame) */
    long long body_bytes;      /* REST response body after decompression, or the WebSocket final frame */
    /* the platform's share: Server-Timing header or the "timing" object */
    long long server_total_ms;
    long long server_engine_ms;
    long long server_platform_ms;
} ygst_timing;

void ygst_timing_init(ygst_timing *t);
/* 1 when the platform should add its timing object: log level INFO (2) or DEBUG (3). */
int ygst_timing_wanted(int log_level);
/* Server-Timing: total;dur=5321, engine;dur=4100, platform;dur=1221 (W3C Server Timing). Metrics in
 * any order and case, other metrics and parameters ignored, fractions rounded. Returns how many of
 * total, engine and platform were found; those go to the server times of t. */
int ygst_server_timing_parse(const char *value, ygst_timing *t);
/* The top-level "timing" object of a final JSON (REST body or WebSocket final frame):
 * {"receivedAt":..,"totalMs":..,"engineMs":..,"platformMs":..,"engineStages":{..},"audioBytes":..,
 * "wsFirstAudioAt":..}, unknown keys ignored. 1 found: its times go to t (when numeric) and timing
 * receives the object; 0 absent or not an object (timing.p NULL), -1 root is not an object. */
int ygst_timing_member(ygst_span root, ygst_timing *t, ygst_span *timing);
/* Records why the WebSocket failed before end (ws>rest), shortened, quotes replaced. */
void ygst_timing_set_fallback(ygst_timing *t, const char *reason);
/* The INFO line, the format of the Android drop-in, without a trailing newline:
 *   timing tokenId=<t> recordId=<r> mode=ws audio=31.20s afterStop=5650ms upload=120ms wait=5480ms
 *   download=- parse=40ms deliver=3ms handshake=85ms queued=6400B final=1532118B wire=- deflate
 *   server.total=5321ms server.engine=4100ms server.platform=1221ms
 * recordId (2.2.0): the recordId of the result, "-" when it has none (an error, NULL or ""), so that
 * the line matches the platform's timing of that record;
 * mode rest|ws|ws>rest; afterStop: stop to the result callback; upload: stop to the last byte sent
 * (end frame, or the request body of the last attempt); wait: from there to the first byte of the
 * result (WebSocket without first-byte time: to the whole final); download: first to last byte;
 * parse; deliver: to the callback. WebSocket: handshake, queued, final, wire, deflate,
 * connections=N when more than one. REST: attempts=N sent=<request>B body=<decoded>B
 * wire=<received>B and the Content-Encoding. Then the server.* times present, replayed, errId=N of an
 * error, fallback="<reason>" of ws>rest. Unknown values print as "-". */
int ygst_timing_summary(const ygst_timing *t, const char *token_id, const char *record_id, ygst_buf *out);
/* DEBUG: every phase that happened, in ms after the first one:
 *   timing tokenId=<t> phases start=+0ms connect=+5ms ... delivered=+36843ms */
int ygst_timing_phases(const ygst_timing *t, const char *token_id, ygst_buf *out);
/* DEBUG: the engine stages of the platform timing object as name=value, then its receivedAt,
 * wsFirstAudioAt and audioBytes:
 *   timing tokenId=<t> engineStages text_frontend_ms=1909.3 asr_ms=595.0 receivedAt=.. audioBytes=..
 * Nothing (0, out unchanged) when timing is empty or has none of them. */
int ygst_timing_stages(ygst_span timing, const char *token_id, ygst_buf *out);

/* ------------------------------------------------------------------ TtsClient and ReportClient (2.2.0) */

/* POST /api/v1/tts/generate and GET /api/v1/report/{recordId} with the appKey and secretKey of the
 * engine or of the caller (CONTRACT 2.2, 2.3, 4, 6, 7). Everything decided about them is here: the
 * JSON body and its signed parameters, the report path, the absolute audio URL, the classification
 * of {code, message, data} answers, the report fields, the wait for the AI final draft and the log
 * line. The Objective-C layer sends the requests with the session, the retry controller (ygst_call)
 * and the error mapping of the evaluations. */

#define YGST_TTS_PATH "api/v1/tts/generate"
#define YGST_REPORT_POLL_MS 1000 /* waitForFinalMs: one lookup per second while the report is pending */

typedef struct {
    const char *text;        /* required */
    const char *language;    /* strings: NULL or "" is not sent */
    const char *voice;
    const char *format;
    int has_speed;           /* integers: sent when has_* is set */
    long long speed;
    int has_pitch;
    long long pitch;
    int has_volume;
    long long volume;
    int has_sample_rate;
    long long sample_rate;
    const char *emotion;
    const char *style;
    const char *pinyin;
    const char *genre;
} ygst_tts_request;

void ygst_tts_request_init(ygst_tts_request *r);
/* The JSON body with the members that are set, in the order text, language, voice, format, speed,
 * pitch, volume, sampleRate, emotion, style, pinyin, genre, and its signed parameters (CONTRACT 2.3:
 * the top-level scalar members, each with its literal text, integers in plain decimal). 0,
 * YGST_LOCAL_INVALID_ARGUMENT when text is blank, -1 on bad input or allocation failure. */
int ygst_tts_body(const ygst_tts_request *r, ygst_buf *json, ygst_fields *sign);

typedef struct {
    ygst_buf audio_url;      /* data.audioUrl as returned, empty when absent */
    ygst_buf format;         /* data.format */
    int has_duration;
    double duration;         /* data.duration in seconds, a text such as "1.348" or a number */
} ygst_tts_answer;

void ygst_tts_answer_init(ygst_tts_answer *a);
void ygst_tts_answer_free(ygst_tts_answer *a);
/* data: the "data" object of a TTS answer. 0, -1 when it is not an object. */
int ygst_tts_parse(ygst_span data, ygst_tts_answer *a);
/* The audioUrl of a TTS answer as an absolute URL (CONTRACT 4.1): http and https URLs stay as they
 * are, /audio/... becomes <base>/tts/audio/..., another path <base><path>, anything else
 * <base>/<url>. Nothing is appended for a NULL or empty audioUrl. 0, or -1. */
int ygst_tts_absolute_url(const char *base, const char *audio_url, ygst_buf *out);

/* api/v1/report/<recordId trimmed, percent-encoded>, relative to the base URL. 0, or
 * YGST_LOCAL_INVALID_ARGUMENT when the recordId is blank. */
int ygst_report_path(const char *record_id, ygst_buf *out);
/* <base>/<path> with the trailing slashes of base removed. 0, or -1. */
int ygst_api_url(const char *base, const char *path, ygst_buf *out);

/* {code, message, data} answers of the v1 API. HTTP 2xx with "code" 0 or absent and an object
 * "data": a success, c->root is the body and c->result its "data". HTTP 2xx with another code: a
 * platform error with that code, retried when spec/errors.json says so. HTTP 2xx without a JSON
 * object or without a data object: 90005. Every other outcome exactly like ygst_classify. */
int ygst_classify_api(const ygst_outcome *o, ygst_classification *c);

typedef struct {
    ygst_buf record_id;      /* data.recordId when it is a string */
    ygst_buf report_status;  /* data.reportStatus: final, pending, instant, none; empty when absent */
    int has_summary;
    ygst_buf summary;        /* data.report.summary */
    int has_overall;
    double overall;          /* data.score.overall */
    ygst_span report;        /* data.report, data.score and data.timing when they are objects, */
    ygst_span score;         /* p NULL otherwise */
    ygst_span timing;
    ygst_span suggestions;   /* data.report.suggestions when it is an array, p NULL otherwise */
} ygst_report;

void ygst_report_init(ygst_report *r);
void ygst_report_free(ygst_report *r);
/* data: the "data" object of a report answer. 0, -1 when it is not an object. */
int ygst_report_parse(ygst_span data, ygst_report *r);
/* The next string item of a suggestions array, other items skipped. 1 with one in out (replaced),
 * 0 at the end or for anything but an array. *pos starts at 0. */
int ygst_report_next_suggestion(ygst_span suggestions, size_t *pos, ygst_buf *out);
int ygst_report_is_pending(const ygst_report *r);
int ygst_report_is_final(const ygst_report *r);
/* waitForFinalMs: after an answer, the milliseconds until the next lookup, or -1 to return this
 * answer. A pending answer is looked up again after YGST_REPORT_POLL_MS while that lookup starts
 * within wait_ms of the call's start (monotonic milliseconds); wait_ms <= 0 is one lookup. */
long ygst_report_poll_delay(long long wait_ms, int64_t started_ms, int64_t now_ms, int pending);

typedef enum {
    YGST_RP_RETURN = 1, /* the call ends with the latest answer */
    YGST_RP_AGAIN = 2,  /* look up again in delay_ms */
    YGST_RP_FAIL = 3    /* the call ends with the error of this lookup */
} ygst_rp_kind;

typedef struct {
    ygst_rp_kind kind;
    long delay_ms;  /* AGAIN */
    int degraded;   /* RETURN after a failed lookup: the latest answer is an earlier one, log one WARN line */
} ygst_rp_action;

/* getReport after one lookup, as the Android shim decides it. ok: the lookup answered, pending: its
 * reportStatus is pending; answered: an earlier lookup of this call answered. An answer is looked up
 * again while ygst_report_poll_delay allows it, else returned. A failed lookup ends the call with its
 * error when it was the first; after an answer the call ends with the latest answer, degraded. */
ygst_rp_action ygst_report_after_lookup(long long wait_ms, int64_t started_ms, int64_t now_ms, int ok, int pending,
                                        int answered);
/* The WARN line of a degraded getReport, without the report text:
 *   getReport recordId=eval_x lookup failed while waiting: code=20009 lookups=3, returning the latest answer */
int ygst_report_lookup_failed_line(const char *record_id, int code, int lookups, ygst_buf *out);

/* The log line of one call, without the secret, signatures, audio or report text:
 *   tts status=ok attempts=1 ms=842
 *   getReport recordId=eval_3fb4 status=final lookups=3 attempts=3 ms=2150
 *   getReport recordId=eval_x status=error code=40001 lookups=1 attempts=1 ms=35
 * record_id NULL leaves recordId out ("" prints "-", whitespace and control bytes print "_"); code
 * other than 0 prints status=error code=<code>, otherwise status (NULL or "" prints "-");
 * lookups below 0 is left out. */
int ygst_api_log_line(const char *op, const char *record_id, const char *status, int code, int lookups,
                      int attempts, long long ms, ygst_buf *out);

#ifdef __cplusplus
}
#endif

#endif /* YGST_CORE_H */
