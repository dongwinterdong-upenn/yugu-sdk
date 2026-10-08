/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_STDIO_H
#define YGST_STUB_STDIO_H
#include <stddef.h>
#include <stdarg.h>
typedef struct __sFILE FILE;
int snprintf(char *str, size_t size, const char *format, ...) __attribute__((format(printf, 3, 4)));
int printf(const char *format, ...) __attribute__((format(printf, 1, 2)));
int vsnprintf(char *str, size_t size, const char *format, va_list ap);
#endif
