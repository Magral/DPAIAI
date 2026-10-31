/* Separate command-only engine; legacy libbyedpi JNI remains intact. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/socket.h>
#include "params.h"
#include "proxy.h"
extern int parse_args(int argc, char **argv);
extern int init(void);

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_MobileProxy_jniCreateSocketWithCommandLine(JNIEnv *env, jobject self, jobjectArray args)
{
    (void) self;
    jsize count = (*env)->GetArrayLength(env, args);
    if (count < 1 || count > 512) return -1;
    char **argv = calloc((size_t)count + 1, sizeof(char *));
    if (!argv) return -1;
    for (jsize i = 0; i < count; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if (!arg) return -1;
        const char *utf = (*env)->GetStringUTFChars(env, arg, 0);
        if (!utf) return -1;
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, arg, utf);
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) return -1;
    }
    if (parse_args(count, argv) != 0 || init() < 0) return -1;
    signal(SIGPIPE, SIG_IGN);
    return listen_socket(&params.laddr);
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_MobileProxy_jniStartProxy(JNIEnv *env, jobject self, jint fd)
{
    (void) env; (void) self;
    return start_event_loop(fd);
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_MobileProxy_jniStopProxy(JNIEnv *env, jobject self, jint fd)
{
    (void) env; (void) self;
    return shutdown(fd, SHUT_RDWR);
}
