//
//  KYTestEngine.h
//  STKouyuEngine 平替层（优谷雅言）
//
//  评测引擎。声明与声通公开头文件一致，评测请求发到优谷雅言平台 POST /{coreType}，
//  结果与错误 JSON 的格式见 README。全部回调在主线程。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import <Foundation/Foundation.h>
#import "KYStartEngineConfig.h"
#import "KYTestConfig.h"

typedef enum : NSUInteger {
    KY_CloudEngine,       // 云端评测
    KY_NativeEngine,      // 平替层按云端评测，并打印警告日志
    KY_MultiEngine,       // 平替层按云端评测，并打印警告日志
} KYEngineType;

typedef void(^KYTestResultBlock)(NSString *testResult);

typedef void(^KYPlayFinishBlock)(void);

@protocol KYTestEngineDelegate <NSObject>
@optional

- (void)kyTestEngineDidRecordStart;
- (void)kyTestEngineDidRecordStartFail:(NSString *)str;
- (void)kyTestEngineDidRecordTick:(CGFloat)millisUntilFinished percentUntilFinished:(CGFloat)percentUntilFinished;
- (void)kyTestEngineDidRecordEnd;
- (void)kyTestEngineDidVadScore:(int)vad_status sound_intensity:(int)sound_intensity;
- (void)kyTestEngineDidScore:(NSString *)str;
- (void)kyTestEngineDidRecordFeedFail:(NSString *)str;
- (void)kyTestEngineDidRecordWriteAudioResult:(BOOL)result;
- (void)kyTestEngineDidPlayStart;
- (void)kyTestEngineDidPlayStartFail:(NSString *)str;
- (void)kyTestEngineDidPlayEnd;
@end

@interface KYTestEngine : NSObject
@property (nonatomic, weak) id<KYTestEngineDelegate> delegate;

+ (instancetype)sharedInstance;

/**
 初始化引擎。校验 appKey 与 secretKey，确定平台地址。

 @param engineType 引擎类型，三种都按云端评测
 @param startEngineConfig 初始化参数
 @param finishBlock 主线程回调，成功时 isSuccess 为 YES
 */
- (void)initEngine:(KYEngineType)engineType startEngineConfig:(KYStartEngineConfig *)startEngineConfig finishBlock:(void(^)(BOOL isSuccess, NSString *str))finishBlock;


/**
 开始评测，返回本次评测的 tokenId，tokenId 同时是平台请求的幂等键。

 @param testConfig  评测参数
 @param testResultBlock 结果回调，参数为结果 JSON 或错误 JSON
 @param finishBlock 开始是否成功的回调，成功时 str 为 tokenId，失败时为错误 JSON
 */
- (NSString *)startEngineWithTestConfig:(KYTestConfig *)testConfig result:(KYTestResultBlock)testResultBlock finishBlock:(void(^)(BOOL isSuccess, NSString *str))finishBlock;

/**
 开始评测，带录音过程回调，返回 tokenId。

 @param testConfig  评测参数
 @param onStartBlock 录音开始
 @param onStartFailBlock 录音未能开始，参数为原因
 @param onPauseBlock 录音被系统中断而暂停
 @param onTickBlock 设置 duration 后的倒计时，剩余毫秒与剩余百分比
 @param onRecordingBlock 开启 VAD 后的 vad_status 与 sound_intensity
 @param onRecordEndBlock 录音结束
 @param onScoreBlock 结果回调，参数为结果 JSON 或错误 JSON
 @param finishBlock 开始是否成功的回调
 */

- (NSString *)startEngineWithTestConfig:(KYTestConfig *)testConfig
                           onStartBlock:(void(^)(void))onStartBlock
                       onStartFailBlock:(void(^)(NSString *failReason))onStartFailBlock onPauseBlock:(void(^)(void))onPauseBlock
                            onTickBlock:(void(^)(CGFloat millisUntilFinished, CGFloat percentUntilFinished))onTickBlock
                       onRecordingBlock:(void(^)(int vad_status, int sound_intensity))onRecordingBlock
                       onRecordEndBlock:(void(^)(void))onRecordEndBlock
                           onScoreBlock:(void(^)(NSString *result))onScoreBlock
                            finishBlock:(void(^)(BOOL isSuccess, NSString *str))finishBlock;

/**
 结束录音并评测（有结果回调）
 */
- (void)stopEngine;

/**
 取消本次评测（无结果回调）
 */
- (void)cancelEngine;

/**
 释放引擎，可重复调用。之后需要重新 initEngine
 */
- (void)deleteEngine;

/**
 回放最近一次录音
 */
- (void)playback;

/**
 播放指定路径的音频
 **/
- (void)playWithPath:(NSString *)wavPath;

/**
 回放最近一次录音

 @param playFinishBlock 播放结束回调
 */
- (void)playback:(KYPlayFinishBlock)playFinishBlock;

/**
 播放指定路径的音频

 @param playFinishBlock 播放结束回调
 **/
- (void)playWithPath:(NSString *)wavPath void:(KYPlayFinishBlock)playFinishBlock;

/**
 停止播放
 */
- (void)stopPlay;

/**
 激活音频会话（PlayAndRecord）
 */
- (void)activeAudioSession;

/**
 录音或 feed 进行中时返回 YES
 */
- (BOOL)getEngineStatus;

/**
 最近一次录音文件路径
 */
- (NSString *)getLastRecordPath;

/**
 isStream 为 YES 时送入音频，wav 类型为 PCM 数据
 */
- (void)feedAudioData:(void *)audioData audioLength:(int)length;

/**
 云端评测不需要证书，返回 YES
 @param provison 不生效
 @param appkey 不生效
 @param secretkey 不生效
 */
- (BOOL)updateProvision:(NSString *)provison appkey:(NSString *)appkey secretkey:(NSString *)secretkey;

- (BOOL)updateProvision:(NSString *)appkey secretkey:(NSString *)secretkey;

- (BOOL)updateProvision;

/**
 返回 YES，回调 {"provision":"cloud","message":"cloud mode, no provision file needed"}
 @param provision 不生效
 @param inquireProvisionBlock 主线程回调
 */
- (BOOL)inquireProvision:(NSString *)provision
   inquireProvisionBlock:(void(^)(NSString * message))inquireProvisionBlock;

- (BOOL)inquireProvision:(void(^)(NSString * message))inquireProvisionBlock;

@end
