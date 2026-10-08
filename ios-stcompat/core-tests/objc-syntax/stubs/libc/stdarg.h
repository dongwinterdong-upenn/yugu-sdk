/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_STDARG_H
#define YGST_STUB_STDARG_H
typedef __builtin_va_list va_list;
#define va_start(ap, param) __builtin_va_start(ap, param)
#define va_end(ap) __builtin_va_end(ap)
#define va_arg(ap, type) __builtin_va_arg(ap, type)
#define va_copy(d, s) __builtin_va_copy(d, s)
#endif
