//
//  YGSTRecorder.m
//  STKouyuEngine 平替层：AVAudioEngine 录音，转换为 16 kHz 单声道 16 位 PCM。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "YGSTInternal.h"

#import <AVFoundation/AVFoundation.h>
#include <TargetConditionals.h>

void YGSTRequestMicrophone(dispatch_queue_t queue, void (^completion)(BOOL granted)) {
#if TARGET_OS_IPHONE
    AVAudioSession *session = [AVAudioSession sharedInstance];
    AVAudioSessionRecordPermission p = session.recordPermission;
    if (p == AVAudioSessionRecordPermissionGranted) {
        dispatch_async(queue, ^{
            completion(YES);
        });
        return;
    }
    if (p == AVAudioSessionRecordPermissionDenied) {
        dispatch_async(queue, ^{
            completion(NO);
        });
        return;
    }
    [session requestRecordPermission:^(BOOL granted) {
        dispatch_async(queue, ^{
            completion(granted);
        });
    }];
#else
    AVAuthorizationStatus st = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    if (st == AVAuthorizationStatusAuthorized) {
        dispatch_async(queue, ^{
            completion(YES);
        });
        return;
    }
    if (st == AVAuthorizationStatusDenied || st == AVAuthorizationStatusRestricted) {
        dispatch_async(queue, ^{
            completion(NO);
        });
        return;
    }
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio
                             completionHandler:^(BOOL granted) {
                                 dispatch_async(queue, ^{
                                     completion(granted);
                                 });
                             }];
#endif
}

BOOL YGSTActivateAudioSession(NSString **reason) {
#if TARGET_OS_IPHONE
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    AVAudioSessionCategoryOptions options =
        AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetooth;
    if (![session.category isEqualToString:AVAudioSessionCategoryPlayAndRecord] ||
        (session.categoryOptions & options) != options) {
        if (![session setCategory:AVAudioSessionCategoryPlayAndRecord withOptions:options error:&error]) {
            if (reason) *reason = [NSString stringWithFormat:@"AVAudioSession category: %@", error.localizedDescription];
            return NO;
        }
    }
    if (![session setActive:YES error:&error]) {
        if (reason) *reason = [NSString stringWithFormat:@"AVAudioSession activation: %@", error.localizedDescription];
        return NO;
    }
    return YES;
#else
    (void)reason;
    return YES;
#endif
}

@implementation YGSTRecorder {
    dispatch_queue_t _queue;
    AVAudioEngine *_engine;
    AVAudioConverter *_converter;
    AVAudioFormat *_outFormat;
    BOOL _running;
    NSUInteger _generation;
    id _configObserver;
    id _interruptionObserver;
}

- (instancetype)initWithQueue:(dispatch_queue_t)queue {
    if ((self = [super init])) {
        _queue = queue;
        _outFormat = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatInt16
                                                      sampleRate:16000
                                                        channels:1
                                                     interleaved:YES];
    }
    return self;
}

- (void)dealloc {
    [self removeObservers];
}

- (BOOL)running {
    return _running;
}

- (BOOL)installTap:(NSString **)reason {
    AVAudioInputNode *input = _engine.inputNode;
    AVAudioFormat *inFormat = [input outputFormatForBus:0];
    AVAudioConverter *converter;
    AVAudioFormat *outFormat = _outFormat;
    dispatch_queue_t queue = _queue;
    NSUInteger generation = _generation;
    double ratio;
    __weak YGSTRecorder *weakSelf = self;
    if (inFormat.sampleRate <= 0 || inFormat.channelCount == 0) {
        if (reason) *reason = @"no audio input is available";
        return NO;
    }
    converter = [[AVAudioConverter alloc] initFromFormat:inFormat toFormat:outFormat];
    if (!converter) {
        if (reason) *reason = @"audio converter could not be created";
        return NO;
    }
    converter.downmix = YES;
    _converter = converter;
    ratio = outFormat.sampleRate / inFormat.sampleRate;
    [input installTapOnBus:0
                bufferSize:(AVAudioFrameCount)(inFormat.sampleRate / 10)
                    format:inFormat
                     block:^(AVAudioPCMBuffer *buffer, AVAudioTime *when) {
                         AVAudioFrameCount capacity = (AVAudioFrameCount)(buffer.frameLength * ratio) + 64;
                         AVAudioPCMBuffer *out = [[AVAudioPCMBuffer alloc] initWithPCMFormat:outFormat
                                                                                frameCapacity:capacity];
                         __block BOOL fed = NO;
                         NSError *convError = nil;
                         AVAudioConverterOutputStatus status;
                         NSData *pcm;
                         (void)when;
                         if (!out) return;
                         status = [converter convertToBuffer:out
                                                       error:&convError
                                          withInputFromBlock:^AVAudioBuffer *(AVAudioPacketCount inNumberOfPackets,
                                                                              AVAudioConverterInputStatus *outStatus) {
                                              (void)inNumberOfPackets;
                                              if (fed) {
                                                  *outStatus = AVAudioConverterInputStatus_NoDataNow;
                                                  return nil;
                                              }
                                              fed = YES;
                                              *outStatus = AVAudioConverterInputStatus_HaveData;
                                              return buffer;
                                          }];
                         if (status == AVAudioConverterOutputStatus_Error || out.frameLength == 0 ||
                             !out.int16ChannelData) {
                             return;
                         }
                         pcm = [NSData dataWithBytes:out.int16ChannelData[0]
                                              length:(NSUInteger)out.frameLength * sizeof(int16_t)];
                         dispatch_async(queue, ^{
                             YGSTRecorder *strongSelf = weakSelf;
                             if (!strongSelf || strongSelf->_generation != generation) return;
                             if (strongSelf.pcmHandler) strongSelf.pcmHandler(pcm);
                         });
                     }];
    return YES;
}

- (BOOL)start:(NSString **)reason {
    NSError *error = nil;
    if (_running) return YES;
    _generation++;
    _engine = [[AVAudioEngine alloc] init];
    @try {
        if (![self installTap:reason]) {
            _engine = nil;
            return NO;
        }
        [_engine prepare];
        if (![_engine startAndReturnError:&error]) {
            [_engine.inputNode removeTapOnBus:0];
            _engine = nil;
            if (reason) *reason = [NSString stringWithFormat:@"audio engine did not start: %@", error.localizedDescription];
            return NO;
        }
    } @catch (NSException *exception) {
        _engine = nil;
        if (reason) *reason = [NSString stringWithFormat:@"audio engine exception: %@", exception.reason];
        return NO;
    }
    _running = YES;
    [self addObservers];
    YGSTLogf(YGST_LEVEL_DEBUG, @"recorder started, input %.0f Hz", [_engine.inputNode outputFormatForBus:0].sampleRate);
    return YES;
}

- (void)stop {
    AVAudioEngine *engine = _engine;
    _running = NO;
    [self removeObservers];
    if (!engine) return;
    @try {
        [engine.inputNode removeTapOnBus:0];
        [engine stop];
    } @catch (NSException *exception) {
        YGSTLogf(YGST_LEVEL_WARN, @"recorder stop: %@", exception.reason);
    }
    _engine = nil;
    _converter = nil;
}

- (void)restartAfterChange {
    NSString *reason = nil;
    NSError *error = nil;
    if (!_running || !_engine) return;
    @try {
        [_engine.inputNode removeTapOnBus:0];
        [_engine stop];
        _generation++;
        if (![self installTap:&reason]) {
            [self fail:reason ?: @"audio input changed and is not usable"];
            return;
        }
        [_engine prepare];
        if (![_engine startAndReturnError:&error]) {
            [self fail:[NSString stringWithFormat:@"audio engine restart failed: %@", error.localizedDescription]];
        }
    } @catch (NSException *exception) {
        [self fail:[NSString stringWithFormat:@"audio engine exception: %@", exception.reason]];
    }
}

- (void)fail:(NSString *)message {
    YGSTLogf(YGST_LEVEL_ERROR, @"recorder: %@", message);
    if (self.failureHandler) self.failureHandler(message);
}

- (void)addObservers {
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    __weak YGSTRecorder *weakSelf = self;
    dispatch_queue_t queue = _queue;
    [self removeObservers];
    _configObserver = [nc addObserverForName:AVAudioEngineConfigurationChangeNotification
                                      object:_engine
                                       queue:nil
                                  usingBlock:^(NSNotification *note) {
                                      (void)note;
                                      dispatch_async(queue, ^{
                                          YGSTLogf(YGST_LEVEL_INFO, @"audio configuration changed, restarting capture");
                                          [weakSelf restartAfterChange];
                                      });
                                  }];
#if TARGET_OS_IPHONE
    _interruptionObserver = [nc addObserverForName:AVAudioSessionInterruptionNotification
                                            object:[AVAudioSession sharedInstance]
                                             queue:nil
                                        usingBlock:^(NSNotification *note) {
                                            NSUInteger type =
                                                [note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
                                            NSUInteger options =
                                                [note.userInfo[AVAudioSessionInterruptionOptionKey] unsignedIntegerValue];
                                            dispatch_async(queue, ^{
                                                [weakSelf handleInterruption:type options:options];
                                            });
                                        }];
#endif
}

- (void)removeObservers {
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    if (_configObserver) [nc removeObserver:_configObserver];
    if (_interruptionObserver) [nc removeObserver:_interruptionObserver];
    _configObserver = nil;
    _interruptionObserver = nil;
}

#if TARGET_OS_IPHONE
- (void)handleInterruption:(NSUInteger)type options:(NSUInteger)options {
    if (!_running) return;
    if (type == AVAudioSessionInterruptionTypeBegan) {
        YGSTLogf(YGST_LEVEL_INFO, @"audio session interrupted");
        if (self.interruptionHandler) self.interruptionHandler(YES);
        return;
    }
    if (type == AVAudioSessionInterruptionTypeEnded) {
        NSError *error = nil;
        if (options & AVAudioSessionInterruptionOptionShouldResume) {
            [[AVAudioSession sharedInstance] setActive:YES error:&error];
            if (![_engine startAndReturnError:&error]) {
                [self fail:[NSString stringWithFormat:@"capture did not resume: %@", error.localizedDescription]];
                return;
            }
            YGSTLogf(YGST_LEVEL_INFO, @"audio session interruption ended, capture resumed");
            if (self.interruptionHandler) self.interruptionHandler(NO);
        }
    }
}
#endif

@end
