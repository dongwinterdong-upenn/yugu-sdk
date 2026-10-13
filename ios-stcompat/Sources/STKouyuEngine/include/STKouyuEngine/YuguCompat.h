//
//  YuguCompat.h
//  STKouyuEngine 平替层（优谷雅言）的扩展配置
//
//  声通原有接口之外的配置全部集中在本头文件，不改变 KYTestEngine 等原有声明。
//  Objective-C：#import <STKouyuEngine/YuguCompat.h> 或 @import STKouyuEngine.YuguCompat;
//  Swift：import STKouyuEngine.YuguCompat
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 日志级别。数值与 KYLOG_ERROR、KYLOG_WARN、KYLOG_INFO、KYLOG_DEBUG 相同。
typedef NS_ENUM(NSInteger, YuguCompatLogLevel) {
    YuguCompatLogLevelOff = -1,
    YuguCompatLogLevelError = 0,
    YuguCompatLogLevelWarn = 1,
    YuguCompatLogLevelInfo = 2,
    YuguCompatLogLevelDebug = 3,
};

/// 平替层的扩展配置与版本信息。所有方法线程安全，设置在下一次评测生效。
@interface YuguCompat : NSObject

/// "2.1.1"
@property (class, nonatomic, readonly, copy) NSString *sdkVersion;

/// User-Agent，"yugu-ios-stcompat-sdk/2.1.1"
@property (class, nonatomic, readonly, copy) NSString *userAgent;

/// 默认平台地址 https://open.shengzhiai.com
@property (class, nonatomic, readonly, copy) NSString *defaultBaseURL;

/// 平台地址覆盖，http 或 https 开头。设置后优先于 KYStartEngineConfig.server，nil 取消覆盖。
@property (class, nonatomic, copy, nullable) NSString *baseURL;

/// 当前生效的平台地址
@property (class, nonatomic, readonly, copy) NSString *effectiveBaseURL;

/// 日志级别覆盖，优先于 KYStartEngineConfig.logLevel。默认不覆盖。
+ (void)setLogLevel:(YuguCompatLogLevel)level;

/// 取消日志级别覆盖
+ (void)resetLogLevel;

/// 日志接收方。设置后日志交给 handler，不再写控制台。日志里没有 secretKey、签名与音频内容。
+ (void)setLogHandler:(nullable void (^)(NSInteger level, NSString *message))handler;

/// 单次提交内的重试次数，0 到 5，默认 2（共 3 次尝试）。
@property (class, nonatomic) NSInteger maxRetries;

/// 一次评测含重试与等待的总时限，单位秒，默认 300。
@property (class, nonatomic) NSTimeInterval totalTimeout;

/// 边录边传，默认 YES。录音与 16 kHz 16 位单声道 PCM 的 feed 在录音过程中经声通兼容实时评测接口上传，
/// stopEngine 之后只剩最后一小段音频。设为 NO 时与 2.1.0 相同，录音结束后整段上传。
/// audioPath 模式总是整段上传。realtime_feedback 只决定是否回调朗读进度。需要 iOS 13，iOS 12 上整段上传。
@property (class, nonatomic) BOOL streamUpload;

@end

NS_ASSUME_NONNULL_END
