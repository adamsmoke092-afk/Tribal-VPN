// tunnel_bridge.cpp
//
// JNI bridge for Phase 5 (badvpn-udpgw) only. Phase 4 (hev-socks5-tunnel) now
// goes through hev's own JNI binding class, hev.htproxy.TProxyService - see
// NativeTunnelBridge.kt. Loading this .so still pulls libhev-socks5-tunnel.so
// in as a DT_NEEDED dependency, and its JNI_OnLoad (found via dlsym, which
// searches dependencies) registers those methods - so this file stays out of
// Phase 4's data path entirely; the fd stays owned by TribalVpnService.
//
// STATUS: Phase 5 is still a stub. These functions log a clear "not
// implemented" message and return failure rather than pretending to succeed -
// consistent with this project's no-fabricated-state rule
// (README_DATA_INTEGRITY.md).

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "TribalTunnelBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Phase 5: start the UDP gateway client (badvpn-udpgw protocol).
 *
 * @return 0 on success, negative error code otherwise. Currently always
 *         returns -1 since badvpn-udpgw is not vendored yet.
 */
JNIEXPORT jint JNICALL
Java_com_tribal_vpn_vpn_NativeTunnelBridge_nativeStartUdpGateway(
        JNIEnv *env, jobject /* this */,
        jint tun_fd, jstring udpgw_addr, jint udpgw_port) {

    const char *addr = env->GetStringUTFChars(udpgw_addr, nullptr);
    LOGE("nativeStartUdpGateway called (tun_fd=%d, udpgw=%s:%d) but "
         "badvpn-udpgw is not vendored yet — returning -1.",
         tun_fd, addr, udpgw_port);
    env->ReleaseStringUTFChars(udpgw_addr, addr);

    return -1;
}

JNIEXPORT void JNICALL
Java_com_tribal_vpn_vpn_NativeTunnelBridge_nativeStopUdpGateway(
        JNIEnv *env, jobject /* this */) {
    LOGI("nativeStopUdpGateway called (no-op)");
}

} // extern "C"
