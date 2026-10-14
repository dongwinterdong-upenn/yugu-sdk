/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. */
#ifndef YGST_STUB_NETINET_IN_H
#define YGST_STUB_NETINET_IN_H
#include <sys/socket.h>
typedef uint16_t in_port_t;
typedef uint32_t in_addr_t;
struct in_addr {
    in_addr_t s_addr;
};
struct sockaddr_in {
    uint8_t sin_len;
    sa_family_t sin_family;
    in_port_t sin_port;
    struct in_addr sin_addr;
    char sin_zero[8];
};
#define INADDR_LOOPBACK ((in_addr_t)0x7f000001)
#endif
