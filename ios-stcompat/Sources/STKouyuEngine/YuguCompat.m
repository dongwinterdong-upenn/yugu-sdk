//
//  YuguCompat.m
//  STKouyuEngine 平替层（优谷雅言）的扩展配置
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "include/STKouyuEngine/YuguCompat.h"
#import "YGSTInternal.h"

@implementation YuguCompat

+ (NSString *)sdkVersion {
    return @YGST_SDK_VERSION;
}

+ (NSString *)userAgent {
    return @YGST_USER_AGENT;
}

+ (NSString *)defaultBaseURL {
    return @YGST_DEFAULT_BASE_URL;
}

+ (NSString *)baseURL {
    return [YGSTSettings shared].baseURLOverride;
}

+ (void)setBaseURL:(NSString *)baseURL {
    NSString *trimmed = [baseURL stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    while ([trimmed hasSuffix:@"/"]) trimmed = [trimmed substringToIndex:trimmed.length - 1];
    if (trimmed.length && !([trimmed hasPrefix:@"http://"] || [trimmed hasPrefix:@"https://"])) {
        YGSTLogf(YGST_LEVEL_ERROR, @"YuguCompat.baseURL must start with http:// or https://, ignored: %@", baseURL);
        return;
    }
    [YGSTSettings shared].baseURLOverride = trimmed.length ? trimmed : nil;
}

+ (NSString *)effectiveBaseURL {
    NSString *o = [YGSTSettings shared].baseURLOverride;
    return o.length ? o : [YGSTSettings shared].configuredBaseURL;
}

+ (void)setLogLevel:(YuguCompatLogLevel)level {
    [YGSTSettings shared].logLevelOverride = level < YuguCompatLogLevelOff ? YuguCompatLogLevelOff
                                                                          : (level > YuguCompatLogLevelDebug
                                                                                 ? YuguCompatLogLevelDebug
                                                                                 : level);
}

+ (void)resetLogLevel {
    [YGSTSettings shared].logLevelOverride = NSNotFound;
}

+ (void)setLogHandler:(void (^)(NSInteger, NSString *))handler {
    [YGSTSettings shared].logHandler = handler;
}

+ (NSInteger)maxRetries {
    return [YGSTSettings shared].maxRetries;
}

+ (void)setMaxRetries:(NSInteger)maxRetries {
    [YGSTSettings shared].maxRetries = maxRetries < 0 ? 0 : (maxRetries > 5 ? 5 : maxRetries);
}

+ (NSTimeInterval)totalTimeout {
    return [YGSTSettings shared].totalTimeout;
}

+ (void)setTotalTimeout:(NSTimeInterval)totalTimeout {
    [YGSTSettings shared].totalTimeout = totalTimeout > 1 ? totalTimeout : 1;
}

@end
