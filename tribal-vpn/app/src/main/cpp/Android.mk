# Tribal-VPN native build (ndk-build), wired into app/build.gradle.kts via
# externalNativeBuild { ndkBuild { path = ... } }.
#
# Phase 4: hev-socks5-tunnel is pinned as a gitlink-only submodule at
# hev-socks5-tunnel/ (see .gitmodules at the repo root - the source is
# cloned by CI, never on the dev phone). Its own Android.mk builds
# libhev-socks5-tunnel.so plus the yaml/lwip/hev-task-system static deps
# from its nested submodules; our bridge links against it and calls the
# public API from src/hev-main.h.
#
# Phase 5 (badvpn-udpgw): vendor the same way once implemented - see the
# nativeStartUdpGateway stub in tunnel_bridge.cpp.

CPP_PATH := $(call my-dir)

include $(CPP_PATH)/hev-socks5-tunnel/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH := $(CPP_PATH)
LOCAL_MODULE := tribal_tunnel_bridge
LOCAL_SRC_FILES := tunnel_bridge.cpp
LOCAL_C_INCLUDES := $(CPP_PATH)/hev-socks5-tunnel/src
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
include $(BUILD_SHARED_LIBRARY)
