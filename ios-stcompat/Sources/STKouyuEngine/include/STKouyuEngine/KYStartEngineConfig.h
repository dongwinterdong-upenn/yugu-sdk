//
//  KYStartEngineConfig.h
//  STKouyuEngine 平替层（优谷雅言）
//
//  引擎初始化参数。声明与声通公开头文件一致，评测走优谷雅言云端，离线与证书相关参数保留但不生效。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#ifndef KYStartEngineConfig_H_
#define KYStartEngineConfig_H_

#import <Foundation/Foundation.h>
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#else
#import <CoreGraphics/CoreGraphics.h>
#endif

extern NSString *const KY_CloudServer_Gray;      // 声通灰度地址，平替层映射到优谷雅言默认地址

extern NSString *const KY_CloudServer_Release;   // 声通正式地址，平替层映射到优谷雅言默认地址

FOUNDATION_EXPORT int const KYLOG_ERROR;
FOUNDATION_EXPORT int const KYLOG_WARN;
FOUNDATION_EXPORT int const KYLOG_INFO;
FOUNDATION_EXPORT int const KYLOG_DEBUG;

FOUNDATION_EXPORT void KYLog(int flag, NSString *format, ...) NS_FORMAT_FUNCTION(2,3) NS_NO_TAIL_CALL;


@interface KYStartEngineConfig : NSObject

/****************  公用参数  *****************/
// 必填，优谷雅言控制台的 appKey
@property (nonatomic, copy) NSString *appKey;

// 必填，优谷雅言控制台的 secretKey，用于请求签名
@property (nonatomic, copy) NSString *secretKey;

// 不生效，云端评测不需要证书
@property (nonatomic, assign) BOOL isUseOnlineProvison;

// 不生效，云端评测不需要证书
@property (nonatomic, assign) BOOL isUpdateProvison;

// 不生效，云端评测不需要证书
@property (nonatomic, copy) NSString *provison;

// 可选，开启本地 VAD，默认 NO。开启后回调 vad_status：0 未开始说话，1 说话中，2 说话结束
@property (nonatomic, assign) BOOL vadEnable;

// 可选，说话结束判定的静音时长，单位 10ms，默认 60
@property (nonatomic, assign) CGFloat seek;

// 可选，写日志文件，默认 NO
@property (nonatomic, assign) BOOL sdkLogEnable;

// 可选，日志级别，默认 1。0 error，1 warn，2 info，3 debug
@property (nonatomic, assign) CGFloat logLevel;

// 可选，日志文件路径，默认 Documents/sdkLog.txt
@property (nonatomic, copy) NSString *sdkLogPath;

// 可选，控制台输出日志，默认 YES
@property (nonatomic, assign) BOOL isOutputLog;

// 可选，YES 时由业务层设置 AVAudioSession，默认 NO
@property (nonatomic, assign) BOOL customized_avaudiosession;

/******************************************/




/****************  云端引擎参数  *****************/

// 可选，默认 YES。平替层只有云端评测
@property (nonatomic, assign) BOOL enable;

// 可选，服务地址。为空或指向 stkouyu.com 时使用 https://open.shengzhiai.com，其他 http(s) 与 ws(s) 地址作为平台地址
@property (nonatomic, copy) NSString *server;

// 不生效
@property (nonatomic, copy) NSString *serverList;

// 不生效
@property (nonatomic, copy) NSString *sdkCfgAddr;

// 可选，建立连接超时，单位秒，默认 20
@property (nonatomic, assign) CGFloat connectTimeout;

// 可选，等待评测结果超时，单位秒，默认 60
@property (nonatomic, assign) CGFloat serverTimeout;

/******************************************/




/****************  离线引擎参数  *****************/

// 不生效，平替层没有离线引擎
@property (nonatomic, copy) NSString *native;

// 不生效
@property (nonatomic, copy) NSString *native_db_path;

// 不生效
@property (nonatomic, copy) NSString *native_cn;

// 不生效
@property (nonatomic, copy) NSString *ailocalAddress;

/******************************************/

// 不生效，平替层只有云端评测
@property (nonatomic, assign) BOOL autoDetectNetwork;

@end

#endif
