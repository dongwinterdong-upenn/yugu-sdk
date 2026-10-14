// Copyright 2026 优谷雅言 open.shengzhiai.com
// SPDX-License-Identifier: Apache-2.0
//
// A TCP relay between KYTestEngine and the mock platform for the 2.3.0 pipelined checks. Every answer
// of the platform waits in the gate until the check lets it through, so that "A's result comes after B
// was refused" is a step of the check instead of a delay of the mock that a slow runner can outlast.
// An answer is the final frame of a WebSocket round (a text frame that is neither an event nor eof 0
// progress; the mock sends frames unmasked and uncompressed) or the first response on a connection; it
// belongs to the idempotencyKey of the handshake or the Idempotency-Key header of the first request.
// Everything else is relayed at once, a later request on a kept-alive connection included: the checks
// let such a token through as soon as they start its session. In process, BSD sockets on 127.0.0.1, so it runs on macOS and in the iOS simulator.
// Written for a macOS runner (ci/ios-stcompat-macos.sh). Not executed on the Linux CI.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface YGSTTestGate : NSObject
/// Listens on 127.0.0.1 and relays every connection to the mock at mockBaseURL (http://127.0.0.1:PORT).
+ (nullable instancetype)gateToMock:(NSString *)mockBaseURL error:(NSString *_Nullable *_Nullable)error;
/// http://127.0.0.1:<gate port>, the platform address of the engine under test.
@property (nonatomic, readonly, copy) NSString *baseURL;
/// The answer of tokenId goes on to the engine, now or when it arrives.
- (void)letThrough:(NSString *)tokenId;
/// The answer of tokenId reached the gate and waits.
- (BOOL)isWaiting:(NSString *)tokenId;
/// The answer of tokenId went on to the engine.
- (BOOL)isRelayed:(NSString *)tokenId;
/// What happened, one event per line with the milliseconds since the gate opened, for failure messages.
- (NSString *)report;
/// Stops listening and ends every connection; answers still waiting are dropped. Idempotent.
- (void)close;
@end

NS_ASSUME_NONNULL_END
