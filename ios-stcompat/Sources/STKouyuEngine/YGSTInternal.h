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

@interface YGSTSettings : NSObject
+ (instancetype)shared;
@property (atomic, copy, nullable) NSString *baseURLOverride;
@property (atomic) NSInteger logLevelOverride; // NSNotFound when not set
@property (atomic, copy, nullable) void (^logHandler)(NSInteger level, NSString *message);
@property (atomic) NSInteger maxRetries;
@property (atomic) NSTimeInterval totalTimeout;
/// Base URL derived from the last initEngine (KYStartEngineConfig.server).
@property (atomic, copy) NSString *configuredBaseURL;
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
FOUNDATION_EXTERN NSString *YGSTStringFromBuf(ygst_buf *b);
FOUNDATION_EXTERN NSString *YGSTDocumentsPath(void);
FOUNDATION_EXTERN int64_t YGSTNowMs(void);
/// Monotonic milliseconds (system uptime), for timers and the retry deadline.
FOUNDATION_EXTERN int64_t YGSTMonotonicMs(void);
FOUNDATION_EXTERN int YGSTTimeZoneOffsetMinutes(void);
FOUNDATION_EXTERN NSString *YGSTErrorJSON(NSString *tokenId, int errId, NSString *_Nullable message,
                                          NSString *_Nullable appKey);
FOUNDATION_EXTERN NSString *YGSTCompatMessage(int errId);
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
@end

/// One logical evaluation call: attempts, backoff and autoRetry decided by the C controller.
@interface YGSTUploader : NSObject
- (instancetype)initWithURLSession:(NSURLSession *)session queue:(dispatch_queue_t)queue;
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
+ (nullable instancetype)requestWithBaseURL:(NSString *)baseURL
                                   coreType:(NSString *)coreType
                                     fields:(const ygst_fields *)fields
                                     secret:(NSString *)secret
                                      audio:(NSData *)audio
                                  extension:(NSString *)extension
                                      error:(NSString *_Nullable *_Nullable)error;
@end

/// Shared NSURLSession for all engines; ephemeral, no cookies, no cache.
FOUNDATION_EXTERN NSURLSession *YGSTSharedURLSession(void);

/// Builds the Shengtong result JSON from a successful upload.
FOUNDATION_EXTERN NSString *_Nullable YGSTResultJSON(YGSTUploadResult *result, NSString *tokenId,
                                                     NSString *appKey, NSString *userId, NSString *refText,
                                                     NSString *_Nullable paramsJSON);

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
