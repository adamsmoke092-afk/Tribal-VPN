# Tribal-VPN native build (ndk-build), wired into app/build.gradle.kts via
# externalNativeBuild { ndkBuild { path = ... } }.
#
# hev-socks5-tunnel is pinned as a gitlink-only submodule at
# hev-socks5-tunnel/ (see .gitmodules at the repo root - the source is
# cloned by CI, never on the dev phone). Its own Android.mk builds
# libhev-socks5-tunnel.so plus the yaml/lwip/hev-task-system static deps
# from its nested submodules; the bridge variants below link against it.
#
# DIAGNOSTIC APK for the Android 8.1 "dlopen failed: can't enable GNU RELRO
# protection" failure: three bridge variants are built from the same hev
# build, and NativeTunnelBridge tries them a -> b -> c at startup, logging
# which loads:
#   tribal_bridge_a - current bridge (tunnel_bridge.cpp), default flags
#   tribal_bridge_b - same source, RELRO disabled (-Wl,-z,norelro)
#   tribal_bridge_c - the full pre-81fdf0b bridge (tunnel_bridge_old.cpp)
#
# Phase 5 (badvpn-udpgw): see the nativeStartUdpGateway stubs in the
# bridge sources.

CPP_PATH := $(call my-dir)

include $(CPP_PATH)/hev-socks5-tunnel/Android.mk

BRIDGE_INCLUDES := $(CPP_PATH)/hev-socks5-tunnel/src
BRIDGE_LDLIBS := -llog
BRIDGE_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

# --- Variant A: current bridge, default link flags -----------------------
include $(CLEAR_VARS)
LOCAL_PATH := $(CPP_PATH)
LOCAL_MODULE := tribal_bridge_a
LOCAL_SRC_FILES := tunnel_bridge.cpp
LOCAL_C_INCLUDES := $(BRIDGE_INCLUDES)
LOCAL_LDLIBS := $(BRIDGE_LDLIBS)
LOCAL_LDFLAGS := $(BRIDGE_LDFLAGS)
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
include $(BUILD_SHARED_LIBRARY)

# --- Diagnostic variants: DEBUG BUILDS ONLY -------------------------------
# TRIBAL_DIAG_VARIANTS=1 is passed only for the debug buildType in
# app/build.gradle.kts. A release build (or a bare `ndk-build` invocation)
# never defines it, so variants b/c do not exist there at all and
# -Wl,-z,norelro is structurally unable to reach a release build. The CI
# dry-run gate greps the release-parity parse for "norelro" and fails if
# it ever shows up. Variant A alone carries the relay in release builds.
ifeq ($(TRIBAL_DIAG_VARIANTS),1)

# --- Variant B: same source, RELRO disabled (diagnostic) ------------------
include $(CLEAR_VARS)
LOCAL_PATH := $(CPP_PATH)
LOCAL_MODULE := tribal_bridge_b
LOCAL_SRC_FILES := tunnel_bridge.cpp
LOCAL_C_INCLUDES := $(BRIDGE_INCLUDES)
LOCAL_LDLIBS := $(BRIDGE_LDLIBS)
LOCAL_LDFLAGS := $(BRIDGE_LDFLAGS) -Wl,-z,norelro
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
include $(BUILD_SHARED_LIBRARY)

# --- Variant C: the archived pre-81fdf0b full bridge ----------------------
include $(CLEAR_VARS)
LOCAL_PATH := $(CPP_PATH)
LOCAL_MODULE := tribal_bridge_c
LOCAL_SRC_FILES := tunnel_bridge_old.cpp
LOCAL_C_INCLUDES := $(BRIDGE_INCLUDES)
LOCAL_LDLIBS := $(BRIDGE_LDLIBS)
LOCAL_LDFLAGS := $(BRIDGE_LDFLAGS)
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
include $(BUILD_SHARED_LIBRARY)

endif
