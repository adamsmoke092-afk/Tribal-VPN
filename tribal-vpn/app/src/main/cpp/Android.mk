# Tribal-VPN native build (ndk-build), wired into app/build.gradle.kts via
# externalNativeBuild { ndkBuild { path = ... } }.
#
# hev-socks5-tunnel is pinned as a gitlink-only submodule at
# hev-socks5-tunnel/ (see .gitmodules at the repo root - the source is
# cloned by CI, never on the dev phone). Its own Android.mk builds
# libhev-socks5-tunnel.so plus the yaml/lwip/hev-task-system static deps
# from its nested submodules; the bridge variants below link against it.
#
# Bridge layout (RELRO policy, verified on the Android 8.1 target
# 2026-10-17): the old linker fails to mmap GNU_RELRO for some ELF layouts
# ("dlopen failed: can't enable GNU RELRO protection ... Out of memory"),
# so the app ships a cascade, not a single library:
#   tribal_bridge_a - current bridge (tunnel_bridge.cpp), RELRO enabled.
#   tribal_bridge_b - same source, RELRO disabled (-Wl,-z,norelro). Ships
#     in EVERY build: NativeTunnelBridge tries a first and falls back to
#     b when a fails to load, logging the fallback visibly. The CI gate
#     asserts every norelro line in the release-parity parse belongs to
#     libtribal_bridge_b.so (a provably keeps RELRO).
#   tribal_bridge_c - the archived pre-81fdf0b full bridge, diagnostic
#     only: DEBUG BUILDS (TRIBAL_DIAG_VARIANTS=1 from the debug buildType).
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

# --- Variant B: RELRO-disabled fallback (ships in ALL builds) -------------
# NativeTunnelBridge tries variant a first and falls back to b when the
# device's linker rejects a's RELRO layout (old Android 8.x linkers).
include $(CLEAR_VARS)
LOCAL_PATH := $(CPP_PATH)
LOCAL_MODULE := tribal_bridge_b
LOCAL_SRC_FILES := tunnel_bridge.cpp
LOCAL_C_INCLUDES := $(BRIDGE_INCLUDES)
LOCAL_LDLIBS := $(BRIDGE_LDLIBS)
LOCAL_LDFLAGS := $(BRIDGE_LDFLAGS) -Wl,-z,norelro
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
include $(BUILD_SHARED_LIBRARY)

# --- Variant C: diagnostic archive, DEBUG BUILDS ONLY ---------------------
# TRIBAL_DIAG_VARIANTS=1 is passed only for the debug buildType in
# app/build.gradle.kts. A release build (or a bare `ndk-build` invocation)
# never defines it, so variant c never exists in a release build.
ifeq ($(TRIBAL_DIAG_VARIANTS),1)

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
