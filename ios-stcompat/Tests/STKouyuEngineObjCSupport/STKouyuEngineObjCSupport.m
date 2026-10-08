// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// Objective-C checks called from Tests/STKouyuEngineTests. See the header for why they are here.

#import "include/STKouyuEngineObjCSupport.h"

#import <STKouyuEngine/KYTestEngine.h>
#import <STKouyuEngine/STKouyuEngine.h>
#import <STKouyuEngine/YuguCompat.h>
#import <STKouyuEngine/skegn.h>
@import STKouyuEngine;

#include <stdlib.h>
#include <string.h>

#define EXPECT(cond, msg)                                                                     \
    do {                                                                                      \
        if (!(cond)) return [NSString stringWithFormat:@"%s:%d %@", __FILE__, __LINE__, (msg)]; \
    } while (0)

@interface YGSTSupportDelegate : NSObject <KYTestEngineDelegate>
@property (nonatomic, strong) NSMutableArray<NSString *> *scores;
@end

@implementation YGSTSupportDelegate
- (instancetype)init {
    if ((self = [super init])) _scores = [NSMutableArray array];
    return self;
}
- (void)kyTestEngineDidScore:(NSString *)str {
    [self.scores addObject:str];
}
@end

// The semaphore is owned by a strong local of each check; the plain C struct only points at it so
// that memset, calloc and free stay valid under ARC.
typedef struct {
    __unsafe_unretained dispatch_semaphore_t done;
    char json[65536];
    int type;
} YGSTCallbackBox;

static int YGSTSupportCallback(const void *usrdata, const char *id, int type, const void *message, int size) {
    YGSTCallbackBox *box = (YGSTCallbackBox *)usrdata;
    (void)id;
    box->type = type;
    if (size > 0 && (size_t)size < sizeof box->json) {
        memcpy(box->json, message, (size_t)size);
        box->json[size] = 0;
    }
    dispatch_semaphore_signal(box->done);
    return 0;
}

static NSDictionary *YGSTParse(const char *json) {
    NSData *d = [NSData dataWithBytes:json length:strlen(json)];
    id o = [NSJSONSerialization JSONObjectWithData:d options:0 error:NULL];
    return [o isKindOfClass:[NSDictionary class]] ? o : @{};
}

/// Waits on the main run loop so that blocks dispatched to the main queue can run.
static BOOL YGSTSpin(BOOL (^done)(void), NSTimeInterval timeout) {
    NSDate *limit = [NSDate dateWithTimeIntervalSinceNow:timeout];
    while (!done() && [limit timeIntervalSinceNow] > 0) {
        [[NSRunLoop mainRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    }
    return done();
}

NSString *YGSTCheckConstantsAndLog(void) {
    EXPECT([KY_CloudServer_Release isEqualToString:@"ws://api.stkouyu.com:8080"], @"KY_CloudServer_Release");
    EXPECT([KY_CloudServer_Gray isEqualToString:@"ws://gray.stkouyu.com:8090"], @"KY_CloudServer_Gray");
    EXPECT(KYLOG_ERROR == 0 && KYLOG_WARN == 1 && KYLOG_INFO == 2 && KYLOG_DEBUG == 3, @"KYLOG values");
    EXPECT([KYEngineCloud isEqualToString:@"cloud"] && [KYEngineNative isEqualToString:@"native"], @"engine names");
    EXPECT(STKouyuEngineVersionNumber == 2.0, @"version number");
    EXPECT(strstr((const char *)STKouyuEngineVersionString, "STKouyuEngine") != NULL, @"version string");
    EXPECT([YuguCompat.sdkVersion isEqualToString:@"2.0.0"], @"YuguCompat.sdkVersion");
    EXPECT(KY_CloudEngine == 0 && KY_MultiEngine == 2 && KYTestType_Wordspell == 22, @"enum values");
    KYLog(KYLOG_INFO, @"KYLog from the Objective-C checks %d", 1);
    return nil;
}

NSString *YGSTCheckDelegateAndProvision(NSTimeInterval timeout) {
    KYTestEngine *engine = [[KYTestEngine alloc] init];
    YGSTSupportDelegate *d = [[YGSTSupportDelegate alloc] init];
    __block NSString *provision = nil;
    engine.delegate = d;
    EXPECT([engine updateProvision], @"updateProvision");
    EXPECT([engine updateProvision:@"ak" secretkey:@"sk"], @"updateProvision:secretkey:");
    EXPECT([engine inquireProvision:^(NSString *message) {
               provision = message;
           }],
           @"inquireProvision:");
    EXPECT(YGSTSpin(^BOOL {
               return provision != nil;
           },
                    timeout),
           @"inquireProvision block on the main queue");
    EXPECT([provision containsString:@"\"provision\":\"cloud\""], provision);
    EXPECT(![engine getEngineStatus], @"idle engine status");
    EXPECT([engine getLastRecordPath] != nil, @"getLastRecordPath never nil");
    [engine deleteEngine];
    [engine deleteEngine];
    return nil;
}

NSString *YGSTCheckSkegnLocalError(NSTimeInterval timeout) {
    // heap allocated and freed only on success: an early return must not leave the callback
    // writing into a dead stack frame
    YGSTCallbackBox *box = calloc(1, sizeof(YGSTCallbackBox));
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    char token[64] = {0};
    struct skegn *engine = skegn_new("{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\","
                                     "\"cloud\":{\"server\":\"http://127.0.0.1:9\"}}");
    EXPECT(box != NULL && engine != NULL, @"skegn_new");
    box->done = sem;
    EXPECT(skegn_start(engine, "{\"request\":{\"coreType\":\"open.eval\",\"refText\":\"x\"}}", token,
                       YGSTSupportCallback, box) == 0,
           @"skegn_start");
    EXPECT(strlen(token) == 32, @"tokenId length");
    EXPECT(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC))) == 0,
           @"callback for an unsupported coreType");
    EXPECT(box->type == SKEGN_MESSAGE_TYPE_JSON, @"message type");
    EXPECT([YGSTParse(box->json)[@"errId"] isEqual:@60003], [NSString stringWithUTF8String:box->json]);
    EXPECT(skegn_feed(engine, "ab", 2) == -1, @"feed after the local answer");
    EXPECT(skegn_delete(engine) == 0, @"skegn_delete");
    EXPECT(skegn_start(NULL, "{}", token, YGSTSupportCallback, box) == -1, @"start with a NULL engine");
    EXPECT(skegn_get_last_error() == 31, @"SGN_ENGINE_IS_NULL");
    free(box);
    return nil;
}

NSString *YGSTCheckSkegnAgainstMock(NSString *baseURL, NSString *specDir, NSTimeInterval timeout) {
    NSString *cfg = [NSString stringWithFormat:@"{\"appKey\":\"mock-app-key\",\"secretKey\":\"mock-secret-key\","
                                               @"\"cloud\":{\"server\":\"%@\"}}",
                                               baseURL];
    NSData *wav = [NSData dataWithContentsOfFile:[specDir stringByAppendingPathComponent:@"fixtures/audio/zh_short.wav"]];
    YGSTCallbackBox *box;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    char token[64] = {0};
    struct skegn *engine;
    NSUInteger off;
    NSDictionary *j;
    EXPECT(wav.length > 78, @"fixture audio");
    engine = skegn_new(cfg.UTF8String);
    EXPECT(engine != NULL, @"skegn_new");
    box = calloc(1, sizeof *box);
    box->done = sem;
    EXPECT(skegn_start(engine,
                       "{\"coreProvideType\":\"cloud\",\"app\":{\"userId\":\"u1\"},"
                       "\"audio\":{\"audioType\":\"wav\",\"sampleRate\":16000,\"channel\":1,\"sampleBytes\":2},"
                       "\"request\":{\"coreType\":\"sent.eval.cn\",\"refText\":\"今天天气很好\"}}",
                       token, YGSTSupportCallback, box) == 0,
           @"skegn_start");
    for (off = 78; off < wav.length; off += 640) {
        NSUInteger n = MIN((NSUInteger)640, wav.length - off);
        EXPECT(skegn_feed(engine, (const char *)wav.bytes + off, (int)n) == 0, @"skegn_feed");
    }
    EXPECT(skegn_stop(engine) == 0, @"skegn_stop");
    EXPECT(skegn_stop(engine) == -1, @"second skegn_stop");
    EXPECT(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC))) == 0,
           @"result callback");
    j = YGSTParse(box->json);
    EXPECT([j[@"tokenId"] isEqualToString:[NSString stringWithUTF8String:token]], @"tokenId");
    EXPECT([j[@"userId"] isEqualToString:@"u1"], @"userId");
    EXPECT(j[@"result"] != nil, [NSString stringWithUTF8String:box->json]);
    skegn_delete(engine);
    free(box);
    return nil;
}
