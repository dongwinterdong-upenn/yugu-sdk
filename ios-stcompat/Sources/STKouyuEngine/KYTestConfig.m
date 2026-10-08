//
//  KYTestConfig.m
//  STKouyuEngine 平替层（优谷雅言）
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import "include/STKouyuEngine/KYTestConfig.h"

NSString *const KYEngineCloud = @"cloud";
NSString *const KYEngineNative = @"native";

@implementation KYTestConfigTemp
@end

@implementation KYTestConfig

- (instancetype)init {
    if ((self = [super init])) {
        _audioType = @"wav";
        _channel = 1;
        _sampleBytes = 2;
        _sampleRate = 16000;
        _quality = 8;
        _complexity = 2;
        _compress = KYCompress_Speex;
        _max_ogg_delay = 48000;
        _phoneme_output = YES;
        _mode = KYModeType_School;
        _durationInterval = 100;
        _errIds = @[ @"20009" ];
    }
    return self;
}

- (NSString *)description {
    return [NSString stringWithFormat:@"<KYTestConfig coreType=%lu coreTypeNS=%@ refText=%@ audioType=%@ isStream=%d audioPath=%@>",
                                      (unsigned long)_coreType, _coreTypeNS ?: @"", _refText ?: @"", _audioType ?: @"",
                                      _isStream, _audioPath ?: @""];
}

@end
