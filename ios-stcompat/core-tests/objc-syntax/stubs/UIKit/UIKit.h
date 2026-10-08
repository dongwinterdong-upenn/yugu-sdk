/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_UIKIT_H
#define YGST_STUB_UIKIT_H
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#define UIKIT_EXTERN extern
@interface UIDevice : NSObject
+ (UIDevice *)currentDevice;
@property (nonatomic, readonly, strong) NSUUID *identifierForVendor;
@end
#endif
