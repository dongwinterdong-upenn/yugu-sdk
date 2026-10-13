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

NS_ASSUME_NONNULL_END
