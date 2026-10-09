// tunnel_bridge.cpp
//
// JNI bridge between Kotlin (NativeTunnelBridge.kt) and the native tunnel
// engines (hev-socks5-tunnel for Phase 4, badvpn-udpgw for Phase 5).
//
// Phase 4 is wired for real: hev_socks5_tunnel_main_from_str() runs the
// tun -> SOCKS5 relay on a worker thread (the upstream API blocks until
// quit()/error), mirroring the threading of upstream's own JNI binding
// (hev-jni.c). The YAML config is generated in-process using only fields
// verified against upstream src/hev-config.c. nativeStartTunnel() reports
// success only if the engine survives its synchronous init window; any
// startup failure is reported honestly - no fabricated state.
//
// Phase 5 (badvpn-udpgw) remains a stub: it logs a clear "not implemented"
// message and returns -1 rather than pretending to succeed - consistent
// with this project's no-fabricated-state rule (README_DATA_INTEGRITY.md).

#include <jni.h>
#include <android/log.h>

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "hev-main.h" // hev-socks5-tunnel public API (src/hev-main.h)

#define LOG_TAG "TribalTunnelBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The upstream API only returns from the blocking main call on quit() or
// error, so if the worker is still alive after this window the relay is
// running; synchronous failures (config parse, task-system/tun init) all
// return well inside it.
#define STARTUP_WAIT_SECONDS 1

namespace {

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t g_cond = PTHREAD_COND_INITIALIZER;
pthread_t g_thread;
int g_thread_joinable = 0; // worker exists and has not been joined yet
int g_running = 0;         // worker is inside the blocking main call
int g_thread_result = 0;   // return code of the finished worker
// g_thread_joinable / g_running / g_thread_result are only touched under
// g_lock (the tunnel worker is single-instance by design: only
// TribalVpnService start/stop touch this file).

struct ThreadData {
    char config[512];
    int tun_fd;
};

void *tunnel_thread(void *arg) {
    ThreadData *data = static_cast<ThreadData *>(arg);
    const unsigned int len = static_cast<unsigned int>(strlen(data->config));
    const int res = hev_socks5_tunnel_main_from_str(
            reinterpret_cast<const unsigned char *>(data->config), len,
            data->tun_fd);
    free(data);

    pthread_mutex_lock(&g_lock);
    g_running = 0;
    g_thread_result = res;
    pthread_cond_broadcast(&g_cond);
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

} // namespace

extern "C" {

/**
 * Phase 4: start the tun -> SOCKS5 relay.
 *
 * @param tun_fd      fd of the already-established VpnService tun interface
 * @param socks_addr  loopback address of the SshTunnelService SOCKS5 listener
 * @param socks_port  port of that listener
 * @return 0 if the relay is running, negative on failure. Never 0 when the
 *         engine failed during startup.
 */
JNIEXPORT jint JNICALL
Java_com_tribal_vpn_vpn_NativeTunnelBridge_nativeStartTunnel(
        JNIEnv *env, jobject /* this */,
        jint tun_fd, jstring socks_addr, jint socks_port) {

    const char *addr = env->GetStringUTFChars(socks_addr, nullptr);
    if (addr == nullptr) {
        LOGE("nativeStartTunnel: GetStringUTFChars failed");
        return -1;
    }

    // Config fields verified against upstream hev-config.c: socks5.port and
    // socks5.address are the only required keys; tunnel.* is inert when the
    // tun fd is handed in (upstream uses it only when it creates the device
    // itself), and these values mirror TribalVpnService's tun (10.0.0.2/24,
    // mtu 1400). udp: 'tcp' because SshTunnelService's SOCKS5 server has no
    // UDP ASSOCIATE support - UDP relaying is Phase 5's job (badvpn-udpgw).
    ThreadData *data = static_cast<ThreadData *>(malloc(sizeof(ThreadData)));
    if (data == nullptr) {
        env->ReleaseStringUTFChars(socks_addr, addr);
        LOGE("nativeStartTunnel: out of memory");
        return -1;
    }
    snprintf(data->config, sizeof(data->config),
             "tunnel:\n"
             "  name: tun0\n"
             "  mtu: 1400\n"
             "  ipv4: 10.0.0.2\n"
             "socks5:\n"
             "  port: %d\n"
             "  address: %s\n"
             "  udp: 'tcp'\n",
             static_cast<int>(socks_port), addr);
    data->tun_fd = tun_fd;

    LOGI("starting hev-socks5-tunnel relay (tun_fd=%d, socks=%s:%d)",
         static_cast<int>(tun_fd), addr, static_cast<int>(socks_port));
    env->ReleaseStringUTFChars(socks_addr, addr);

    pthread_mutex_lock(&g_lock);
    if (g_running) {
        pthread_mutex_unlock(&g_lock);
        free(data);
        LOGE("nativeStartTunnel: relay already running");
        return -1;
    }
    const pthread_t old_thread = g_thread;
    const int have_old = g_thread_joinable;
    pthread_mutex_unlock(&g_lock);

    if (have_old) {
        // The previous worker already finished (g_running == 0); reap it.
        pthread_join(old_thread, NULL);
    }

    pthread_mutex_lock(&g_lock);
    g_running = 1;
    g_thread_result = 0;
    pthread_mutex_unlock(&g_lock);

    const int rc = pthread_create(&g_thread, NULL, tunnel_thread, data);
    if (rc != 0) {
        pthread_mutex_lock(&g_lock);
        g_running = 0;
        pthread_mutex_unlock(&g_lock);
        free(data);
        LOGE("nativeStartTunnel: pthread_create failed (%d)", rc);
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    g_thread_joinable = 1;

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += STARTUP_WAIT_SECONDS;
    while (g_running) {
        if (pthread_cond_timedwait(&g_cond, &g_lock, &deadline) == ETIMEDOUT)
            break;
    }

    if (g_running) {
        // Alive past the init window: the relay is up. Upstream's own JNI
        // binding defines "running" the same way - thread alive inside the
        // blocking main call.
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    // The worker exited inside the window: a real, synchronous failure.
    g_thread_joinable = 0;
    const int result = g_thread_result;
    pthread_mutex_unlock(&g_lock);
    pthread_join(g_thread, NULL);

    LOGE("nativeStartTunnel: hev-socks5-tunnel failed to start (result=%d)",
         result);
    return (result < 0) ? result : -1;
}

/**
 * Phase 4: stop the tun -> SOCKS5 relay started above.
 */
JNIEXPORT void JNICALL
Java_com_tribal_vpn_vpn_NativeTunnelBridge_nativeStopTunnel(
        JNIEnv * /*env*/, jobject /* this */) {

    pthread_mutex_lock(&g_lock);
    const int joinable = g_thread_joinable;
    const int running = g_running;
    pthread_mutex_unlock(&g_lock);

    if (!joinable) {
        LOGI("nativeStopTunnel: no relay to stop");
        return;
    }

    if (running) {
        // Unblocks hev_socks5_tunnel_main_from_str() inside the worker.
        hev_socks5_tunnel_quit();
    }
    // Joined outside g_lock: the worker's exit path takes the lock, so
    // joining while holding it would deadlock.
    pthread_join(g_thread, NULL);

    pthread_mutex_lock(&g_lock);
    g_thread_joinable = 0;
    g_running = 0;
    pthread_mutex_unlock(&g_lock);
    LOGI("nativeStopTunnel: relay stopped");
}

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
