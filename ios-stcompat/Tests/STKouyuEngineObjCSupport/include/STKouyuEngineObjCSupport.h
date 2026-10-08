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

NS_ASSUME_NONNULL_END
