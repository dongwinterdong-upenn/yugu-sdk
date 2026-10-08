//
//  STKouyuEngine.h
//  STKouyuEngine 平替层（优谷雅言），接口与声通公开头文件一致
//
//  Copyright 2026 优谷雅言 open.shengzhiai.com
//  SPDX-License-Identifier: Apache-2.0
//

#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#else
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#endif

//! 包版本号，2.0
FOUNDATION_EXPORT double STKouyuEngineVersionNumber;

//! 包版本字符串
FOUNDATION_EXPORT const unsigned char STKouyuEngineVersionString[];

#import "KYTestEngine.h"


