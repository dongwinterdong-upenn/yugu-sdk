/*
 * Stub for the Linux syntax check of the Objective-C layer. Not an Apple header.
 *
 * Declares only the AVFoundation API that STKouyuEngine uses, with the selectors and types of
 * the Apple SDK (AVAudioEngine, AVAudioConverter, AVAudioPCMBuffer, AVAudioPlayer,
 * AVAudioSession on iOS, AVCaptureDevice authorization on macOS).
 */
#ifndef YGST_STUB_AVFOUNDATION_H
#define YGST_STUB_AVFOUNDATION_H

#import <Foundation/Foundation.h>

typedef uint32_t AVAudioFrameCount;
typedef uint32_t AVAudioPacketCount;
typedef uint32_t AVAudioChannelCount;
typedef NSUInteger AVAudioNodeBus;

typedef NS_ENUM(NSUInteger, AVAudioCommonFormat) {
    AVAudioOtherFormat = 0,
    AVAudioPCMFormatFloat32 = 1,
    AVAudioPCMFormatFloat64 = 2,
    AVAudioPCMFormatInt16 = 3,
    AVAudioPCMFormatInt32 = 4
};

@interface AVAudioFormat : NSObject
- (instancetype)initWithCommonFormat:(AVAudioCommonFormat)format
                          sampleRate:(double)sampleRate
                            channels:(AVAudioChannelCount)channels
                         interleaved:(BOOL)interleaved;
@property (nonatomic, readonly) double sampleRate;
@property (nonatomic, readonly) AVAudioChannelCount channelCount;
@property (nonatomic, readonly) AVAudioCommonFormat commonFormat;
@end

@interface AVAudioBuffer : NSObject <NSCopying, NSMutableCopying>
@property (nonatomic, readonly) AVAudioFormat *format;
@end

@interface AVAudioPCMBuffer : AVAudioBuffer
- (instancetype)initWithPCMFormat:(AVAudioFormat *)format frameCapacity:(AVAudioFrameCount)frameCapacity;
@property (nonatomic) AVAudioFrameCount frameLength;
@property (nonatomic, readonly) AVAudioFrameCount frameCapacity;
@property (nonatomic, readonly) float *const *floatChannelData;
@property (nonatomic, readonly) int16_t *const *int16ChannelData;
@end

@interface AVAudioTime : NSObject
@end

typedef void (^AVAudioNodeTapBlock)(AVAudioPCMBuffer *buffer, AVAudioTime *when);

@interface AVAudioNode : NSObject
- (AVAudioFormat *)outputFormatForBus:(AVAudioNodeBus)bus;
- (void)installTapOnBus:(AVAudioNodeBus)bus
             bufferSize:(AVAudioFrameCount)bufferSize
                 format:(AVAudioFormat *)format
                  block:(AVAudioNodeTapBlock)tapBlock;
- (void)removeTapOnBus:(AVAudioNodeBus)bus;
@end

@interface AVAudioIONode : AVAudioNode
@end

@interface AVAudioInputNode : AVAudioIONode
@end

@interface AVAudioOutputNode : AVAudioIONode
@end

FOUNDATION_EXPORT NSString *const AVAudioEngineConfigurationChangeNotification;

@interface AVAudioEngine : NSObject
- (instancetype)init;
@property (readonly, nonatomic) AVAudioInputNode *inputNode;
@property (readonly, nonatomic) AVAudioOutputNode *outputNode;
- (void)prepare;
- (BOOL)startAndReturnError:(NSError **)outError;
- (void)pause;
- (void)stop;
@property (readonly, nonatomic, getter=isRunning) BOOL running;
@end

typedef NS_ENUM(NSInteger, AVAudioConverterInputStatus) {
    AVAudioConverterInputStatus_HaveData = 0,
    AVAudioConverterInputStatus_NoDataNow = 1,
    AVAudioConverterInputStatus_EndOfStream = 2
};

typedef NS_ENUM(NSInteger, AVAudioConverterOutputStatus) {
    AVAudioConverterOutputStatus_HaveData = 0,
    AVAudioConverterOutputStatus_InputRanDry = 1,
    AVAudioConverterOutputStatus_EndOfStream = 2,
    AVAudioConverterOutputStatus_Error = 3
};

typedef AVAudioBuffer *(^AVAudioConverterInputBlock)(AVAudioPacketCount inNumberOfPackets,
                                                     AVAudioConverterInputStatus *outStatus);

@interface AVAudioConverter : NSObject
- (instancetype)initFromFormat:(AVAudioFormat *)fromFormat toFormat:(AVAudioFormat *)toFormat;
@property (nonatomic) BOOL downmix;
- (AVAudioConverterOutputStatus)convertToBuffer:(AVAudioBuffer *)outputBuffer
                                          error:(NSError **)outError
                             withInputFromBlock:(AVAudioConverterInputBlock)inputBlock;
@end

@class AVAudioPlayer;

@protocol AVAudioPlayerDelegate <NSObject>
@optional
- (void)audioPlayerDidFinishPlaying:(AVAudioPlayer *)player successfully:(BOOL)flag;
- (void)audioPlayerDecodeErrorDidOccur:(AVAudioPlayer *)player error:(NSError *)error;
@end

@interface AVAudioPlayer : NSObject
- (instancetype)initWithContentsOfURL:(NSURL *)url error:(NSError **)outError;
@property (weak) id<AVAudioPlayerDelegate> delegate;
- (BOOL)prepareToPlay;
- (BOOL)play;
- (void)pause;
- (void)stop;
@property (readonly, getter=isPlaying) BOOL playing;
@end

#if TARGET_OS_IPHONE
typedef NSString *AVAudioSessionCategory;
FOUNDATION_EXPORT AVAudioSessionCategory const AVAudioSessionCategoryPlayAndRecord;
FOUNDATION_EXPORT AVAudioSessionCategory const AVAudioSessionCategoryPlayback;

typedef NS_OPTIONS(NSUInteger, AVAudioSessionCategoryOptions) {
    AVAudioSessionCategoryOptionMixWithOthers = 0x1,
    AVAudioSessionCategoryOptionDuckOthers = 0x2,
    AVAudioSessionCategoryOptionAllowBluetooth = 0x4,
    AVAudioSessionCategoryOptionDefaultToSpeaker = 0x8,
};

typedef NS_ENUM(NSUInteger, AVAudioSessionRecordPermission) {
    AVAudioSessionRecordPermissionUndetermined = 'undt',
    AVAudioSessionRecordPermissionDenied = 'deny',
    AVAudioSessionRecordPermissionGranted = 'grnt'
};

typedef NS_ENUM(NSUInteger, AVAudioSessionInterruptionType) {
    AVAudioSessionInterruptionTypeBegan = 1,
    AVAudioSessionInterruptionTypeEnded = 0,
};

typedef NS_OPTIONS(NSUInteger, AVAudioSessionInterruptionOptions) {
    AVAudioSessionInterruptionOptionShouldResume = 1
};

FOUNDATION_EXPORT NSNotificationName const AVAudioSessionInterruptionNotification;
FOUNDATION_EXPORT NSString *const AVAudioSessionInterruptionTypeKey;
FOUNDATION_EXPORT NSString *const AVAudioSessionInterruptionOptionKey;

typedef void (^PermissionBlock)(BOOL granted);

@interface AVAudioSession : NSObject
+ (AVAudioSession *)sharedInstance;
@property (readonly) AVAudioSessionCategory category;
@property (readonly) AVAudioSessionCategoryOptions categoryOptions;
- (BOOL)setCategory:(AVAudioSessionCategory)category
        withOptions:(AVAudioSessionCategoryOptions)options
              error:(NSError **)outError;
- (BOOL)setActive:(BOOL)active error:(NSError **)outError;
@property (readonly) AVAudioSessionRecordPermission recordPermission;
- (void)requestRecordPermission:(PermissionBlock)response;
@end
#else
typedef NSString *AVMediaType;
FOUNDATION_EXPORT AVMediaType const AVMediaTypeAudio;

typedef NS_ENUM(NSInteger, AVAuthorizationStatus) {
    AVAuthorizationStatusNotDetermined = 0,
    AVAuthorizationStatusRestricted = 1,
    AVAuthorizationStatusDenied = 2,
    AVAuthorizationStatusAuthorized = 3,
};

@interface AVCaptureDevice : NSObject
+ (AVAuthorizationStatus)authorizationStatusForMediaType:(AVMediaType)mediaType;
+ (void)requestAccessForMediaType:(AVMediaType)mediaType completionHandler:(void (^)(BOOL granted))handler;
@end
#endif

#endif
