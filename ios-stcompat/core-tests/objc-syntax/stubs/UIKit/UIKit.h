/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_UIKIT_H
#define YGST_STUB_UIKIT_H
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#define UIKIT_EXTERN extern
@interface UIColor : NSObject <NSCopying>
@property (class, nonatomic, readonly) UIColor *grayColor;
@property (class, nonatomic, readonly) UIColor *greenColor;
@property (class, nonatomic, readonly) UIColor *orangeColor;
@end
UIKIT_EXTERN NSAttributedStringKey const NSForegroundColorAttributeName;
@interface UILabel : NSObject
@property (nonatomic, copy) NSAttributedString *attributedText;
@end
@interface UIDevice : NSObject
+ (UIDevice *)currentDevice;
@property (nonatomic, readonly, strong) NSUUID *identifierForVendor;
@end
#endif
