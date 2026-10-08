/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_TARGETCONDITIONALS_H
#define YGST_STUB_TARGETCONDITIONALS_H
#if defined(__ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__) || defined(__ENVIRONMENT_IOS__)
#define TARGET_OS_IPHONE 1
#define TARGET_OS_IOS 1
#define TARGET_OS_OSX 0
#else
#define TARGET_OS_IPHONE 0
#define TARGET_OS_IOS 0
#define TARGET_OS_OSX 1
#endif
#define TARGET_OS_MAC 1
#define TARGET_OS_MACCATALYST 0
#define TARGET_OS_SIMULATOR 0
#endif
