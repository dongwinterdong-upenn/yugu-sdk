// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// Objective-C side of the XCTest suite. SwiftPM does not run Objective-C test targets, so the
// Objective-C checks live in this support target, which `swift build` compiles (that compiles the
// three import forms of an existing app), and the Swift test target calls them.
// Written for a macOS runner (ci/ios-stcompat-macos.sh). Not executed on the Linux CI.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Each check returns nil on success, otherwise the first expectation that failed.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckConstantsAndLog(void);
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckDelegateAndProvision(NSTimeInterval timeout);
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckSkegnLocalError(NSTimeInterval timeout);
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckSkegnAgainstMock(NSString *baseURL, NSString *specDir,
                                                                NSTimeInterval timeout);

/// realtime_feedback (2.1.0) against the mock platform: a stream session fed with zh_progress.wav.
/// Progress through the result block and the delegate, the final like REST, one evaluation billed.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeProgress(NSString *baseURL, NSString *specDir,
                                                                NSTimeInterval timeout);
/// mockFault ws-refuse: the handshake is refused, the round is evaluated over REST with the same tokenId.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeRefusedFallsBack(NSString *baseURL, NSString *specDir,
                                                                        NSTimeInterval timeout);
/// mockFault ws-kill-after:3: the connection dies while reading, REST at stopEngine with the same tokenId.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeKilledFallsBack(NSString *baseURL, NSString *specDir,
                                                                       NSTimeInterval timeout);
/// mockFault ws-error:50200 on the first connection: after end the round reconnects and replays,
/// no word is delivered twice, one evaluation billed.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeReconnectAfterEnd(NSString *baseURL, NSString *specDir,
                                                                         NSTimeInterval timeout);
/// mockFault progress-mismatch:7: the item of unit 7 is read but not matched.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeProgressMismatch(NSString *baseURL, NSString *specDir,
                                                                        NSTimeInterval timeout);
/// mockFault progress-off: no eof 0 callback, the final as usual.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeProgressOff(NSString *baseURL, NSString *specDir,
                                                                   NSTimeInterval timeout);
/// cancelEngine during a realtime round: no callback, nothing evaluated.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeCancel(NSString *baseURL, NSString *specDir,
                                                              NSTimeInterval timeout);

/// 2.1.1 stream upload against the mock platform, without realtime_feedback: the stream session goes
/// over the compat WebSocket while it is fed, only the final arrives, one evaluation billed.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckStreamUploadDefault(NSString *baseURL, NSString *specDir,
                                                                   NSTimeInterval timeout);
/// YuguCompat.streamUpload NO: one request after stopEngine, as in 2.1.0.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckStreamUploadOff(NSString *baseURL, NSString *specDir,
                                                               NSTimeInterval timeout);
/// mockFault ws-refuse without realtime_feedback: one request after stopEngine with the same tokenId.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckStreamRefusedFallsBack(NSString *baseURL, NSString *specDir,
                                                                      NSTimeInterval timeout);
/// realtime_feedback with YuguCompat.streamUpload NO: streamed with progress, as in 2.1.0.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckRealtimeWithStreamUploadOff(NSString *baseURL, NSString *specDir,
                                                                           NSTimeInterval timeout);
/// KYStartEngineConfig sdkLogEnable YES with logLevel 2: INFO in sdkLogPath with one timing line per
/// evaluation and timing=1 in the request; logLevel 1, the default: neither.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckLogLevelInfoTiming(NSString *baseURL, NSString *specDir,
                                                                  NSTimeInterval timeout);
/// Against core-tests/integration/timing_server.py: a streamed paragraph with a 1 MB final, the
/// server accepting or declining permessage-deflate; the final arrives whole, without its timing
/// object, the timing line carries the server timing. report: what URLSessionWebSocketTask offered
/// and how the final went over the wire.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckTimingServerWebSocket(NSString *baseURL, NSString *specDir, BOOL deflate,
                                                                     NSTimeInterval timeout,
                                                                     NSString *_Nullable *_Nullable report);
/// Against timing_server.py over REST: gzip body, Server-Timing header, timing=1, the split of
/// NSURLSessionTaskMetrics in the timing line. report: what went over the wire.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckTimingServerRest(NSString *baseURL, NSString *specDir,
                                                                NSTimeInterval timeout,
                                                                NSString *_Nullable *_Nullable report);

/// 2.2.0 without network: an unsupported coreType through both start methods answers 60003 with
/// "不支持的评测内核：<coreType>" (coreTypeNS trimmed, else the KYTestType's string or value, "未指定" for
/// no testConfig) in the result and finishBlock JSON, with one WARN line naming it and errId 60003.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckUnsupportedCoreType(NSTimeInterval timeout);
/// 2.2.0 skegn_start with an unsupported, blank or missing coreType: the 60003 JSON at once, skegn_feed
/// and skegn_stop -1 with last errors 21 and 24, one WARN line.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckSkegnUnsupportedCoreType(NSTimeInterval timeout);
/// 2.2.0 YuguCompat.throwOnUnsupportedCoreType YES: both start methods throw NSInvalidArgumentException
/// with the 60003 text, nothing is called back, other errors keep their callbacks; skegn_start returns
/// -1 with last error 60003. Switches it back to NO.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckThrowOnUnsupportedCoreType(NSTimeInterval timeout);
/// 2.2.0 TtsClient against the mock platform: fields, absolute URL, Idempotency-Key, retries, NSError.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckTtsClient(NSString *baseURL, NSTimeInterval timeout);
/// 2.2.0 ReportClient against the mock platform: fields, pending, waitForFinalMs, NSError.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckReportClient(NSString *baseURL, NSTimeInterval timeout);
/// 2.2.0 clients without an initialized engine (60007) and with bad explicit settings (90010); skegn_new
/// gives its credentials and skegn_delete takes them back. No mock needed.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckClientsWithoutEngine(NSTimeInterval timeout);

/// 2.3.0 YuguCompat.maxPendingEvaluations: 0 by default, clamped to 0..16, one INFO line per change of the
/// stored value; pendingEvaluations 0 without evaluations. No mock needed. Leaves the switch at 0.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineSetting(void);
/// 2.3.0, the default 0: a start after stopEngine and before the result is refused as in 2.2.0 through
/// both start methods (finishBlock NO with errId 60008, onStartFailBlock "engine is busy", no delegate
/// call, one WARN line each, no request); after the result the next start works.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineDefaultOff(NSString *baseURL, NSString *specDir,
                                                                  NSTimeInterval timeout);
/// 2.3.0 maxPendingEvaluations 3: A, B and C streamed back to back with realtime_feedback, each started
/// from the previous one's onRecordEndBlock, the mock answering C first and A last. Each final once, to
/// its own blocks, with its own tokenId, in completion order; reading progress only to its own blocks,
/// until its own final, the tail after its end frame included (A's arrives after B was started);
/// pendingEvaluations and getEngineStatus in the record-end blocks; one INFO line per start; each
/// evaluated once over its own connection.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineThreeInFlight(NSString *baseURL, NSString *specDir,
                                                                     NSTimeInterval timeout);
/// 2.3.0 maxPendingEvaluations 1: a third start is refused while one session waits for its result and one
/// records, and while two wait; it is accepted again after a result.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineCap(NSString *baseURL, NSString *specDir, NSTimeInterval timeout);
/// 2.3.0 cancelEngine while two sessions wait for their results and one records: no callback afterwards,
/// pendingEvaluations 0 at once, nothing evaluated for the one recording, the next start works.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineCancel(NSString *baseURL, NSString *specDir,
                                                              NSTimeInterval timeout);
/// 2.3.0 a pipelined session whose WebSocket fails (mockFault ws-kill-after:1, before end) is evaluated in
/// one request with its tokenId while the next session records, and still delivers.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineFallback(NSString *baseURL, NSString *specDir,
                                                                NSTimeInterval timeout);
/// 2.3.0 audioPath sessions count as waiting for their results from the start: with maxPendingEvaluations
/// 1 two files evaluate side by side, a third start (file or recording) is refused until a result.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckPipelineFileSessions(NSString *baseURL, NSString *specDir,
                                                                    NSTimeInterval timeout);
/// 2.3.0 ReportClient against core-tests/integration/timing_server.py (reportStatus partial is not in the
/// shared mock): waitForFinalMs looks up through pending and partial and stops at final; partial without
/// waiting, or when the time runs out, is a result with isFinal NO and the AI summary and suggestions.
FOUNDATION_EXPORT NSString *_Nullable YGSTCheckReportPartial(NSString *baseURL, NSTimeInterval timeout);

NS_ASSUME_NONNULL_END
