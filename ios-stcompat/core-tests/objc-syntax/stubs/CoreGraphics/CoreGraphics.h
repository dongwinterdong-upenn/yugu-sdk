/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_COREGRAPHICS_H
#define YGST_STUB_COREGRAPHICS_H
#include <CoreFoundation/CoreFoundation.h>
#if defined(__LP64__) && __LP64__
typedef double CGFloat;
#else
typedef float CGFloat;
#endif
typedef struct CGPoint { CGFloat x; CGFloat y; } CGPoint;
typedef struct CGSize { CGFloat width; CGFloat height; } CGSize;
typedef struct CGRect { CGPoint origin; CGSize size; } CGRect;
#endif
