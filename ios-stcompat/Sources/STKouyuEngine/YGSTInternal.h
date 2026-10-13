//
//  YGSTInternal.h
//  STKouyuEngine 平替层内部声明，不对外公开。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import <Foundation/Foundation.h>

#include "core/ygst_core.h"

NS_ASSUME_NONNULL_BEGIN

#if !__has_feature(objc_arc)
#error "STKouyuEngine is written for ARC (-fobjc-arc); SwiftPM enables it for Objective-C targets."
#endif

#define YGST_LEVEL_OFF (-1)
#define YGST_LEVEL_ERROR 0
#define YGST_LEVEL_WARN 1
#define YGST_LEVEL_INFO 2
#define YGST_LEVEL_DEBUG 3

// ------------------------------------------------------------------ logging

/// Applies the logging fields of KYStartEngineConfig.
FOUNDATION_EXTERN void YGSTLogConfigure(BOOL fileEnabled, NSInteger level, NSString *_Nullable filePath,
                                        BOOL consoleEnabled);
FOUNDATION_EXTERN NSInteger YGSTLogLevel(void);
FOUNDATION_EXTERN void YGSTLogEmit(NSInteger level, NSString *message);
FOUNDATION_EXTERN void YGSTLogf(NSInteger level, NSString *format, ...) NS_FORMAT_FUNCTION(2, 3);

// ------------------------------------------------------------------ global settings (YuguCompat)

/// 2.2.0: appKey, secretKey and platform address of an initialized engine, for the clients made with
/// +[YuguTtsClient clientFromEngine] and +[YuguReportClient clientFromEngine].
@interface YGSTEngineCredentials : NSObject
@property (nonatomic, copy) NSString *appKey;
@property (nonatomic, copy) NSString *secretKey;
/// KYStartEngineConfig.server or cloud.server resolved like every evaluation request.
@property (nonatomic, copy) NSString *baseURL;
@end

@interface YGSTSettings : NSObject
+ (instancetype)shared;
@property (atomic, copy, nullable) NSString *baseURLOverride;
@property (atomic) NSInteger logLevelOverride; // NSNotFound when not set
@property (atomic, copy, nullable) void (^logHandler)(NSInteger level, NSString *message);
@property (atomic) NSInteger maxRetries;
@property (atomic) NSTimeInterval totalTimeout;
/// 2.1.1: recordings and 16 kHz mono PCM feeds stream over the compat WebSocket, default YES.
@property (atomic) BOOL streamUpload;
/// Base URL derived from the last initEngine (KYStartEngineConfig.server).
@property (atomic, copy) NSString *configuredBaseURL;
/// 2.2.0: an unsupported coreType throws (KYTestEngine) or fails skegn_start instead of a 60003 callback.
@property (atomic) BOOL throwOnUnsupportedCoreType;
/// 2.2.0: the engine initialized last (initEngine or skegn_new) gives its credentials; owner identifies
/// that engine, so that deleteEngine, skegn_delete or a failed initEngine of another one keep them.
- (void)setEngineCredentials:(YGSTEngineCredentials *)credentials owner:(const void *)owner;
- (void)clearEngineCredentialsOfOwner:(const void *)owner;
/// nil when no engine is initialized.
- (nullable YGSTEngineCredentials *)engineCredentials;
@end

// ------------------------------------------------------------------ per-phase timing (2.1.1)

/// YES at log level INFO or DEBUG: the request asks the platform for its timing object
/// (form field timing=1, "timing":1 in the parameter frame).
FOUNDATION_EXTERN BOOL YGSTTimingWanted(void);

/// The phases of one evaluation (core/ygst_timing.c). The engine queue of the session writes the
/// struct directly; the summary is logged once, after the result callback.
@interface YGSTTiming : NSObject {
@public
    ygst_timing _t;
}
/// The platform timing object of the final JSON, for the DEBUG line.
@property (nonatomic, copy, nullable) NSData *timingObject;
/// 2.2.0: the recordId of the result for the INFO line, nil for an error ("-").
@property (nonatomic, copy, nullable) NSString *recordId;
/// The server times and the timing object of a final JSON (REST body or WebSocket final frame).
- (void)takeTimingFromRoot:(ygst_span)root;
/// INFO summary line and, at DEBUG, the detail line; once, and only when the platform was reached.
- (void)logForToken:(NSString *)tokenId;
@end

// ------------------------------------------------------------------ helpers

FOUNDATION_EXTERN NSString *YGSTRandomHex(NSUInteger bytes);
FOUNDATION_EXTERN void YGSTRandomBytes(unsigned char *out, size_t n);
FOUNDATION_EXTERN NSString *YGSTNewTokenId(void);
/// Compact JSON text with sorted keys, nil when obj is nil or not serialisable.
FOUNDATION_EXTERN NSString *_Nullable YGSTJSONText(id _Nullable obj);
/// Text of a customParams value: strings as-is, booleans true/false, numbers in plain form,
/// arrays and dictionaries as JSON, NSNull and nil give nil.
FOUNDATION_EXTERN NSString *_Nullable YGSTValueText(id _Nullable value);
FOUNDATION_EXTERN NSString *YGSTStringFromBuf(const ygst_buf *b);
FOUNDATION_EXTERN NSString *YGSTDocumentsPath(void);
FOUNDATION_EXTERN int64_t YGSTNowMs(void);
/// Monotonic milliseconds (system uptime), for timers and the retry deadline.
FOUNDATION_EXTERN int64_t YGSTMonotonicMs(void);
FOUNDATION_EXTERN int YGSTTimeZoneOffsetMinutes(void);
FOUNDATION_EXTERN NSString *YGSTErrorJSON(NSString *tokenId, int errId, NSString *_Nullable message,
                                          NSString *_Nullable appKey);
FOUNDATION_EXTERN NSString *YGSTCompatMessage(int errId);
/// 2.2.0: the 60003 text, "不支持的评测内核：<requested, trimmed>" or "不支持的评测内核：未指定".
FOUNDATION_EXTERN NSString *YGSTUnsupportedCoreTypeMessage(NSString *_Nullable requested);
FOUNDATION_EXTERN NSString *YGSTMaskedAppKey(NSString *_Nullable appKey);
FOUNDATION_EXTERN NSString *YGSTNonEmpty(NSString *_Nullable s);
/// Wraps 16-bit PCM in a WAV header.
FOUNDATION_EXTERN NSData *YGSTWavFromPCM(NSData *pcm, int sampleRate, int channels, int bits);

// ------------------------------------------------------------------ HTTP

@interface YGSTUploadResult : NSObject
@property (nonatomic) BOOL success;
@property (nonatomic, strong, nullable) NSData *body;
@property (nonatomic) NSRange resultRange;
@property (nonatomic, copy) NSString *recordId;
/// recordId token exactly as the platform sent it, nil when the response had none.
@property (nonatomic, copy, nullable) NSString *recordIdJSON;
@property (nonatomic, copy, nullable) NSString *audioUrl;
@property (nonatomic) int errId;
@property (nonatomic, copy) NSString *message;
@property (nonatomic) int attempts;
@property (nonatomic) BOOL replayed;
/// 2.2.0: the HTTP status of the last attempt, 0 without a response.
@property (nonatomic) int httpStatus;
@end

/// One logical evaluation call: attempts, backoff and autoRetry decided by the C controller.
@interface YGSTUploader : NSObject
- (instancetype)initWithURLSession:(NSURLSession *)session queue:(dispatch_queue_t)queue;
/// 2.1.1: receives the phases of the last attempt (NSURLSessionTaskMetrics), the Server-Timing
/// header and the timing object of the body.
@property (nonatomic, strong, nullable) YGSTTiming *timing;
- (void)startWithURL:(NSURL *)url
              appKey:(NSString *)appKey
           signature:(NSString *)signature
      idempotencyKey:(NSString *)idempotencyKey
                body:(NSData *)body
         contentType:(NSString *)contentType
      attemptTimeout:(NSTimeInterval)attemptTimeout
              policy:(const ygst_retry_policy *)policy
          completion:(void (^)(YGSTUploadResult *result))completion;
/// No completion is called after cancel.
- (void)cancel;
@end

/// A prepared POST /{coreType}: URL, signed form fields and multipart body.
@interface YGSTRequest : NSObject
@property (nonatomic, strong) NSURL *url;
@property (nonatomic, strong) NSData *body;
@property (nonatomic, copy) NSString *contentType;
@property (nonatomic, copy) NSString *signature;
/// timing: add the form field timing=1 (2.1.1, log level INFO or DEBUG) to the signed request; the
/// fields themselves, which also make the params of the result JSON, stay as they are.
+ (nullable instancetype)requestWithBaseURL:(NSString *)baseURL
                                   coreType:(NSString *)coreType
                                     fields:(const ygst_fields *)fields
                                     timing:(BOOL)timing
                                     secret:(NSString *)secret
                                      audio:(NSData *)audio
                                  extension:(NSString *)extension
                                      error:(NSString *_Nullable *_Nullable)error;
@end

/// Shared NSURLSession for all engines; ephemeral, no cookies, no cache. Its delegate collects the
/// task metrics of the REST attempts (2.1.1). NSURLSession asks for gzip by itself
/// (Accept-Encoding) and decodes the body before the completion handler sees it.
FOUNDATION_EXTERN NSURLSession *YGSTSharedURLSession(void);

/// YGST_LOCAL_TIMEOUT, YGST_LOCAL_TLS or YGST_LOCAL_NETWORK for a URL loading error.
FOUNDATION_EXTERN int YGSTLocalCodeForError(NSError *error);
/// The text of that error in the error JSON: "timeout: ..", "TLS certificate check failed: ..", "network error: ..".
FOUNDATION_EXTERN NSString *YGSTLocalMessageForError(NSError *error);

/// The outcome of one evaluation call, from the classification of its last attempt and the decision
/// of the call controller: the REST uploader and the realtime round both build it here.
FOUNDATION_EXTERN YGSTUploadResult *YGSTUploadResultMake(const ygst_classification *c, ygst_action_kind kind,
                                                         int errId, NSData *_Nullable body, const ygst_call *call,
                                                         BOOL replayed);

/// Builds the Shengtong result JSON from a successful upload.
FOUNDATION_EXTERN NSString *_Nullable YGSTResultJSON(YGSTUploadResult *result, NSString *tokenId,
                                                     NSString *appKey, NSString *userId, NSString *refText,
                                                     NSString *_Nullable paramsJSON);

// ------------------------------------------------------------------ v1 API calls (2.2.0)

/// One call of TtsClient or ReportClient: the shared session, a fresh timestamp and nonce on every
/// attempt, the same signature and Idempotency-Key (none for a GET), attempts and back-off decided by
/// the C call controller with the policy of the evaluations, answers classified by ygst_classify_api.
/// The result is built like an evaluation's (YGSTUploadResultMake): on success resultRange is the
/// "data" object of body, otherwise errId and message. Runs on queue, the completion as well.
@interface YGSTApiCall : NSObject
- (instancetype)initWithURLSession:(NSURLSession *)session queue:(dispatch_queue_t)queue;
- (void)startWithMethod:(NSString *)method
                    url:(NSURL *)url
                 appKey:(NSString *)appKey
              signature:(NSString *)signature
         idempotencyKey:(nullable NSString *)idempotencyKey
                   body:(nullable NSData *)body
            contentType:(nullable NSString *)contentType
         attemptTimeout:(NSTimeInterval)attemptTimeout
                 policy:(const ygst_retry_policy *)policy
              operation:(NSString *)operation
             completion:(void (^)(YGSTUploadResult *result))completion;
@end

// ------------------------------------------------------------------ realtime feedback (2.1.0)

/// One evaluation streamed over the compat WebSocket: since 2.1.1 every recording and 16 kHz mono
/// PCM feed by default (YuguCompat.streamUpload), with reading progress only when
/// KYTestConfig.realtime_feedback asks for it (2.1.0). The C controller ygst_rt decides
/// everything; this class carries its actions out with NSURLSessionWebSocketTask (iOS 13, macOS
/// 10.15) on the engine queue, which every method must be called on and every handler runs on.
/// NSURLSessionWebSocketTask negotiates permessage-deflate by itself where the system supports it.
@interface YGSTRealtimeRound : NSObject
/// NO before iOS 13: no NSURLSessionWebSocketTask, the evaluation goes over REST.
+ (BOOL)isAvailable;
/// realtimeFeedback: "realtime_feedback":true in the parameter frame, the platform sends progress.
/// timing: the phases of the round, written on the queue.
- (instancetype)initWithQueue:(dispatch_queue_t)queue
                      baseURL:(NSString *)baseURL
                     coreType:(NSString *)coreType
                       appKey:(NSString *)appKey
                       secret:(NSString *)secret
                      tokenId:(NSString *)tokenId
                       userId:(NSString *)userId
                      refText:(NSString *)refText
                       fields:(const ygst_fields *)fields
                       policy:(const ygst_retry_policy *)policy
               connectTimeout:(NSTimeInterval)connectTimeout
               attemptTimeout:(NSTimeInterval)attemptTimeout
             realtimeFeedback:(BOOL)realtimeFeedback
                       timing:(YGSTTiming *)timing;
/// An eof 0 JSON with the words read since the last one.
@property (nonatomic, copy, nullable) void (^progressHandler)(NSString *json);
/// The final result or the error, built like the REST uploader's.
@property (nonatomic, copy, nullable) void (^resultHandler)(YGSTUploadResult *result);
/// The round failed before end after stop was requested: evaluate over REST now.
@property (nonatomic, copy, nullable) void (^fallbackHandler)(void);
/// Opens the connection; audio captured meanwhile goes out once the platform has started.
- (void)start;
/// The round audio so far (raw PCM or the fed bytes); only its length grows.
- (void)audioAppended:(NSData *)audio;
/// stopEngine: sends end, or waits for "started" and sends it then. NO: the round failed before end,
/// evaluate over REST now (no handler is called).
- (BOOL)finish;
/// No handler is called afterwards. Idempotent.
- (void)cancel;
@end

// ------------------------------------------------------------------ recorder

/// AVAudioEngine capture converted to 16 kHz mono PCM16. All handlers run on the queue.
@interface YGSTRecorder : NSObject
- (instancetype)initWithQueue:(dispatch_queue_t)queue;
@property (nonatomic, copy, nullable) void (^pcmHandler)(NSData *pcm);
@property (nonatomic, copy, nullable) void (^interruptionHandler)(BOOL began);
@property (nonatomic, copy, nullable) void (^failureHandler)(NSString *message);
@property (nonatomic, readonly) BOOL running;
/// Call on the queue. Returns NO with a reason when the microphone cannot start.
- (BOOL)start:(NSString *_Nullable *_Nullable)reason;
- (void)stop;
@end

/// Microphone permission, then the completion on the queue.
FOUNDATION_EXTERN void YGSTRequestMicrophone(dispatch_queue_t queue, void (^completion)(BOOL granted));
/// PlayAndRecord with speaker output, unless the app manages AVAudioSession itself.
FOUNDATION_EXTERN BOOL YGSTActivateAudioSession(NSString *_Nullable *_Nullable reason);

NS_ASSUME_NONNULL_END
