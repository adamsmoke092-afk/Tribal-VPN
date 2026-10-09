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
  failures are reported honestly. **Still pending: green CI build + on-device
  verification — until a build is installed and traffic is observed flowing,
  treat the relay as wired-but-unverified.** If `startTunnel()` does fail, the
  service logs it (isError) and does not claim packet relay is active.
- **Phase 5 (UDP/badvpn-udpgw)**: same pattern — `NativeTunnelBridge.startUdpGateway()`
  is called when `enableUdp` is on, but only after Phase 4's relay is confirmed
  live, and it also returns failure honestly until `badvpn-udpgw` is vendored.
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
   no CMake build; see `app/src/main/cpp/Android.mk`). Remaining Phase 4 work:
   green CI build, then on-device traffic verification.
2. Same for `badvpn-udpgw` (Phase 5), gated behind Phase 4 being live.
3. Build the Android Keystore → sshj `KeyProvider` adapter so SSH key auth
   stops throwing `UnsupportedOperationException`.
4. Replace the static `pendingConfigProvider` / companion-object StateFlow
   pattern with a proper `bindService()` connection once the app needs more
   than one Activity talking to the service — flagged as scaffolding debt,
   not a correctness bug.
5. Manual DNS leak test once #1 is done (Phase 6 Definition of Done).
