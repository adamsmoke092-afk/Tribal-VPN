# UDP Custom protocol analysis — evidence record

Reproducible static investigation of the official HTTP Custom "UDP Custom"
server, toward a compatible Tribal-VPN client. **No wire protocol has been
established yet, and no client is claimed working.** Strings and recovered
symbols are leads, not proof.

## Method and guarantees

- Static analysis only. The downloaded server binary is **never executed**.
- The only program that runs is the analyzer (GoReSym), and it only reads
  the target file.
- Every upstream artifact is pinned to an immutable commit SHA; the target
  binary's SHA-256 is verified against the recorded pin on every run.
- Proprietary binaries are hashed, sampled, then **deleted** — they are
  never uploaded as artifacts. Artifacts contain derived text only.
- No user credentials appear in this analysis, its artifacts, or its logs.

## Pins (verified 2026-10-22)

| Artifact | Pin |
|---|---|
| Target repo | `http-custom/udp-custom` @ `fcf839259e623af001cfa85fe1e1e343c58df264` (2023-09-29) |
| Target binary | `bin/udp-custom-linux-amd64`, sha256 `2a1b5584c7947feb5a02e847e09795751024f63ce7137a353c2b9c2a4282d636` |
| Analyzer | Mandiant GoReSym `v3.4.1`, `GoReSym-linux.zip` sha256 `a557124857f95a589f8ce3525119c2d18c7c7fc7c4225c92ff94e3effb38748d` |
| Sibling repo | `http-custom/udp` @ `ccd5ffec4484b191478cf094b128a3268ff3beda` (2024-03-14) |
| Sibling repo | `http-custom/udphc` @ `9e5333fd6b2b2390c6396a90ab487d3ce5e29b65` (2024-02-26) |
| Sibling repo | `http-custom/udpcustom` @ `5f0443c2f9374fcc5350d6e495ecd27074c527e3` (2024-02-24) |
| Sibling repo | `http-custom/udp_custom` @ `a9b0a06269618a9183dcf9ea367164755b0fe2c2` (2024-01-20) |

## Verified facts

**From the official installer scripts (all files read 2026-10-21):**
- The server stack runs three services: `udp-custom` (proprietary binary,
  client-facing, config `listen :36712`, `auth mode "passwords"`, 32 MB
  stream / 80 MB receive buffers), `udpgw` = badvpn-udpgw bound to
  **loopback 127.0.0.1:7800** (`--max-clients 1000
  --max-connections-for-client 100`), and an optional `udp-request`
  full-port-range catcher (`-ip -net -exclude -mode=system`; disabled in
  the Lite installer).
- Account creation prints "Port Range: 1-65535" as a fixed server-wide
  convention; the range's only scripted interaction with ports is
  `udp-request`'s exclusion list (e.g. dnstt 53/5300, WireGuard 51820,
  OpenVPN 1194). There are **zero iptables rules in any repo script** —
  range routing lives inside the binaries.
- The account format `server:1-65535@user:pass` carries SSH credentials
  (accounts are created with `useradd`); the udpgw protocol itself has no
  authentication.

**From the Phase 1 analysis run (target binary, static):**
- ELF x86-64, statically linked, **no section header table** (consistent
  with deliberate stripping — this is why `nm`/`go tool nm` recovered
  nothing and `go version -m` failed).
- Strings include `ro/udp-custom/app/cmd/serv` — a Go-style source path
  fragment (likely `.../udp-custom/app/cmd/server`). Go is therefore the
  most probable implementation language, but this is a **deduction, not a
  verified fact** until GoReSym recovers pclntab metadata.

## Unresolved protocol questions

1. Transport of the client-facing listener on :36712 (TCP or UDP).
2. Connection sequence and session establishment from the client.
3. Authentication handshake: message format, credential encoding, and how
   the server provisions passwords (`"mode": "passwords"`).
4. Message framing and packet encoding of the client protocol.
5. Failure behaviour: refused credentials, expired accounts, idle
   disconnects, keepalives.
6. How the 1-65535 range routes traffic to the core, per stack generation.
7. Which stack generation the user's provider runs (2023 `udp-custom` vs
   the 2024 sibling repos — not assumed identical).

## Reproduction

GitHub → Actions → **"UDP Custom protocol analysis"** → Run workflow.
Defaults analyze the pinned 2023 target and inventory the four pinned
sibling repos. Inputs allow retargeting (`repo`, `ref`, `binary_path`,
`expected_sha256` — clear it when analyzing a different binary;
`disassemble` takes Go symbol regexes for targeted `go tool objdump`
follow-ups once the symbol inventory names candidates).

Artifacts: `udp-custom-analysis` (target evidence) and
`udp-custom-siblings` (sibling inventory) — text only.

## Status

- Static-analysis findings: Phase 1 done (identity + strings); Phase 1B
  (GoReSym metadata recovery + sibling inventory) pending first run.
- Client implementation: **not started** — blocked on the unresolved
  questions above. No guessed protocol will be written.
- End-to-end compatibility: **not claimed**.
