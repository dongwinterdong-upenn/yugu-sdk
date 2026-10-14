//
//  YuguCompat.h
//  STKouyuEngine 平替层（优谷雅言）的扩展配置，语音合成与报告查询
//
//  声通原有接口之外的配置与接口全部集中在本头文件，不改变 KYTestEngine 等原有声明。
//  Objective-C：#import <STKouyuEngine/YuguCompat.h> 或 @import STKouyuEngine.YuguCompat;
//  Swift：import STKouyuEngine.YuguCompat，类名为 TtsClient，ReportClient 等不带 Yugu 前缀的名字
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

/// "2.3.0"
@property (class, nonatomic, readonly, copy) NSString *sdkVersion;

/// User-Agent，"yugu-ios-stcompat-sdk/2.3.0"
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

/// 单次提交内的重试次数，0 到 5，默认 2（共 3 次尝试）。语音合成与报告查询同样适用。
@property (class, nonatomic) NSInteger maxRetries;

/// 一次评测含重试与等待的总时限，单位秒，默认 300。语音合成与报告查询的一次请求同样适用。
@property (class, nonatomic) NSTimeInterval totalTimeout;

/// 边录边传，默认 YES。录音与 16 kHz 16 位单声道 PCM 的 feed 在录音过程中经声通兼容实时评测接口上传，
/// stopEngine 之后只剩最后一小段音频。设为 NO 时与 2.1.0 相同，录音结束后整段上传。
/// audioPath 模式总是整段上传。realtime_feedback 只决定是否回调朗读进度。需要 iOS 13，iOS 12 上整段上传。
@property (class, nonatomic) BOOL streamUpload;

/// 2.2.0 不支持的评测内核是否抛出异常，默认 NO。
/// NO 时结果回调收到 errId 60003 的错误 JSON，error 为“不支持的评测内核：open.eval”这样的一句话，
/// 同时打印一行 warn 日志。YES 时 KYTestEngine 的两个开始评测方法在调用线程同步抛出
/// NSInvalidArgumentException，reason 为同一句话，不回调，不录音，不发请求。C 接口 skegn_start 返回 -1，
/// skegn_get_last_error 为 60003，打印一行 error 日志，不回调。其余错误照常回调。
/// Swift 不能捕获 Objective-C 异常，Swift 工程打开前需要自行确认内核受支持。
@property (class, nonatomic) BOOL throwOnUnsupportedCoreType;

/// 2.3.0 流水线评测，KYTestEngine 每次开始评测时读取，默认 0。
/// 0 时与 2.2.0 相同，一次只做一次评测，上一次结果回调之前开始评测回调 errId 60008。
/// 大于 0 时停录处理完毕的评测转到后台等结果，下一次评测可以立即开始录音，后台等结果的评测最多 n 次。
/// 正在录音或暂停时，或后台等结果的评测多于 n 次时，开始评测照旧回调 60008。
/// 每次评测有自己的 tokenId，回调，录音文件与网络连接，结果按完成先后回调。取值限定在 0 到 16，负数按 0。
/// 取值变化时打印一行 info 日志。
@property (class, nonatomic) NSInteger maxPendingEvaluations;

/// 2.3.0 已停录或以 audioPath 开始，还没有回调结果的评测次数，全部 KYTestEngine 实例合计。
/// 结果回调开始之前减一，cancelEngine 与 deleteEngine 取消的评测随即不再计入。C 接口的评测不计入。
@property (class, nonatomic, readonly) NSInteger pendingEvaluations;

@end

// ------------------------------------------------------------------ 语音合成与报告查询（2.2.0）

/// TtsClient 与 ReportClient 的错误域。code 为平台错误码，例如 40001 与 2003，
/// 网络或服务端临时故障重试用尽后为 20009，本机检查为 60007（引擎未初始化）或 90010（参数不合法），
/// 平台响应无法解析为 90005。localizedDescription 为平台消息或本机说明。
FOUNDATION_EXPORT NSErrorDomain const YuguCompatErrorDomain;

/// 上述错误 userInfo 里 HTTP 状态码的键，值为 NSNumber，没有收到 HTTP 响应时没有这一项。
FOUNDATION_EXPORT NSString *const YuguCompatHTTPStatusKey;

/// 语音合成请求，字段与 POST /api/v1/tts/generate 相同，只发送设置过的字段。
NS_SWIFT_NAME(TtsRequest)
@interface YuguTtsRequest : NSObject
- (instancetype)initWithText:(NSString *)text;
/// 必填，要合成的文本
@property (nonatomic, copy) NSString *text;
/// zh-CN，en-US 或 en-GB
@property (nonatomic, copy, nullable) NSString *language;
/// 中文 xiaoyan 或 xiaofeng，英文 female 或 male
@property (nonatomic, copy, nullable) NSString *voice;
/// mp3，wav 或 ogg
@property (nonatomic, copy, nullable) NSString *format;
/// 语速，0 到 100
@property (nonatomic, strong, nullable) NSNumber *speed;
/// 音高，0 到 100
@property (nonatomic, strong, nullable) NSNumber *pitch;
/// 音量，0 到 100
@property (nonatomic, strong, nullable) NSNumber *volume;
/// 采样率，单位 Hz
@property (nonatomic, strong, nullable) NSNumber *sampleRate;
/// 情感预设 neutral，warm 或 energetic
@property (nonatomic, copy, nullable) NSString *emotion;
/// 风格说明，自然语言
@property (nonatomic, copy, nullable) NSString *style;
/// 中文读音，与文本里的汉字逐个对应的数字调拼音，空格隔开
@property (nonatomic, copy, nullable) NSString *pinyin;
/// 文体 classical_poem 或 prose
@property (nonatomic, copy, nullable) NSString *genre;
@end

/// 语音合成选项
NS_SWIFT_NAME(TtsOptions)
@interface YuguTtsOptions : NSObject
/// 幂等键，1 到 200 个可见 ASCII 字符。为 nil 时每次调用生成新的键，同一次调用的重试沿用同一个键。
@property (nonatomic, copy, nullable) NSString *idempotencyKey;
/// 单次请求的超时，单位秒，默认 60
@property (nonatomic) NSTimeInterval timeout;
@end

/// 语音合成结果
NS_SWIFT_NAME(TtsResult)
@interface YuguTtsResult : NSObject
/// 平台返回的 audioUrl，原样保留
@property (nonatomic, readonly, copy, nullable) NSString *audioUrl;
/// 按平台地址补全的完整地址，/audio/ 开头的地址补为 <平台地址>/tts/audio/...
@property (nonatomic, readonly, copy, nullable) NSString *absoluteAudioUrl;
/// 时长，单位秒，平台没有给出时为 0
@property (nonatomic, readonly) NSTimeInterval duration;
/// 音频格式
@property (nonatomic, readonly, copy, nullable) NSString *format;
/// 平台响应的完整 JSON 文本
@property (nonatomic, readonly, copy) NSString *rawJSON;
@end

/// 语音合成。回调在主线程，result 与 error 恰有一个不为 nil。
NS_SWIFT_NAME(TtsClient)
@interface YuguTtsClient : NSObject
/// 使用已初始化引擎（initEngine 或 skegn_new）的 appKey，secretKey 与平台地址，每次调用时读取。
/// 引擎未初始化时调用以 60007“引擎未初始化”结束。
+ (instancetype)clientFromEngine NS_SWIFT_NAME(fromEngine());
/// 指定 appKey 与 secretKey，平台地址为 YuguCompat.baseURL，未设置时为默认平台地址。
- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey;
/// 指定 appKey，secretKey 与平台地址，baseURL 为 nil 时同上。
- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey baseURL:(nullable NSString *)baseURL;
/// POST /api/v1/tts/generate，options 为 nil 时用默认选项。
- (void)tts:(YuguTtsRequest *)request
       options:(nullable YuguTtsOptions *)options
    completion:(void (^)(YuguTtsResult *_Nullable result, NSError *_Nullable error))completion
    NS_SWIFT_NAME(tts(_:options:completion:));
@end

/// 报告查询选项
NS_SWIFT_NAME(ReportOptions)
@interface YuguReportOptions : NSObject
/// 等待 AI 终稿的最长时间，单位毫秒，默认 0 只查一次。大于 0 时，reportStatus 为 pending 或 partial 就每
/// 1000 毫秒再查一次，直到二者都不是或时间用完，返回最后一次的结果，仍为 pending 或 partial 也是正常结果。
/// 第一次查询失败时回调该错误。之后的查询失败时回调最近一次查到的结果，error 为 nil，打印一行 warn 日志。
@property (nonatomic) NSInteger waitForFinalMs;
/// 单次请求的超时，单位秒，默认 30
@property (nonatomic) NSTimeInterval timeout;
@end

/// 报告查询结果
NS_SWIFT_NAME(ReportResult)
@interface YuguReportResult : NSObject
/// 评测记录号
@property (nonatomic, readonly, copy) NSString *recordId;
/// final 为 AI 终稿，pending 为即时稿，AI 终稿正在生成，partial 为 AI 整体点评与建议已就绪，各维度点评仍在生成，
/// instant 为即时稿，AI 终稿不会再来，none 为没有报告。平台没有给出时为 nil
@property (nonatomic, readonly, copy, nullable) NSString *reportStatus;
/// reportStatus 为 final，partial 时为 NO
@property (nonatomic, readonly, getter=isFinal) BOOL final;
/// 整体点评 report.summary
@property (nonatomic, readonly, copy, nullable) NSString *summary;
/// 改进建议 report.suggestions，没有时为空数组
@property (nonatomic, readonly, copy) NSArray<NSString *> *suggestions;
/// 总分 score.overall
@property (nonatomic, readonly, strong, nullable) NSNumber *overall;
/// 报告对象 report，含 dimensions，dimensionSuggestions，skillInsights 等
@property (nonatomic, readonly, copy, nullable) NSDictionary<NSString *, id> *report;
/// 评分对象 score，与评测结果相同，开放题的点评在其中的 feedback
@property (nonatomic, readonly, copy, nullable) NSDictionary<NSString *, id> *score;
/// 声通兼容评测的分段耗时 timing，保留 7 天，没有时为 nil
@property (nonatomic, readonly, copy, nullable) NSDictionary<NSString *, id> *timing;
/// 平台响应 data 的完整 JSON 文本
@property (nonatomic, readonly, copy) NSString *dataJSON;
@end

/// 报告查询。回调在主线程，result 与 error 恰有一个不为 nil。
NS_SWIFT_NAME(ReportClient)
@interface YuguReportClient : NSObject
/// 使用已初始化引擎（initEngine 或 skegn_new）的 appKey，secretKey 与平台地址，每次调用时读取。
/// 引擎未初始化时调用以 60007“引擎未初始化”结束。
+ (instancetype)clientFromEngine NS_SWIFT_NAME(fromEngine());
/// 指定 appKey 与 secretKey，平台地址为 YuguCompat.baseURL，未设置时为默认平台地址。
- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey;
/// 指定 appKey，secretKey 与平台地址，baseURL 为 nil 时同上。
- (instancetype)initWithAppKey:(NSString *)appKey secretKey:(NSString *)secretKey baseURL:(nullable NSString *)baseURL;
/// GET /api/v1/report/{recordId}，recordId 为评测结果 JSON 的 recordId，options 为 nil 时只查一次。
- (void)getReport:(NSString *)recordId
          options:(nullable YuguReportOptions *)options
       completion:(void (^)(YuguReportResult *_Nullable result, NSError *_Nullable error))completion
    NS_SWIFT_NAME(getReport(_:options:completion:));
@end

NS_ASSUME_NONNULL_END
