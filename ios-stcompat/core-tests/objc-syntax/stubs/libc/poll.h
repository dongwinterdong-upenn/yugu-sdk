/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_POLL_H
#define YGST_STUB_POLL_H
struct pollfd {
    int fd;
    short events;
    short revents;
};
typedef unsigned int nfds_t;
#define POLLIN 0x0001
int poll(struct pollfd fds[], nfds_t nfds, int timeout);
#endif
