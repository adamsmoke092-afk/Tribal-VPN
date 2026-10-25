# Data Integrity Notes

This scaffold was built with one hard rule: **no fabricated connection stats,
and no UI state that claims a permission or capability the OS hasn't actually
confirmed.** Every number or toggle the UI can show is either real right now,
or explicitly absent (null/zero/disabled) until the corresponding phase is
implemented.

## What's real today

| Value | Source | File |
|---|---|---|
| Connection state (Disconnected/Connecting/Connected/Error) | `TribalVpnService` state machine, driven by actual `VpnService.Builder.establish()` success/failure | `vpn/TribalVpnService.kt` |
| Uptime | `System.currentTimeMillis()` timestamp captured the instant the tun interface establishes; formatted live in the UI | `vpn/TrafficMonitor.kt`, `ui/VpnViewModel.kt` |
| Bytes downloaded/uploaded | `android.net.TrafficStats.getUidRxBytes()/getUidTxBytes()`, scoped to this app's UID, baselined at connect time | `vpn/TrafficMonitor.kt` |
| Speed (Mbps) | Delta of TrafficStats byte counts between two 1-second samples, converted to Mbps | `vpn/TrafficMonitor.kt` |
| Saved profiles | `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore) | `data/ConfigRepository.kt` |
| Logs | Appended only from actual service lifecycle events (connecting, SSH authenticated, tunnel state, error, disconnected) | `vpn/TribalVpnService.kt`, `ssh/SshTunnelService.kt` |
| SSH session + SOCKS5 proxy | Real `sshj` session with a hand-rolled RFC 1928 SOCKS5 server bridging into sshj `direct-tcpip` channels; `protect()` is called on the raw socket before connecting | `ssh/SshTunnelService.kt`, `ssh/Socks5Handshake.kt` |
| Xray engine (second connection engine) | In-process Xray-core via the pinned `libv2ray.aar` (v26.10.21 + sha256). The app manages the loopback SOCKS5 inbound, validates the user's Xray JSON config, excludes its own uid from the VPN (this binding exposes no per-fd protect hook), and reports Connected only after `startLoop` + `isRunning` + the native relay all succeeded | `xray/XrayEngine.kt`, `xray/XrayConfigManager.kt`, `vpn/TribalVpnService.kt` |
| Network change detection / reconnect | `ConnectivityManager.NetworkCallback` (real OS callbacks), exponential backoff 1s→60s | `vpn/NetworkChangeMonitor.kt`, wired into `TribalVpnService` |
| "Ignore battery optimization" toggle | `PowerManager.isIgnoringBatteryOptimizations()`, queried live in `onCreate`/`onResume` — **not** a stored preference, since this permission can't be self-granted | `MainActivity.kt` |

## What's intentionally NOT fully implemented yet (by roadmap phase)

These are wired up to the point of being called, with a stub or honest
failure behind them — not faked:

- **Phase 4 (hev-socks5-tunnel via NDK)**: wired for real as of 2026-10-09 —
  `hev-socks5-tunnel` is pinned as a gitlink-only submodule at
  `tribal-vpn/app/src/main/cpp/hev-socks5-tunnel` (`.gitmodules` at the repo
  root; the source is cloned by CI, never on the dev phone), built by
  ndk-build from `app/src/main/cpp/Android.mk` (upstream has no CMake build),
  and `tunnel_bridge.cpp` runs the blocking `hev_socks5_tunnel_main_from_str()`
  API on a worker thread with a synchronous-init failure window so start
  failures are reported honestly. **VERIFIED 2026-10-17**: CI run 16 built the
  relay from the pinned submodule (re-pinned to `873541b8…` after the old pin
  vanished upstream), and the on-device Connect showed `TProxyStartService`
  TRUE with real traffic (Telegram DC + carrier proxy IPs) through
  tun0 → hev → SOCKS5 → sshj → internet. If `startTunnel()` does fail, the
  service logs it (isError) and does not claim packet relay is active.
- **Phase 5 (UDP)**: **shipped 2026-10-20 as a Kotlin badvpn-udpgw client** —
  hev's UDP flows (SOCKS5 FWD_UDP) are bridged to the VPS's udpgw over the
  SSH session (encrypted, preferred) or a direct protected socket into the
  provider's redirect range (HTTP Custom style, unencrypted, logged loudly).
  `NativeTunnelBridge.startUdpGateway()` was a dead stub that pretended to be
  a feature - it and its JNI externs have been removed; the Kotlin bridge in
  `ssh/UdpGwClient.kt` + `ssh/SshTunnelService.kt` is the real UDP path, and
  activation logs verified availability (transport opened, or an honest
  "UDP will not flow, TCP unaffected").
- **SSH key auth**: `SshTunnelService.authenticate()` throws
  `UnsupportedOperationException` for `AuthMethod.KEY` rather than silently
  falling back to password or pretending to succeed. Needs an Android
  Keystore → sshj `KeyProvider` adapter.
- **DNS leak verification**: DNS servers are set on `VpnService.Builder` for
  real, but there's no automated leak test — verify manually per roadmap
  Phase 6 once Phase 4 is live (no point leak-testing a tunnel that isn't
  relaying packets yet).

## What changed in this pass

- Wired `NativeTunnelBridge` (new) into `TribalVpnService.connect()`/`disconnect()`
  for real, plus its JNI counterpart in `app/src/main/cpp/`.
- Added the missing `externalNativeBuild { cmake { ... } }` block to
  `app/build.gradle.kts` so `CMakeLists.txt` actually gets picked up by Gradle.
- Wired `NetworkChangeMonitor` (previously a standalone, unused class) into
  `TribalVpnService` — auto-reconnect is now live, gated on the real
  `AppSettingsRepository.autoReconnect` flag, and respects a user-initiated
  disconnect (won't fight you by reconnecting after you tap Disconnect).
- Fixed a real honesty gap: "Ignore battery optimization" was a plain stored
  boolean the UI could flip to `true` without Android ever granting anything.
  It's now backed by a live `PowerManager` query in `MainActivity`, and the
  toggle launches the actual system exemption dialog instead.
- Added the missing `junit` test dependency (the existing `VpnConfigTest`
  couldn't have compiled without it) and a new `Socks5HandshakeTest` that
  exercises the real RFC 1928 byte framing over loopback sockets.

## What changed in this pass — 2026-10-09 (Phase 4 wiring)

- Registered `hev-socks5-tunnel` (pinned at upstream commit
  `07bea57d20f71c9804055c2e2a0787d65a7277cb`) as a **gitlink-only submodule**
  at `tribal-vpn/app/src/main/cpp/hev-socks5-tunnel` — `.gitmodules` lives at
  the repo root and the source is intentionally absent locally (the dev phone
  has no storage for a clone); CI checks it out with `submodules: recursive`.
- Switched the app module from CMake to ndk-build (AGP allows only one native
  build system per module, and upstream hev-socks5-tunnel has no CMake build):
  `app/src/main/cpp/Android.mk` includes hev's own Android.mk (which also
  builds its yaml/lwip/hev-task-system static deps from its nested submodules)
  and links `tribal_tunnel_bridge` against `libhev-socks5-tunnel.so`.
  `CMakeLists.txt` was removed.
- `tunnel_bridge.cpp`: `nativeStartTunnel` now generates the hev config
  in-process — only fields verified against upstream `src/hev-config.c`, with
  `socks5.udp: 'tcp'` because the hand-rolled SOCKS5 server has no UDP
  ASSOCIATE — and runs the blocking `hev_socks5_tunnel_main_from_str()` on a
  pthread (mirroring upstream's own JNI binding in `hev-jni.c`). A 1s
  synchronous-init window means config/setup failures return failure
  honestly instead of pretending the relay started.
- CI at `.github/workflows/build.yml` (repo root, `working-directory:
  tribal-vpn`) builds the debug APK on every push.
- Updated the stale "not vendored yet" comments/logs in
  `NativeTunnelBridge.kt` / `TribalVpnService.kt` to describe the real failure
  mode (engine failed to start, logged as an error) instead of the old stub
  state.

## Why this matters

The original React prototype used a `setTimeout`-based fake connect sequence and a
hardcoded `"6.2 Mbps"` string for demo purposes. That's fine for a UI mockup, but
would be actively misleading in the real app — a friend could believe they're
protected/tunneled when they're not, or that background battery exemption is
active when the OS never granted it. This scaffold replaces every one of those
values with either a real OS-level reading, a real (if currently failing)
call into the native/SSH layer, or an honest "not there yet" state.

## Next wiring steps

1. ~~Vendor `hev-socks5-tunnel` as a submodule and uncomment the CMake
   lines~~ **Done 2026-10-09** — via ndk-build instead of CMake (upstream has
   no CMake build; see `app/src/main/cpp/Android.mk`). Phase 4 verification:
   **DONE 2026-10-17** (on-device traffic observed; see the Phase 4 bullet).
2. ~~Same for `badvpn-udpgw` (Phase 5)~~ **Shipped 2026-10-20** — as a Kotlin
   badvpn-udpgw client (`ssh/UdpGwClient.kt`) plus the FWD_UDP bridge in
   `ssh/SshTunnelService.kt`; no native vendoring needed and the dead native
   stub was removed. Requires a udpgw server on the VPS (port 7300 default).
3. Build the Android Keystore → sshj `KeyProvider` adapter so SSH key auth
   stops throwing `UnsupportedOperationException`.
4. Replace the static `pendingConfigProvider` / companion-object StateFlow
   pattern with a proper `bindService()` connection once the app needs more
   than one Activity talking to the service — flagged as scaffolding debt,
   not a correctness bug.
5. Manual DNS leak test once #1 is done (Phase 6 Definition of Done).

## What changed in this pass — 2026-10-25 (Xray engine = second connection engine)

- **New: Xray-core as a second engine** (`xray/XrayEngine.kt`,
  `xray/XrayConfigManager.kt`). A profile now carries an `engine` field
  (`VpnEngine.SSH` default / `VpnEngine.XRAY`). Profiles saved before the field
  existed decode as SSH and behave exactly as before — pinned by tests in
  `VpnConfigTest`.
- **Engine seam:** the engine only decides which local SOCKS5 endpoint
  `hev-socks5-tunnel` forwards into — the existing sshj SOCKS5 listener, or the
  Xray core's app-managed loopback inbound (kernel-allocated port). tun0,
  MapDNS, MTU, the bypass list, traffic stats and auto-reconnect are shared and
  unchanged.
- **Pinned artifact:** `libv2ray.aar` v26.10.21 (AndroidLibXrayLite, whose
  `go.mod` pins Xray-core `v1.260327.1-0.20261021115930-1b1647cd9ddf`),
  downloaded and sha256-verified by CI, never committed (~57 MB; the dev phone
  has no storage). Licenses: libXray LGPL-3.0, Xray-core MPL-2.0. Details and
  the supported config format: `XRAY_INTEGRATION.md`.
- **Socket protection:** that binding exposes no per-fd protect callback, so for
  Xray profiles the app excludes its **own uid** from the VPN
  (`Builder.addDisallowedApplication(packageName)` — the mechanism v2rayNG's
  `CoreVpnService` uses). Core outbound traffic therefore cannot loop back into
  tun0, and a failed exclusion aborts the connection instead of pretending. The
  SSH engine keeps its per-socket `protect()` path untouched.
- **Honesty rules carried over:** the runtime config is validated twice (editor
  + `connect()`), a direct-only (`freedom`-only) config is rejected, `startLoop`'s
  synchronous errors **and** a post-start `isRunning` check both gate Connected,
  and a failed core or relay tears down tun0 rather than reporting success. Logs
  carry a structure-only config summary — never config text (UUIDs, passwords,
  addresses).
- **APK size / ABIs (2026-10-26):** the app now ships **arm64-v8a + armeabi-v7a
  only** — x86 and x86_64 were dropped, which removes a full Xray-core
  `libgojni.so` for every dropped ABI. Both retained ABIs carry the hev relay
  (`libhev-socks5-tunnel.so`), the bridge variants and the Xray core; the CI
  gate "Verify APK ABI contents" fails the build if any other ABI ships or one
  of those libraries goes missing.
- **Not yet verified on-device.** CI compiles the app and runs the JVM tests
  (`XrayConfigManagerTest` + the engine-compatibility tests in `VpnConfigTest`);
  no Xray connection has been made on a phone yet. `XRAY_INTEGRATION.md` lists
  the exact log lines to look for when testing.
