#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include <mutex>
#include <string>

namespace {
std::mutex gate;
JavaVM *vm=nullptr;
jobject service=nullptr;
jmethodID protectMethod=nullptr;
void *library=nullptr;
using Keys=char *(*)();
using Start=char *(*)(int,char*,char*,int,uintptr_t);
using Stop=void (*)();
Keys keys=nullptr,stats=nullptr;
Start start=nullptr;
Stop stop=nullptr;
bool load(JNIEnv *env) {
    if(library)return true;
    void *candidate=dlopen("libpilotwarp.so",RTLD_NOW|RTLD_LOCAL);
    if(candidate) {
        keys=reinterpret_cast<Keys>(dlsym(candidate,"PilotWarpKeys"));
        stats=reinterpret_cast<Keys>(dlsym(candidate,"PilotWarpStats"));
        start=reinterpret_cast<Start>(dlsym(candidate,"PilotWarpStart"));
        stop=reinterpret_cast<Stop>(dlsym(candidate,"PilotWarpStop"));
        if(keys && stats && start && stop){library=candidate;return true;}
        dlclose(candidate);
    }
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"WARP native backend unavailable");return false;
}
int protectSocket(int fd) {
    JNIEnv *env=nullptr;bool attached=false;
    if(!vm || !service || !protectMethod)return 0;
    if(vm->GetEnv(reinterpret_cast<void **>(&env),JNI_VERSION_1_6)!=JNI_OK) {
#ifdef __ANDROID__
        if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return 0;
#else
        if(vm->AttachCurrentThread(reinterpret_cast<void **>(&env),nullptr)!=JNI_OK)return 0;
#endif
        attached=true;
    }
    bool ok=env->CallBooleanMethod(service,protectMethod,fd)==JNI_TRUE;
    if(env->ExceptionCheck()){env->ExceptionClear();ok=false;}
    if(attached)vm->DetachCurrentThread();return ok ? 1 : 0;
}
jstring result(JNIEnv *env,char *value){if(!value)return env->NewStringUTF("");jstring out=env->NewStringUTF(value);free(value);return out;}
void release(JNIEnv *env){if(service)env->DeleteGlobalRef(service);service=nullptr;protectMethod=nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_dovecoteescapee_byedpi_warp_WarpNative_keys(JNIEnv *env,jobject) {
    std::lock_guard<std::mutex> lock(gate);if(!load(env))return nullptr;return result(env,keys());
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_dovecoteescapee_byedpi_warp_WarpNative_start(JNIEnv *env,jobject,jint fd,jstring config,jstring reserved,jboolean handshakeOnly,jobject vpn) {
    std::lock_guard<std::mutex> lock(gate);
    if(!load(env)) {close(fd);return nullptr;}
    if(service){close(fd);return env->NewStringUTF("WARP already running");}
    env->GetJavaVM(&vm);service=env->NewGlobalRef(vpn);
    jclass type=env->GetObjectClass(vpn);protectMethod=env->GetMethodID(type,"protectWarpSocket","(I)Z");env->DeleteLocalRef(type);
    if(!service||!protectMethod){close(fd);release(env);return nullptr;}
    const char *ipc=env->GetStringUTFChars(config,nullptr);const char *r=env->GetStringUTFChars(reserved,nullptr);
    if(!ipc||!r){if(ipc)env->ReleaseStringUTFChars(config,ipc);if(r)env->ReleaseStringUTFChars(reserved,r);close(fd);release(env);return nullptr;}
    char *error=start(fd,const_cast<char*>(ipc),const_cast<char*>(r),handshakeOnly?1:0,reinterpret_cast<uintptr_t>(&protectSocket));
    env->ReleaseStringUTFChars(config,ipc);env->ReleaseStringUTFChars(reserved,r);
    bool failed=!error || error[0]!=0;
    if(failed){stop();release(env);}
    return result(env,error);
}
extern "C" JNIEXPORT void JNICALL Java_io_github_dovecoteescapee_byedpi_warp_WarpNative_stop(JNIEnv *env,jobject) {
    std::lock_guard<std::mutex> lock(gate);if(library)stop();release(env);
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_dovecoteescapee_byedpi_warp_WarpNative_stats(JNIEnv *env,jobject) {
    std::lock_guard<std::mutex> lock(gate);if(!load(env))return nullptr;return result(env,stats());
}
