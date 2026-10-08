/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header.
 * Under ARC (OS_OBJECT_USE_OBJC) dispatch objects are Objective-C objects. */
#ifndef YGST_STUB_DISPATCH_H
#define YGST_STUB_DISPATCH_H
#include <stddef.h>
#include <stdint.h>
@protocol OS_dispatch_object
@end
@protocol OS_dispatch_queue <OS_dispatch_object>
@end
@protocol OS_dispatch_source <OS_dispatch_object>
@end
@protocol OS_dispatch_semaphore <OS_dispatch_object>
@end
@protocol OS_dispatch_queue_attr <OS_dispatch_object>
@end
typedef id<OS_dispatch_object> dispatch_object_t;
typedef id<OS_dispatch_queue> dispatch_queue_t;
typedef id<OS_dispatch_source> dispatch_source_t;
typedef id<OS_dispatch_semaphore> dispatch_semaphore_t;
typedef id<OS_dispatch_queue_attr> dispatch_queue_attr_t;
typedef const struct dispatch_source_type_s *dispatch_source_type_t;
typedef void (^dispatch_block_t)(void);
typedef long dispatch_once_t;
typedef uint64_t dispatch_time_t;
#define DISPATCH_QUEUE_SERIAL ((dispatch_queue_attr_t)0)
#define DISPATCH_TIME_NOW (0ull)
#define DISPATCH_TIME_FOREVER (~0ull)
#define NSEC_PER_SEC 1000000000ull
#define NSEC_PER_MSEC 1000000ull
#define USEC_PER_SEC 1000000ull
extern const struct dispatch_source_type_s _dispatch_source_type_timer;
#define DISPATCH_SOURCE_TYPE_TIMER (&_dispatch_source_type_timer)
dispatch_queue_t dispatch_queue_create(const char *label, dispatch_queue_attr_t attr);
dispatch_queue_t dispatch_get_main_queue(void);
dispatch_queue_t dispatch_get_global_queue(long identifier, unsigned long flags);
void dispatch_async(dispatch_queue_t queue, dispatch_block_t block);
void dispatch_sync(dispatch_queue_t queue, dispatch_block_t block);
void dispatch_after(dispatch_time_t when, dispatch_queue_t queue, dispatch_block_t block);
dispatch_time_t dispatch_time(dispatch_time_t when, int64_t delta);
void dispatch_once(dispatch_once_t *predicate, dispatch_block_t block);
void dispatch_queue_set_specific(dispatch_queue_t queue, const void *key, void *context, void (*destructor)(void *));
void *dispatch_get_specific(const void *key);
dispatch_source_t dispatch_source_create(dispatch_source_type_t type, uintptr_t handle, unsigned long mask,
                                         dispatch_queue_t queue);
void dispatch_source_set_timer(dispatch_source_t source, dispatch_time_t start, uint64_t interval, uint64_t leeway);
void dispatch_source_set_event_handler(dispatch_source_t source, dispatch_block_t handler);
void dispatch_source_cancel(dispatch_source_t source);
void dispatch_resume(dispatch_object_t object);
dispatch_semaphore_t dispatch_semaphore_create(long value);
long dispatch_semaphore_wait(dispatch_semaphore_t dsema, dispatch_time_t timeout);
long dispatch_semaphore_signal(dispatch_semaphore_t dsema);
#endif
