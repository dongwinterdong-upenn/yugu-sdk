/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header.
 * The BSD socket API of the Darwin SDK as the test gate (Tests/STKouyuEngineObjCSupport) uses it. */
#ifndef YGST_STUB_SYS_SOCKET_H
#define YGST_STUB_SYS_SOCKET_H
#include <stddef.h>
#include <stdint.h>
typedef uint32_t socklen_t;
typedef uint8_t sa_family_t;
typedef long ssize_t;
struct sockaddr {
    uint8_t sa_len;
    sa_family_t sa_family;
    char sa_data[14];
};
#define AF_INET 2
#define SOCK_STREAM 1
#define SOL_SOCKET 0xffff
#define SO_REUSEADDR 0x0004
#define SO_NOSIGPIPE 0x1022
#define SHUT_RD 0
#define SHUT_WR 1
#define SHUT_RDWR 2
int socket(int domain, int type, int protocol);
int bind(int socket, const struct sockaddr *address, socklen_t address_len);
int listen(int socket, int backlog);
int accept(int socket, struct sockaddr *address, socklen_t *address_len);
int connect(int socket, const struct sockaddr *address, socklen_t address_len);
int getsockname(int socket, struct sockaddr *address, socklen_t *address_len);
int setsockopt(int socket, int level, int option_name, const void *option_value, socklen_t option_len);
ssize_t recv(int socket, void *buffer, size_t length, int flags);
ssize_t send(int socket, const void *buffer, size_t length, int flags);
int shutdown(int socket, int how);
#endif
