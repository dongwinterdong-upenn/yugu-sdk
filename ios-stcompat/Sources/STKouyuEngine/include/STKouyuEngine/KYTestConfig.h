//
//  KYTestConfig.h
//  STKouyuEngine 平替层（优谷雅言）
//
//  单次评测参数。声明与声通公开头文件一致，发送到平台的表单字段见 README 的参数映射表。
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#import <Foundation/Foundation.h>
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#else
#import <CoreGraphics/CoreGraphics.h>
#endif

// 音频压缩配置，平替层上传无损 WAV，此项不生效
typedef enum : NSUInteger {
    KYCompress_Speex = 1,     // 压缩
    KYCompress_Raw,       // 不压缩
} KYCompressType;


// 评测内核，coreTypeNS 为空时按此项映射为声通 coreType 字符串
typedef enum : NSUInteger {
    KYTestType_Word,              // word.eval
    KYTestType_Sentence,          // sent.eval
    KYTestType_Paragraph,         // para.eval
    KYTestType_Open,              // open.eval，平台不支持，回调 errId 60003
    KYTestType_Choice,            // choice.rec，平台不支持
    KYTestType_Asr,               // asr.rec，平台不支持
    KYTestType_Align,              // align.eval，平台不支持
    KYTestType_Word_Pro,           // word.eval.pro
    KYTestType_Sentence_Pro,       // sent.eval.pro
    KYTestType_Word_Cn,            // word.eval.cn
    KYTestType_Sentence_Cn,        // sent.eval.cn
    KYTestType_Paragraph_Cn,        // para.eval.cn
    KYTestType_AsrEval,            // asr.eval，平台不支持
    KYTestType_Word_fr,           // 法语，平台不支持
    KYTestType_Sentence_fr,       // 法语，平台不支持
    KYTestType_Paragraph_fr,       // 法语，平台不支持
    KYTestType_Word_kr,           // 韩语，平台不支持
    KYTestType_Sentence_kr,       // 韩语，平台不支持
    KYTestType_Paragraph_kr,       // 韩语，平台不支持
    KYTestType_Word_jp,           // 日语，平台不支持
    KYTestType_Sentence_jp,       // 日语，平台不支持
    KYTestType_Paragraph_jp,       // 日语，平台不支持
    KYTestType_Wordspell,          // 平台不支持
} KYTestType;

// 音素字典，设置后以 dict_type 发送
typedef enum : NSUInteger {
    KYPhonemeOption_CMU = 1,
    KYPhonemeOption_KK,
    KYPhonemeOption_IPA88,
} KYPhonemeOption;

// 年龄段，设置后以 agegroup 发送
typedef enum : NSUInteger {
    KYAgeGroupSupportOption_Junior = 1,     // 3 到 6 岁
    KYAgeGroupSupportOption_Middle,         // 6 到 12 岁
    KYAgeGroupSupportOption_Senior,         // 12 岁以上
} KYAgeGroupSupportOption;

// 开放题型，设置后以 qType 发送
typedef enum : NSUInteger {
    KYQType_PassageReading,         // 短文朗读
    KYQType_FollowReadPassage,      // 短文跟读
    KYQType_SentenceTranslation,    // 句子翻译
    KYQType_ParagraphTranslation,   // 段落翻译
    KYQType_RepeatStory,            // 故事复述
    KYQType_LookAndSay,             // 看图说话
    KYQType_SituationalReply,       // 情景问答
    KYQType_OralComposition,        // 口头作文
    KYQType_SentenceReading,        // 句子朗读
} KYQType;

// 使用场景，Home 以 mode=home 发送
typedef enum : NSUInteger {
    KYModeType_School,
    KYModeType_Home,
} KYModeType;

extern NSString *const KYEngineCloud;      // @"cloud"

extern NSString *const KYEngineNative;   // @"native"，平替层按云端评测

@interface KYTestConfigTemp : NSObject

@property (nonatomic, assign) KYTestType coreType;

@property (nonatomic, copy) NSString *coreTypeNS;

@property (nonatomic, copy) NSString *coreProvideType;

@end


@interface KYTestConfig : NSObject

// 可选，回调音强 sound_intensity，需要 KYStartEngineConfig.vadEnable 为 YES，默认 NO
@property (nonatomic, assign) BOOL soundIntensityEnable;

// 可选，cloud 或 native，平替层都按云端评测
@property (nonatomic, copy) NSString *coreProvideType;

// 不生效
@property (nonatomic, copy) NSString *serialNumber;

// 可选，用户标识，写入结果 JSON 的 userId
@property (nonatomic, copy) NSString *userId;



/****************  audio参数  *****************/

// 可选，默认 wav。录音固定为 16 kHz 单声道 16 位 WAV，audioPath 与 feed 按此类型上传
@property (nonatomic, copy) NSString *audioType;

// 可选，feed 的 PCM 声道数，默认 1
@property (nonatomic, assign) NSInteger channel;

// 可选，feed 的 PCM 每采样字节数，默认 2
@property (nonatomic, assign) NSInteger sampleBytes;

// 可选，feed 的 PCM 采样率，默认 16000
@property (nonatomic, assign) NSInteger sampleRate;

// 不生效
@property (nonatomic, assign) NSInteger quality;

// 不生效
@property (nonatomic, assign) NSInteger complexity;

// 不生效，上传无损 WAV
@property (nonatomic, assign) KYCompressType compress;

// 不生效
@property (nonatomic, assign) BOOL vbr;

// 不生效
@property (nonatomic, assign) NSInteger max_ogg_delay;

// 可选，录音保存目录，默认 Documents/record/
@property (nonatomic, copy) NSString *recordPath;

// 可选，录音文件名，默认 <tokenId>.wav。写 .mp3 文件名时内容仍为 WAV
@property (nonatomic, copy) NSString *recordName;

// 可选，评测已有音频文件的绝对路径，开始后立即上传评测
@property (nonatomic, copy) NSString *audioPath;

// 可选，YES 时不录音，由 feedAudioData:audioLength: 送入音频，stopEngine 后评测，默认 NO
@property (nonatomic, assign) BOOL isStream;




/******************************************/



/****************  proto参数  *****************/

// 不生效，固定为 HTTPS
@property (nonatomic, copy) NSString *protocol;

/******************************************/

/****************  request参数  *****************/

// 可选，结果 JSON 带 params，默认 NO
@property (nonatomic, assign) BOOL getParam;

// 评测内核，coreTypeNS 为空时生效
@property (nonatomic, assign) KYTestType coreType;

// 可选，字符串内核，优先于 coreType
@property (nonatomic, copy) NSString *coreTypeNS;

// 评测文本，以 refText 发送。为空且 refPinyin 也为空时回调 errId 60006
@property (nonatomic, copy) NSString *refText;

// 不生效，align.eval 平台不支持
@property (nonatomic, copy) NSString *refAudio;

// 可选，YES 时发送 attachAudioUrl=1，评测结果带 audioUrl 录音下载地址，保留 7 天，默认 NO
@property (nonatomic, assign) BOOL attachAudioUrl;

// 可选，设置后以 dict_type 发送 CMU、KK 或 IPA88
@property (nonatomic, assign) KYPhonemeOption phonemeOption;

// 可选，YES 时发送 phoneme_output=1，默认 YES
@property (nonatomic, assign) BOOL phoneme_output;

// 可选，设置后以 agegroup 发送 1、2 或 3
@property (nonatomic, assign) KYAgeGroupSupportOption ageGroup;

// 可选，Home 时发送 mode=home
@property (nonatomic, assign) KYModeType mode;

// 可选，YES 时发送 paragraph_need_word_score=1，默认 NO。para.eval 与 para.eval.cn 一律发送
@property (nonatomic, assign) BOOL isParagraphNeedWordScore;

// 可选，分制，非 0 时以 scale 发送
@property (nonatomic, assign) CGFloat scale;

// 可选，精度，非 0 时以 precision 发送
@property (nonatomic, assign) CGFloat precision;

// 可选，松紧度，非 0 时以 slack 发送
@property (nonatomic, assign) CGFloat slack;

// 可选，以 keywords 发送
@property (nonatomic, copy) NSString *keywords;

// 可选，非 0 时以 qType 发送
@property (nonatomic, assign) KYQType qType;

// 可选，序列化为 JSON 后以 customized_lexicon 发送
@property (nonatomic, strong) NSDictionary *customized_lexicon;

// 可选，每个键值作为一个表单字段发送，同名时覆盖映射字段
@property (nonatomic, strong) NSDictionary *customParams;

// 可选，YES 时发送 phoneme_diagnosis=1
@property (nonatomic, assign) BOOL phoneme_diagnosis;

// 可选，本次说话结束判定的静音时长，单位 10ms，优先于 KYStartEngineConfig.seek
@property (nonatomic, assign) CGFloat seek;

// 可选，开始说话后保持说话状态的最短时长，单位 10ms，默认 0
@property (nonatomic, assign) CGFloat ref_length;

// 可选，YES 时只有 stopEngine 才结束本次评测，VAD 与 duration 不自动出结果，默认 NO
@property (nonatomic, assign) BOOL forceRecord;

// 可选，结果 errId 在 errIds 里时用同一个 tokenId 重新提交，最多 2 次，默认 NO
@property (nonatomic, assign) BOOL autoRetry;

// 可选，触发 autoRetry 的 errId，默认 20009
@property (nonatomic, strong) NSArray *errIds;

// 不生效，平台整段评测不返回中间结果
@property (nonatomic, assign) BOOL realtime_feedback;

// 可选，以 negativeReftext 发送
@property (nonatomic, copy) NSString *negativeReftext;

// 可选，以 dict_dialect 发送
@property (nonatomic, copy) NSString *dict_dialect;

// 可选，YES 时发送 detect_nonscorable=1
@property (nonatomic, assign) BOOL detect_nonscorable;

// 可选，序列化为 JSON 后以 customized_pron 发送
@property (nonatomic, strong) NSDictionary *customized_pron;

// 可选，YES 时发送 output_rawtext=1
@property (nonatomic, assign) BOOL output_rawtext;

// 可选，YES 时以 vad_detction=1 发送，字段名与安卓平替层一致
@property (nonatomic, assign) BOOL vad_detection;

// 可选，序列化为 JSON 数组后以 keypoints 发送
@property (nonatomic, strong) NSArray *keypoints;

// 可选，非 0 时以 keypoints_weight 发送
@property (nonatomic, assign) CGFloat keypoints_weight;

// 可选，序列化为 JSON 数组后以 negative_keypoints 发送
@property (nonatomic, strong) NSArray *negative_keypoints;

// 可选，本次请求的超时，单位秒，非 0 时优先于 KYStartEngineConfig.serverTimeout
@property (nonatomic, assign) CGFloat serverTimeout;

// 可选，最长录音时长，单位毫秒，到时自动结束录音
@property (nonatomic, assign) CGFloat duration;

// 可选，录音倒计时回调间隔，单位毫秒，默认 100
@property (nonatomic, assign) CGFloat durationInterval;

// 可选，以 refPinyin 发送
@property (nonatomic, copy) NSString *refPinyin;

// 可选，YES 时发送 punctuate=1
@property (nonatomic, assign) BOOL punctuate;

// 不生效，请求按优谷雅言签名规则鉴权
@property (nonatomic, strong) NSDictionary *customized_sig;

// 不生效，请求按优谷雅言签名规则鉴权
@property (nonatomic, copy) NSString *customized_sig_url;

// 可选，VAD 与音强回调的最小间隔，单位毫秒，默认每段音频回调一次
@property (nonatomic, assign) CGFloat recordCallbackInterval;

// 可选，非 0 时以 readtype_diagnosis 发送
@property (nonatomic, assign) CGFloat readtype_diagnosis;

// 可选，非 0 时以 itn 发送
@property (nonatomic, assign) CGFloat itn;

// 可选，序列化为 JSON 后以 request 发送
@property (nonatomic, strong) NSDictionary *request;

// 可选，YES 时发送 blend_phoneme_enable=1
@property (nonatomic, assign) BOOL blend_phoneme_enable;

@end
