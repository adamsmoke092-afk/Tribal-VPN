# Xray Engine Integration

Status: **implemented 2026-10-25, self-reviewed, awaiting CI + on-device
verification.** Nothing in this document claims a device-verified connection;
the "Verification" section states exactly what has and has not been run.

## What this adds

Tribal VPN now has **two connection engines**. A profile picks one, and exactly
one runs per connection:

```
Android VpnService (tun0)
        |
        v
hev-socks5-tunnel            <- unchanged, owns tun0, unchanged relay config shape
        |
        v
local SOCKS5 endpoint        <- THE engine seam
        |
        +--> SSH engine   : the existing hand-rolled SOCKS5 server -> sshj -> VPS
        |
        +--> Xray engine  : the Xray core's app-managed inbound -> outbounds -> VPS
                           (in-process, libv2ray.aar / Xray-core)
```

tun0, MTU/DNS/MapDNS handling, traffic stats, the bypass list and
auto-reconnect are shared and untouched. The engine only decides **which local
SOCKS5 endpoint the relay forwards into**.

The SSH engine is byte-for-byte the same code path as before (`protect()` per
socket, `udp: 'tcp'` in the hev config, udpgw for UDP).

## The integration surface (all verified from source, nothing guessed)

| Fact | Value | Source |
|---|---|---|
| Artifact | `libv2ray.aar`, AndroidLibXrayLite **v26.10.21** | release assets; the same file v2rayNG's own CI downloads |
| Pinned core | Xray-core `v1.260327.1-0.20261021115930-1b1647cd9ddf` (that release's `go.mod`) | AndroidLibXrayLite `go.mod` |
| sha256 | `e56ec3b06485fe7b37af26948673951edf719c50d6bbc618b8539045952adde4` | GitHub release API |
| ABIs | the AAR carries gomobile's default set (arm64-v8a, armeabi-v7a, x86, x86_64; `libgojni.so` per ABI, `-androidapi 24`) — the app **packages only arm64-v8a + armeabi-v7a** | AndroidLibXrayLite `main.yml`; gomobile `cmd/gomobile/bind_androidapp.go` |
| Java package | `libv2ray` + gomobile's `go.Seq` | `-javapkg` absent; v2rayNG's imports |
| Start | `Libv2ray.newCoreController(handler)` then `controller.startLoop(json, tunFd)` | binding Go source |
| Stop / state | `controller.stopLoop()`, `controller.isRunning` | binding Go source |
| Errors | `startLoop` throws synchronously (`config error` / `core init failed` / `startup failed`) and self-cleans | binding Go source |
| tunFd | **0** — "do not use TUN"; hev owns tun0 (v2rayNG does the same in hev mode) | binding + v2rayNG `CoreServiceManager` |
| Socket protection | no per-fd hook exists, so the app's **own uid** is excluded from the VPN via `Builder.addDisallowedApplication(packageName)` | v2rayNG `CoreVpnService` |

Because the app's uid is excluded, the core's outbound sockets and its own DNS
resolution of the server address use the underlying network directly and can
never be routed back into tun0 (no loop). If that exclusion fails, the
connection is aborted - never reported as Connected.

Licenses: **libv2ray = LGPL-3.0**, **Xray-core = MPL-2.0**. The AAR is consumed
unmodified from its official release; it is never committed to this repo
(`.gitignore`) and CI downloads + hash-verifies it on every build.

## Supported configuration format

**A full Xray JSON config whose `outbounds` describe the proxy.** That is the
only format claimed. Share links (`vless://`, `vmess://`, `trojan://`, `ss://`)
are recognised and **rejected with a clear message** - not half-parsed.

The app then, at connect time:

1. **Replaces `inbounds`** with its own loopback SOCKS5 inbound
   (`127.0.0.1`, kernel-allocated port, `auth: noauth`, `udp: true`,
   `ip: 127.0.0.1`). The tag of the user's first inbound is preserved when
   present, so routing rules matching on `inboundTag` keep meaning the same
   thing.
2. **Normalises `log`** to `{"loglevel":"warning","access":"none"}`. This is a
   secrecy control: a user-supplied access-log path or `debug` level would let
   core diagnostics write data the app promised not to log.
3. Leaves **everything else verbatim**: outbounds, routing, dns,
   streamSettings.

Validation happens twice - in the editor, and again in `connect()` through
`VpnConfig.isValid()` - using the same validator, so the UI can never save
something the connection path would refuse. Rejected with actionable reasons:
empty config, unparseable JSON, non-object root, missing/empty/non-array
`outbounds`, an outbound without a `protocol`, and a **direct-only**
(`freedom`-only) config - which would connect "successfully" while tunnelling
nothing, exactly the fake success this project forbids.

**Removed-field validation (2026-10-26):** the pinned core (v26.10.21)
hard-errors at config-build time while `tlsSettings.allowInsecure` is `true`
("The feature \"allowInsecure\" has been removed and migrated to
\"pinnedPeerCert(Sha256...)\" and \"verifyPeerCertByName(vcn)\"." - verified in
`infra/conf/transport_security.go` at the pinned commit: the field survives as
a plain `bool` and the guard is `if c.AllowInsecure`). The validator therefore
rejects any boolean-true `allowInsecure` ANYWHERE in the config before the core
runs, naming the exact JSON path (e.g.
`outbounds[0].streamSettings.tlsSettings.allowInsecure`) and no config values.
The app does **not** rewrite or auto-migrate security fields - the user removes
the field from their own JSON. Two facts worth knowing:
- `allowInsecure: false` is tolerated and preserved verbatim (the core
  declares the field as a plain bool and only errors when it is true).
- The CURRENT replacement fields are `tlsSettings.pinnedPeerCertSha256`
  (comma-separated hex SHA-256 pins) and `tlsSettings.verifyPeerCertByName`
  (comma-separated names). The older `pinnedPeerCertificateChainSha256` no
  longer exists in the schema and is never recommended by the app.

**Transport security is expressed through the pasted JSON - there is no UI
selector.** The profile editor offers no TLS/no-TLS choice, so nothing can
switch modes behind the user's back: a config with
`streamSettings.security: "none"` (or no `tlsSettings` at all) passes
validation untouched, and a TLS error never downgrades a profile to no-TLS.
If a no-TLS UI choice is ever added, it must be an explicit user selection
(defaulting to TLS) with the visible warning
"Unencrypted — traffic is not protected by TLS and may be visible to your
network." directly next to the choice - not buried in Advanced.

## Diagnostics and secrecy

- The durable log records the engine, the loopback inbound port, and a
  **structure-only** config summary (`outbounds=2 [vless,freedom],
  routingRules=2`). It never records config text, so UUIDs, passwords, server
  addresses and paths cannot leak into logs or screenshots.
- Parser exception messages are **not** forwarded (they can quote input);
  the UI shows a fixed explanation instead.
- Third-party (core) error strings are capped to their first line before being
  logged.

## Lifecycle behaviour

| Situation | Behaviour |
|---|---|
| Connect | validate -> allocate loopback port -> `init` env -> `startLoop` -> verify `isRunning` -> tun0 -> relay -> **then** Connected |
| Core start failure | throws; tun0 was not yet opened; ERROR state with the core's own reason |
| Relay start failure | tun0 closed, core stopped, ERROR (`startTunnel()` false) |
| Disconnect | relay stopped -> engine stopped -> tun0 closed -> DISCONNECTED |
| Repeated connect | guarded (CONNECTING/CONNECTED no-op); a restart always stops the previous core first (`startLoop` is otherwise a no-op when already running - a real trap) |
| Network change | existing `NetworkChangeMonitor` reconnect re-runs `connect()`, which restarts the engine cleanly |
| Service destroyed | core stopped (`onDestroy`), tun closed |
| SSH profile | unchanged path, unchanged behaviour |

## Known limitations (honest list)

- **No geo assets.** `geosite.dat`/`geoip.dat` are not shipped, so a config
  whose routing rules reference `geoip:`/`geosite:` fails at core start with
  the core's own error. Fix: place the `.dat` files in the app's files dir
  (the `xray.location.asset` path).
- **Share links unsupported** (see above).
- **No sniffing** is added to the injected inbound, so routing rules that match
  on sniffed domains only see whatever the relay already provides (MapDNS
  reverse mapping gives domain targets for DNS-resolved connections).
- **Port race (documented, honest):** the loopback port is probed by binding
  and releasing; a rare collision surfaces as a core start failure, never as a
  fake Connected.
- **APK size / ABIs:** the AAR carries Xray for four ABIs (~57 MB unpacked);
  the app packages only **arm64-v8a and armeabi-v7a** (`abiFilters`, changed
  2026-10-26), so exactly one Xray `libgojni.so` ships per retained ABI.
  Measured before/after sizes are recorded in README_DATA_INTEGRITY.md.
- **UDP path** is standard SOCKS5 UDP ASSOCIATE (`socks5.udp: 'udp'`), which
  the Xray socks inbound supports. Whether app UDP actually flows end-to-end
  through a given outbound is a property of that outbound, untested here.

## Verification

- **Compile + JVM unit tests:** `.github/workflows/build.yml`
  (`gradle :app:assembleDebug :app:testDebugUnitTest`). This device cannot run
  Gradle (low-RAM phone, no Android SDK), so CI is the only build path - the
  same rule the project already follows. Unit tests added:
  `XrayConfigManagerTest` (validation, rejection, inbound injection, outbound
  preservation, log normalisation, redaction, port allocation) and new
  engine-compatibility tests in `VpnConfigTest`.
- **Packaged ABIs:** the CI step `Verify APK ABI contents (gate: arm64-v8a +
  armeabi-v7a only)` inspects the built APK and fails the build unless both
  retained ABIs contain `libhev-socks5-tunnel.so`, `libtribal_bridge_a/b.so`
  and the Xray `libgojni.so`, and unless zero `lib/x86*/` entries are packaged.
- **On-device networking: NOT DONE YET.** Requires an APK install and a real
  Xray profile. On-device evidence to look for in Logs: `Xray core running
  (startLoop returned, isRunning=true)`, `app uid excluded from the VPN`,
  `TProxyStartService returned true`, then TCP traffic.

## Local build note

The AAR is not committed. Before a local Gradle build:

```
curl -L -o app/libs/libv2ray.aar \
  https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.21/libv2ray.aar
```

A missing AAR is a deliberate hard compile error.
