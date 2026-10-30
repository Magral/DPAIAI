#include <assert.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
static int forwarded;
ssize_t __real_sendto(int fd, const void *p, size_t n, int flags, const struct sockaddr *a, socklen_t l) {
    (void)fd; (void)p; (void)flags; (void)a; (void)l; forwarded++; return (ssize_t)n;
}
static int connections;
int __real_connect(int fd, const struct sockaddr *a, socklen_t l) {
    (void)fd; (void)a; (void)l; connections++; return 0;
}
int __wrap_connect(int,const struct sockaddr *,socklen_t);
ssize_t __wrap_sendto(int,const void *,size_t,int,const struct sockaddr *,socklen_t);
int main(void) {
    int udp = socket(AF_INET,SOCK_DGRAM,0), tcp = socket(AF_INET,SOCK_STREAM,0);
    assert(udp >= 0 && tcp >= 0);
    struct sockaddr_in v4 = { .sin_family=AF_INET, .sin_port=htons(443) };
    struct sockaddr_in6 v6 = { .sin6_family=AF_INET6, .sin6_port=htons(443) };
    assert(__wrap_sendto(udp,"x",1,0,(struct sockaddr *)&v4,sizeof(v4)) == -1 && errno == EACCES);
    assert(__wrap_sendto(udp,"x",1,0,(struct sockaddr *)&v6,sizeof(v6)) == -1 && errno == EACCES);
    assert(forwarded == 0);
    v4.sin_port=htons(53);
    assert(__wrap_sendto(udp,"dns",3,0,(struct sockaddr *)&v4,sizeof(v4)) == 3);
    v4.sin_port=htons(443);
    assert(__wrap_sendto(tcp,"tls",3,0,(struct sockaddr *)&v4,sizeof(v4)) == 3);
    assert(__wrap_sendto(udp,"x",1,0,NULL,0) == 1);
    assert(forwarded == 3);
    assert(__wrap_connect(udp,(struct sockaddr *)&v4,sizeof(v4)) == -1 && errno == EACCES);
    assert(__wrap_connect(udp,(struct sockaddr *)&v6,sizeof(v6)) == -1 && errno == EACCES);
    assert(connections == 0);
    v4.sin_port=htons(53);
    assert(__wrap_connect(udp,(struct sockaddr *)&v4,sizeof(v4)) == 0);
    v4.sin_port=htons(443);
    assert(__wrap_connect(tcp,(struct sockaddr *)&v4,sizeof(v4)) == 0);
    assert(connections == 2);
    close(udp); close(tcp);
    puts("UDP/443 rejected for both address families; DNS, TCP/443 and default send preserved");
}
