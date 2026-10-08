//
//  KYStartEngineConfig.m
//  STKouyuEngine 平替层（优谷雅言）
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "include/STKouyuEngine/KYStartEngineConfig.h"
#import "YGSTInternal.h"

// The documented Shengtong addresses. Both map to the Yugu default platform address.
NSString *const KY_CloudServer_Gray = @"ws://gray.stkouyu.com:8090";
NSString *const KY_CloudServer_Release = @"ws://api.stkouyu.com:8080";

// Same scale as KYStartEngineConfig.logLevel: 0 error, 1 warn, 2 info, 3 debug.
int const KYLOG_ERROR = 0;
int const KYLOG_WARN = 1;
int const KYLOG_INFO = 2;
int const KYLOG_DEBUG = 3;

void KYLog(int flag, NSString *format, ...) {
    va_list ap;
    NSString *message;
    if (!format || flag > YGSTLogLevel()) return;
    va_start(ap, format);
    message = [[NSString alloc] initWithFormat:format arguments:ap];
    va_end(ap);
    YGSTLogEmit(flag, message);
}

@implementation KYStartEngineConfig

- (instancetype)init {
    if ((self = [super init])) {
        _seek = 60;
        _logLevel = 1;
        _isOutputLog = YES;
        _enable = YES;
        _connectTimeout = 20;
        _serverTimeout = 60;
    }
    return self;
}

- (NSString *)description {
    return [NSString stringWithFormat:@"<KYStartEngineConfig appKey=%@ server=%@ vadEnable=%d seek=%.0f logLevel=%.0f>",
                                      YGSTMaskedAppKey(_appKey), _server ?: @"(default)", _vadEnable, _seek, _logLevel];
}

@end
