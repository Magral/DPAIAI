/* TV-only UDP/443 guard. TCP and DNS are forwarded unchanged. */
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
#include <stddef.h>

ssize_t __real_sendto(int fd, const void *buffer, size_t size, int flags,
                      const struct sockaddr *address, socklen_t length);

ssize_t __wrap_sendto(int fd, const void *buffer, size_t size, int flags,
                      const struct sockaddr *address, socklen_t length)
{
    int type = 0;
    socklen_t type_size = sizeof(type);
    unsigned short port = 0;
    if (address && address->sa_family == AF_INET && length >= sizeof(struct sockaddr_in))
        port = ((const struct sockaddr_in *)address)->sin_port;
    else if (address && address->sa_family == AF_INET6 && length >= sizeof(struct sockaddr_in6))
        port = ((const struct sockaddr_in6 *)address)->sin6_port;
    if (port == htons(443) && getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_size) == 0 && type == SOCK_DGRAM) {
        errno = EACCES;
        return -1;
    }
    return __real_sendto(fd, buffer, size, flags, address, length);
}

/* ByeDPI 17.x connects UDP sockets and then uses send(), rather than sendto().
 * Reject the association before any payload/fake is sent. TCP hot-path send()
 * is deliberately untouched.
 */
int __real_connect(int fd, const struct sockaddr *address, socklen_t length);
int __wrap_connect(int fd, const struct sockaddr *address, socklen_t length)
{
    unsigned short port = 0;
    int type = 0;
    socklen_t type_size = sizeof(type);
    if (address && address->sa_family == AF_INET && length >= sizeof(struct sockaddr_in))
        port = ((const struct sockaddr_in *)address)->sin_port;
    else if (address && address->sa_family == AF_INET6 && length >= sizeof(struct sockaddr_in6))
        port = ((const struct sockaddr_in6 *)address)->sin6_port;
    if (port == htons(443) && getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_size) == 0 && type == SOCK_DGRAM) {
        errno = EACCES;
        return -1;
    }
    return __real_connect(fd,address,length);
}
