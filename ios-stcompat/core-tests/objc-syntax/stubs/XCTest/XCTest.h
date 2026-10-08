/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_XCTEST_H
#define YGST_STUB_XCTEST_H
#import <Foundation/Foundation.h>
@interface XCTestExpectation : NSObject
- (void)fulfill;
@end
typedef void (^XCWaitCompletionHandler)(NSError *error);
@interface XCTestCase : NSObject
- (XCTestExpectation *)expectationWithDescription:(NSString *)description;
- (void)waitForExpectationsWithTimeout:(NSTimeInterval)timeout handler:(XCWaitCompletionHandler)handler;
@end
#define XCTAssertTrue(expression, ...) ((void)(!!(expression)))
#define XCTAssertFalse(expression, ...) ((void)(!(expression)))
#define XCTAssertNotNil(expression, ...) ((void)((expression) != nil))
#define XCTAssertEqual(a, b, ...) ((void)((a) == (b)))
#define XCTAssertEqualObjects(a, b, ...) ((void)[(a) isEqual:(b)])
#endif
