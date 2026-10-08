/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_STDLIB_H
#define YGST_STUB_STDLIB_H
#include <stddef.h>
#include <stdint.h>
void *malloc(size_t size);
void *calloc(size_t count, size_t size);
void *realloc(void *ptr, size_t size);
void free(void *ptr);
void qsort(void *base, size_t nel, size_t width, int (*compar)(const void *, const void *));
double strtod(const char *s, char **end);
long long strtoll(const char *s, char **end, int base);
unsigned long strtoul(const char *s, char **end, int base);
int atoi(const char *s);
void arc4random_buf(void *buf, size_t nbytes);
uint32_t arc4random(void);
void abort(void) __attribute__((noreturn));
#endif
