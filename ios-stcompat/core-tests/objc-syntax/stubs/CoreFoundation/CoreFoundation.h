/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_COREFOUNDATION_H
#define YGST_STUB_COREFOUNDATION_H
#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdbool.h>
#include <limits.h>
#include <TargetConditionals.h>
typedef const void *CFTypeRef;
typedef unsigned long CFTypeID;
typedef unsigned long CFOptionFlags;
typedef long CFIndex;
typedef const struct __CFBoolean *CFBooleanRef;
typedef const struct __CFString *CFStringRef;
extern const CFBooleanRef kCFBooleanTrue;
extern const CFBooleanRef kCFBooleanFalse;
CFTypeID CFGetTypeID(CFTypeRef cf);
CFTypeID CFBooleanGetTypeID(void);
CFTypeRef CFRetain(CFTypeRef cf);
void CFRelease(CFTypeRef cf);
#endif
