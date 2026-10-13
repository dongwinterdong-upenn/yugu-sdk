/*
 * Stub for the Linux syntax check of the Objective-C layer. Not an Apple header.
 *
 * Declares only the Foundation API that STKouyuEngine uses, with the selectors, parameter
 * types and return types of the Apple SDK, so that libclang can type-check the .m files.
 * Nullability annotations are left out; they do not change Objective-C type checking.
 */
#ifndef YGST_STUB_FOUNDATION_H
#define YGST_STUB_FOUNDATION_H

#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdbool.h>
#include <limits.h>
#include <TargetConditionals.h>
#include <CoreFoundation/CoreFoundation.h>
#include <CoreGraphics/CoreGraphics.h>
#include <dispatch/dispatch.h>

#if defined(__arm64__) || defined(__aarch64__)
typedef bool BOOL;
#else
typedef signed char BOOL;
#endif
#define YES __objc_yes
#define NO __objc_no
#define nil ((void *)0)
#define Nil ((void *)0)

typedef long NSInteger;
typedef unsigned long NSUInteger;
#define NSIntegerMax LONG_MAX
static const NSInteger NSNotFound = NSIntegerMax;
typedef double NSTimeInterval;

#define FOUNDATION_EXPORT extern
#define FOUNDATION_EXTERN extern
#define NS_FORMAT_FUNCTION(F, A) __attribute__((format(__NSString__, F, A)))
#define NS_NO_TAIL_CALL __attribute__((not_tail_called))
#define NS_RETURNS_INNER_POINTER __attribute__((objc_returns_inner_pointer))
#define NS_ASSUME_NONNULL_BEGIN _Pragma("clang assume_nonnull begin")
#define NS_ASSUME_NONNULL_END _Pragma("clang assume_nonnull end")
#define NS_VALID_UNTIL_END_OF_SCOPE __attribute__((objc_precise_lifetime))
#define NS_DESIGNATED_INITIALIZER __attribute__((objc_designated_initializer))
#define NS_ENUM(_type, _name) enum _name : _type _name; enum _name : _type
#define NS_OPTIONS(_type, _name) enum _name : _type _name; enum _name : _type
/* API_AVAILABLE(macos(10.15), ios(13.0), ...) as availability attributes, like the SDK, so that a
 * use of an iOS 13 API outside @available or an API_AVAILABLE context warns on the iOS 12 target
 * (-Wunguarded-availability-new). One to four platforms. */
#define YGST_STUB_AV_ios(v) __attribute__((availability(ios, introduced = v)))
#define YGST_STUB_AV_macos(v) __attribute__((availability(macos, introduced = v)))
#define YGST_STUB_AV_tvos(v) __attribute__((availability(tvos, introduced = v)))
#define YGST_STUB_AV_watchos(v) __attribute__((availability(watchos, introduced = v)))
#define YGST_STUB_AV1(a) YGST_STUB_AV_##a
#define YGST_STUB_AV2(a, b) YGST_STUB_AV1(a) YGST_STUB_AV1(b)
#define YGST_STUB_AV3(a, b, c) YGST_STUB_AV1(a) YGST_STUB_AV2(b, c)
#define YGST_STUB_AV4(a, b, c, d) YGST_STUB_AV1(a) YGST_STUB_AV3(b, c, d)
#define YGST_STUB_AV_PICK(_1, _2, _3, _4, name, ...) name
#define API_AVAILABLE(...) \
    YGST_STUB_AV_PICK(__VA_ARGS__, YGST_STUB_AV4, YGST_STUB_AV3, YGST_STUB_AV2, YGST_STUB_AV1, unused)(__VA_ARGS__)

typedef struct _NSRange {
    NSUInteger location;
    NSUInteger length;
} NSRange;

static inline NSRange NSMakeRange(NSUInteger loc, NSUInteger len) {
    NSRange r;
    r.location = loc;
    r.length = len;
    return r;
}

static inline NSUInteger NSMaxRange(NSRange range) {
    return range.location + range.length;
}

#define MIN(a, b) (((a) < (b)) ? (a) : (b))
#define MAX(a, b) (((a) > (b)) ? (a) : (b))

typedef NS_ENUM(NSInteger, NSComparisonResult) {
    NSOrderedAscending = -1L,
    NSOrderedSame,
    NSOrderedDescending
};

@class NSString, NSArray, NSDictionary, NSData, NSError, NSURL, NSNumber, NSDate, NSCharacterSet, NSOperationQueue,
    NSNotification, NSURLSessionUploadTask, NSURLResponse, NSHTTPURLResponse, NSUUID, NSRunLoop;

@protocol NSObject
- (BOOL)isEqual:(id)object;
@property (readonly) NSUInteger hash;
- (Class)class;
- (instancetype)self;
- (BOOL)isKindOfClass:(Class)aClass;
- (BOOL)respondsToSelector:(SEL)aSelector;
@property (readonly, copy) NSString *description;
@end

__attribute__((objc_root_class))
@interface NSObject <NSObject> {
    Class isa;
}
+ (instancetype)alloc;
+ (instancetype)new;
- (instancetype)init;
- (void)dealloc;
- (id)copy;
- (id)mutableCopy;
+ (Class)class;
@end

typedef struct _NSZone NSZone;

@protocol NSCopying
- (id)copyWithZone:(NSZone *)zone;
@end

@protocol NSMutableCopying
- (id)mutableCopyWithZone:(NSZone *)zone;
@end

typedef struct {
    unsigned long state;
    id __unsafe_unretained *itemsPtr;
    unsigned long *mutationsPtr;
    unsigned long extra[5];
} NSFastEnumerationState;

@protocol NSFastEnumeration
- (NSUInteger)countByEnumeratingWithState:(NSFastEnumerationState *)state
                                  objects:(id __unsafe_unretained[])buffer
                                    count:(NSUInteger)len;
@end

// ------------------------------------------------------------------ strings

typedef NSUInteger NSStringEncoding;
enum {
    NSUTF8StringEncoding = 4,
};

typedef NS_OPTIONS(NSUInteger, NSStringCompareOptions) {
    NSCaseInsensitiveSearch = 1,
    NSLiteralSearch = 2,
};

@interface NSString : NSObject <NSCopying, NSMutableCopying>
@property (readonly) NSUInteger length;
@property (readonly) const char *UTF8String NS_RETURNS_INNER_POINTER;
+ (instancetype)string;
+ (instancetype)stringWithUTF8String:(const char *)nullTerminatedCString;
+ (instancetype)stringWithFormat:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (instancetype)stringWithContentsOfFile:(NSString *)path encoding:(NSStringEncoding)enc error:(NSError **)error;
- (instancetype)initWithFormat:(NSString *)format arguments:(va_list)argList NS_FORMAT_FUNCTION(1, 0);
- (instancetype)initWithBytes:(const void *)bytes length:(NSUInteger)len encoding:(NSStringEncoding)encoding;
- (instancetype)initWithData:(NSData *)data encoding:(NSStringEncoding)encoding;
- (NSData *)dataUsingEncoding:(NSStringEncoding)encoding;
- (NSString *)stringByTrimmingCharactersInSet:(NSCharacterSet *)set;
- (NSString *)stringByAppendingString:(NSString *)aString;
- (NSString *)stringByAppendingPathComponent:(NSString *)str;
- (NSString *)stringByAppendingPathExtension:(NSString *)str;
@property (readonly, copy) NSString *stringByDeletingLastPathComponent;
@property (readonly, copy) NSString *stringByDeletingPathExtension;
@property (readonly, copy) NSString *pathExtension;
@property (readonly, copy) NSString *lowercaseString;
- (BOOL)isEqualToString:(NSString *)aString;
- (NSComparisonResult)caseInsensitiveCompare:(NSString *)string;
- (BOOL)hasPrefix:(NSString *)str;
- (BOOL)hasSuffix:(NSString *)str;
- (BOOL)containsString:(NSString *)str;
- (NSString *)substringToIndex:(NSUInteger)to;
- (NSRange)rangeOfString:(NSString *)searchString options:(NSStringCompareOptions)mask range:(NSRange)rangeOfReceiverToSearch;
- (NSRange)rangeOfCharacterFromSet:(NSCharacterSet *)searchSet;
@property (readonly) double doubleValue;
@property (readonly) NSInteger integerValue;
@property (readonly) long long longLongValue;
@property (readonly) BOOL boolValue;
@end

@interface NSMutableString : NSString
- (void)appendString:(NSString *)aString;
@end

@interface NSCharacterSet : NSObject <NSCopying>
@property (class, readonly, copy) NSCharacterSet *whitespaceAndNewlineCharacterSet;
@property (class, readonly, copy) NSCharacterSet *decimalDigitCharacterSet;
@property (readonly, copy) NSCharacterSet *invertedSet;
@end

// ------------------------------------------------------------------ numbers and values

@interface NSValue : NSObject <NSCopying>
@end

@interface NSNumber : NSValue
+ (NSNumber *)numberWithChar:(char)value;
+ (NSNumber *)numberWithInt:(int)value;
+ (NSNumber *)numberWithUnsignedInt:(unsigned int)value;
+ (NSNumber *)numberWithLong:(long)value;
+ (NSNumber *)numberWithUnsignedLong:(unsigned long)value;
+ (NSNumber *)numberWithLongLong:(long long)value;
+ (NSNumber *)numberWithUnsignedLongLong:(unsigned long long)value;
+ (NSNumber *)numberWithFloat:(float)value;
+ (NSNumber *)numberWithDouble:(double)value;
+ (NSNumber *)numberWithBool:(BOOL)value;
+ (NSNumber *)numberWithInteger:(NSInteger)value;
+ (NSNumber *)numberWithUnsignedInteger:(NSUInteger)value;
@property (readonly) BOOL boolValue;
@property (readonly) int intValue;
@property (readonly) long long longLongValue;
@property (readonly) unsigned long long unsignedLongLongValue;
@property (readonly) double doubleValue;
@property (readonly) NSInteger integerValue;
@property (readonly) NSUInteger unsignedIntegerValue;
@property (readonly, copy) NSString *stringValue;
@property (readonly) const char *objCType NS_RETURNS_INNER_POINTER;
@end

@interface NSNull : NSObject <NSCopying>
+ (NSNull *)null;
@end

// ------------------------------------------------------------------ collections

@interface NSArray<__covariant ObjectType> : NSObject <NSCopying, NSMutableCopying, NSFastEnumeration>
@property (readonly) NSUInteger count;
@property (readonly) ObjectType firstObject;
@property (readonly) ObjectType lastObject;
+ (instancetype)array;
+ (instancetype)arrayWithObjects:(const ObjectType __unsafe_unretained[])objects count:(NSUInteger)cnt;
- (ObjectType)objectAtIndexedSubscript:(NSUInteger)idx;
@end

@interface NSMutableArray<ObjectType> : NSArray <ObjectType>
- (void)addObject:(ObjectType)anObject;
@end

@interface NSDictionary<__covariant KeyType, __covariant ObjectType> : NSObject <NSCopying, NSMutableCopying,
                                                                                 NSFastEnumeration>
@property (readonly) NSUInteger count;
+ (instancetype)dictionaryWithObjects:(const ObjectType __unsafe_unretained[])objects
                              forKeys:(const KeyType<NSCopying> __unsafe_unretained[])keys
                                count:(NSUInteger)cnt;
- (ObjectType)objectForKeyedSubscript:(KeyType)key;
- (ObjectType)objectForKey:(KeyType)aKey;
@end

@interface NSMutableDictionary<KeyType, ObjectType> : NSDictionary <KeyType, ObjectType>
+ (instancetype)dictionary;
- (void)setObject:(ObjectType)obj forKeyedSubscript:(KeyType<NSCopying>)key;
- (void)removeObjectForKey:(KeyType)aKey;
@end

// ------------------------------------------------------------------ attributed strings

typedef NSString *NSAttributedStringKey;

@interface NSAttributedString : NSObject <NSCopying, NSMutableCopying>
@property (readonly, copy) NSString *string;
- (instancetype)initWithString:(NSString *)str attributes:(NSDictionary<NSAttributedStringKey, id> *)attrs;
@end

@interface NSMutableAttributedString : NSAttributedString
- (void)addAttribute:(NSAttributedStringKey)name value:(id)value range:(NSRange)range;
@end

// ------------------------------------------------------------------ data

typedef NS_OPTIONS(NSUInteger, NSDataReadingOptions) {
    NSDataReadingMappedIfSafe = 1UL << 0,
    NSDataReadingUncached = 1UL << 1,
};

typedef NS_OPTIONS(NSUInteger, NSDataWritingOptions) {
    NSDataWritingAtomic = 1UL << 0,
};

@interface NSData : NSObject <NSCopying, NSMutableCopying>
@property (readonly) NSUInteger length;
@property (readonly) const void *bytes NS_RETURNS_INNER_POINTER;
+ (instancetype)data;
+ (instancetype)dataWithBytes:(const void *)bytes length:(NSUInteger)length;
+ (instancetype)dataWithContentsOfFile:(NSString *)path options:(NSDataReadingOptions)readOptionsMask error:(NSError **)errorPtr;
+ (instancetype)dataWithContentsOfFile:(NSString *)path;
+ (instancetype)dataWithContentsOfURL:(NSURL *)url options:(NSDataReadingOptions)readOptionsMask error:(NSError **)errorPtr;
- (BOOL)writeToFile:(NSString *)path options:(NSDataWritingOptions)writeOptionsMask error:(NSError **)errorPtr;
- (NSData *)subdataWithRange:(NSRange)range;
@end

@interface NSMutableData : NSData
+ (instancetype)dataWithCapacity:(NSUInteger)aNumItems;
@property (readonly) void *mutableBytes NS_RETURNS_INNER_POINTER;
@property NSUInteger length;
- (void)appendBytes:(const void *)bytes length:(NSUInteger)length;
- (void)appendData:(NSData *)other;
@end

// ------------------------------------------------------------------ errors and exceptions

typedef NSString *NSErrorDomain;

@interface NSError : NSObject <NSCopying>
@property (readonly, copy) NSErrorDomain domain;
@property (readonly) NSInteger code;
@property (readonly, copy) NSString *localizedDescription;
@end

@interface NSException : NSObject <NSCopying>
@property (readonly, copy) NSString *name;
@property (readonly, copy) NSString *reason;
@end

FOUNDATION_EXPORT NSErrorDomain const NSURLErrorDomain;
enum {
    NSURLErrorUnknown = -1,
    NSURLErrorCancelled = -999,
    NSURLErrorTimedOut = -1001,
    NSURLErrorCannotConnectToHost = -1004,
    NSURLErrorNetworkConnectionLost = -1005,
    NSURLErrorNotConnectedToInternet = -1009,
    NSURLErrorAppTransportSecurityRequiresSecureConnection = -1022,
    NSURLErrorSecureConnectionFailed = -1200,
    NSURLErrorServerCertificateHasBadDate = -1201,
    NSURLErrorServerCertificateUntrusted = -1202,
    NSURLErrorServerCertificateHasUnknownRoot = -1203,
    NSURLErrorServerCertificateNotYetValid = -1204,
    NSURLErrorClientCertificateRejected = -1205,
    NSURLErrorClientCertificateRequired = -1206,
};

// ------------------------------------------------------------------ system

@interface NSDate : NSObject <NSCopying>
+ (instancetype)date;
+ (instancetype)dateWithTimeIntervalSinceNow:(NSTimeInterval)secs;
@property (readonly) NSTimeInterval timeIntervalSince1970;
@property (readonly) NSTimeInterval timeIntervalSinceNow;
- (NSTimeInterval)timeIntervalSinceDate:(NSDate *)anotherDate;
@end

@interface NSTimeZone : NSObject <NSCopying>
@property (class, readonly, copy) NSTimeZone *localTimeZone;
- (NSInteger)secondsFromGMTForDate:(NSDate *)aDate;
@end

@interface NSProcessInfo : NSObject
@property (class, readonly, strong) NSProcessInfo *processInfo;
@property (readonly) NSTimeInterval systemUptime;
@property (readonly, copy) NSDictionary<NSString *, NSString *> *environment;
@end

@interface NSThread : NSObject
@property (class, readonly) BOOL isMainThread;
@end

@interface NSUUID : NSObject <NSCopying>
+ (instancetype)UUID;
@property (readonly, copy) NSString *UUIDString;
@end

@interface NSUserDefaults : NSObject
@property (class, readonly, strong) NSUserDefaults *standardUserDefaults;
- (NSString *)stringForKey:(NSString *)defaultName;
- (void)setObject:(id)value forKey:(NSString *)defaultName;
@end

@interface NSRunLoop : NSObject
@property (class, readonly, strong) NSRunLoop *mainRunLoop;
- (void)runUntilDate:(NSDate *)limitDate;
@end

FOUNDATION_EXPORT void NSLog(NSString *format, ...) NS_FORMAT_FUNCTION(1, 2) NS_NO_TAIL_CALL;

typedef NS_ENUM(NSUInteger, NSSearchPathDirectory) {
    NSDocumentDirectory = 9,
};

typedef NS_OPTIONS(NSUInteger, NSSearchPathDomainMask) {
    NSUserDomainMask = 1,
};

FOUNDATION_EXPORT NSArray<NSString *> *NSSearchPathForDirectoriesInDomains(NSSearchPathDirectory directory,
                                                                          NSSearchPathDomainMask domainMask,
                                                                          BOOL expandTilde);
FOUNDATION_EXPORT NSString *NSTemporaryDirectory(void);

typedef NSString *NSFileAttributeKey;

@interface NSFileManager : NSObject
@property (class, readonly, strong) NSFileManager *defaultManager;
- (BOOL)fileExistsAtPath:(NSString *)path;
- (BOOL)createDirectoryAtPath:(NSString *)path
    withIntermediateDirectories:(BOOL)createIntermediates
                     attributes:(NSDictionary<NSFileAttributeKey, id> *)attributes
                          error:(NSError **)error;
- (BOOL)createFileAtPath:(NSString *)path contents:(NSData *)data attributes:(NSDictionary<NSFileAttributeKey, id> *)attr;
- (BOOL)removeItemAtPath:(NSString *)path error:(NSError **)error;
@end

@interface NSFileHandle : NSObject
+ (instancetype)fileHandleForWritingAtPath:(NSString *)path;
- (unsigned long long)seekToEndOfFile;
- (void)writeData:(NSData *)data;
- (void)closeFile;
@end

// ------------------------------------------------------------------ JSON

typedef NS_OPTIONS(NSUInteger, NSJSONReadingOptions) {
    NSJSONReadingMutableContainers = (1UL << 0),
};

typedef NS_OPTIONS(NSUInteger, NSJSONWritingOptions) {
    NSJSONWritingPrettyPrinted = (1UL << 0),
    NSJSONWritingSortedKeys = (1UL << 1),
};

@interface NSJSONSerialization : NSObject
+ (BOOL)isValidJSONObject:(id)obj;
+ (NSData *)dataWithJSONObject:(id)obj options:(NSJSONWritingOptions)opt error:(NSError **)error;
+ (id)JSONObjectWithData:(NSData *)data options:(NSJSONReadingOptions)opt error:(NSError **)error;
@end

// ------------------------------------------------------------------ notifications

typedef NSString *NSNotificationName;

@interface NSNotification : NSObject <NSCopying>
@property (readonly, copy) NSNotificationName name;
@property (readonly, retain) id object;
@property (readonly, copy) NSDictionary *userInfo;
@end

@interface NSOperationQueue : NSObject
@end

@interface NSNotificationCenter : NSObject
@property (class, readonly, strong) NSNotificationCenter *defaultCenter;
- (id<NSObject>)addObserverForName:(NSNotificationName)name
                            object:(id)obj
                             queue:(NSOperationQueue *)queue
                        usingBlock:(void (^)(NSNotification *note))block;
- (void)removeObserver:(id)observer;
@end

// ------------------------------------------------------------------ URL loading

@interface NSURL : NSObject <NSCopying>
+ (instancetype)URLWithString:(NSString *)URLString;
+ (NSURL *)fileURLWithPath:(NSString *)path;
@property (readonly, copy) NSString *absoluteString;
@property (readonly, copy) NSString *path;
@end

typedef NS_ENUM(NSUInteger, NSURLRequestCachePolicy) {
    NSURLRequestUseProtocolCachePolicy = 0,
    NSURLRequestReloadIgnoringLocalCacheData = 1,
};

typedef NS_ENUM(NSUInteger, NSHTTPCookieAcceptPolicy) {
    NSHTTPCookieAcceptPolicyAlways,
    NSHTTPCookieAcceptPolicyNever,
    NSHTTPCookieAcceptPolicyOnlyFromMainDocumentDomain
};

@interface NSURLCache : NSObject
@end

@interface NSURLRequest : NSObject <NSCopying, NSMutableCopying>
@end

@interface NSMutableURLRequest : NSURLRequest
+ (instancetype)requestWithURL:(NSURL *)URL
                   cachePolicy:(NSURLRequestCachePolicy)cachePolicy
               timeoutInterval:(NSTimeInterval)timeoutInterval;
@property (copy) NSString *HTTPMethod;
@property (copy) NSData *HTTPBody;
@property NSTimeInterval timeoutInterval;
- (void)setValue:(NSString *)value forHTTPHeaderField:(NSString *)field;
@end

@interface NSURLResponse : NSObject <NSCopying>
@end

@interface NSHTTPURLResponse : NSURLResponse
@property (readonly) NSInteger statusCode;
@property (readonly, copy) NSDictionary *allHeaderFields;
@end

@interface NSURLSessionTask : NSObject <NSCopying>
@property (readonly) NSUInteger taskIdentifier;
@property (readonly, copy) NSURLResponse *response;
- (void)resume;
- (void)cancel;
@end

@interface NSURLSessionDataTask : NSURLSessionTask
@end

@interface NSURLSessionUploadTask : NSURLSessionDataTask
@end

@interface NSURLSessionConfiguration : NSObject <NSCopying>
@property (class, readonly, strong) NSURLSessionConfiguration *ephemeralSessionConfiguration;
@property NSURLRequestCachePolicy requestCachePolicy;
@property NSTimeInterval timeoutIntervalForRequest;
@property NSTimeInterval timeoutIntervalForResource;
@property BOOL HTTPShouldSetCookies;
@property NSHTTPCookieAcceptPolicy HTTPCookieAcceptPolicy;
@property (copy) NSDictionary *HTTPAdditionalHeaders;
@property (retain) NSURLCache *URLCache;
@end

/* WebSocket, iOS 13 and macOS 10.15 (Foundation/NSURLSession.h) */

typedef NS_ENUM(NSInteger, NSURLSessionWebSocketMessageType) {
    NSURLSessionWebSocketMessageTypeData = 0,
    NSURLSessionWebSocketMessageTypeString = 1,
} API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));

API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0))
@interface NSURLSessionWebSocketMessage : NSObject
- (instancetype)initWithData:(NSData *)data NS_DESIGNATED_INITIALIZER;
- (instancetype)initWithString:(NSString *)string NS_DESIGNATED_INITIALIZER;
@property (readonly) NSURLSessionWebSocketMessageType type;
@property (readonly, copy) NSData *data;
@property (readonly, copy) NSString *string;
@end

typedef NS_ENUM(NSInteger, NSURLSessionWebSocketCloseCode) {
    NSURLSessionWebSocketCloseCodeInvalid = 0,
    NSURLSessionWebSocketCloseCodeNormalClosure = 1000,
    NSURLSessionWebSocketCloseCodeGoingAway = 1001,
    NSURLSessionWebSocketCloseCodeProtocolError = 1002,
    NSURLSessionWebSocketCloseCodeUnsupportedData = 1003,
    NSURLSessionWebSocketCloseCodeNoStatusReceived = 1005,
    NSURLSessionWebSocketCloseCodeAbnormalClosure = 1006,
    NSURLSessionWebSocketCloseCodeInvalidFramePayloadData = 1007,
    NSURLSessionWebSocketCloseCodePolicyViolation = 1008,
    NSURLSessionWebSocketCloseCodeMessageTooBig = 1009,
    NSURLSessionWebSocketCloseCodeMandatoryExtensionMissing = 1010,
    NSURLSessionWebSocketCloseCodeInternalServerError = 1011,
    NSURLSessionWebSocketCloseCodeTLSHandshakeFailure = 1015,
} API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));

API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0))
@interface NSURLSessionWebSocketTask : NSURLSessionTask
- (void)sendMessage:(NSURLSessionWebSocketMessage *)message completionHandler:(void (^)(NSError *error))completionHandler;
- (void)receiveMessageWithCompletionHandler:(void (^)(NSURLSessionWebSocketMessage *message,
                                                      NSError *error))completionHandler;
- (void)sendPingWithPongReceiveHandler:(void (^)(NSError *error))pongReceiveHandler;
- (void)cancelWithCloseCode:(NSURLSessionWebSocketCloseCode)closeCode reason:(NSData *)reason;
@property NSInteger maximumMessageSize;
@property (readonly) NSURLSessionWebSocketCloseCode closeCode;
@property (readonly, copy) NSData *closeReason;
@end

/* Task metrics, iOS 10 and macOS 10.12; the byte counts iOS 13 and macOS 10.15 (Foundation/NSURLSession.h) */

API_AVAILABLE(macos(10.12), ios(10.0), watchos(3.0), tvos(10.0))
@interface NSURLSessionTaskTransactionMetrics : NSObject
@property (copy, readonly) NSURLRequest *request;
@property (copy, readonly) NSURLResponse *response;
@property (copy, readonly) NSDate *fetchStartDate;
@property (copy, readonly) NSDate *requestStartDate;
@property (copy, readonly) NSDate *requestEndDate;
@property (copy, readonly) NSDate *responseStartDate;
@property (copy, readonly) NSDate *responseEndDate;
@property (readonly) int64_t countOfRequestBodyBytesSent API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));
@property (readonly) int64_t countOfResponseBodyBytesReceived API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));
@property (readonly) int64_t countOfResponseBodyBytesAfterDecoding
    API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));
@end

API_AVAILABLE(macos(10.12), ios(10.0), watchos(3.0), tvos(10.0))
@interface NSURLSessionTaskMetrics : NSObject
@property (copy, readonly) NSArray<NSURLSessionTaskTransactionMetrics *> *transactionMetrics;
@property (assign, readonly) NSUInteger redirectCount;
@end

@class NSURLSession;

@protocol NSURLSessionDelegate <NSObject>
@optional
- (void)URLSession:(NSURLSession *)session didBecomeInvalidWithError:(NSError *)error;
@end

@protocol NSURLSessionTaskDelegate <NSURLSessionDelegate>
@optional
- (void)URLSession:(NSURLSession *)session
                          task:(NSURLSessionTask *)task
    didFinishCollectingMetrics:(NSURLSessionTaskMetrics *)metrics
    API_AVAILABLE(macos(10.12), ios(10.0), watchos(3.0), tvos(10.0));
@end

@interface NSURLSession : NSObject
@property (class, readonly, strong) NSURLSession *sharedSession;
+ (NSURLSession *)sessionWithConfiguration:(NSURLSessionConfiguration *)configuration;
+ (NSURLSession *)sessionWithConfiguration:(NSURLSessionConfiguration *)configuration
                                  delegate:(id<NSURLSessionDelegate>)delegate
                             delegateQueue:(NSOperationQueue *)queue;
- (NSURLSessionDataTask *)dataTaskWithRequest:(NSURLRequest *)request
                            completionHandler:(void (^)(NSData *data, NSURLResponse *response,
                                                        NSError *error))completionHandler;
- (NSURLSessionUploadTask *)uploadTaskWithRequest:(NSURLRequest *)request
                                         fromData:(NSData *)bodyData
                                completionHandler:(void (^)(NSData *data, NSURLResponse *response,
                                                            NSError *error))completionHandler;
- (NSURLSessionWebSocketTask *)webSocketTaskWithURL:(NSURL *)url
    API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));
- (NSURLSessionWebSocketTask *)webSocketTaskWithRequest:(NSURLRequest *)request
    API_AVAILABLE(macos(10.15), ios(13.0), watchos(6.0), tvos(13.0));
@end

static inline CFTypeRef CFBridgingRetain(id X) {
    return (__bridge_retained CFTypeRef)X;
}

static inline id CFBridgingRelease(CFTypeRef X) {
    return (__bridge_transfer id)X;
}

#endif
