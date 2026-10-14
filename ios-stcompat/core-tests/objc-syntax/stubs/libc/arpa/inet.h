/* Stub for the Linux syntax check of the Objective-C layer. Not an Apple header. The Darwin SDK
 * defines these as macros over the byte swap builtins; functions type-check the same way. */
#ifndef YGST_STUB_ARPA_INET_H
#define YGST_STUB_ARPA_INET_H
#include <netinet/in.h>
uint16_t htons(uint16_t hostshort);
uint32_t htonl(uint32_t hostlong);
uint16_t ntohs(uint16_t netshort);
uint32_t ntohl(uint32_t netlong);
#endif
