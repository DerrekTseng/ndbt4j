# Optimization Backlog

Status of performance work on bt4j: what is already done, and every remaining candidate worth considering,
ranked by expected value. Effort/Risk: S(mall) / M(edium) / L(arge).

## Already done

| Optimization | Commit theme |
|---|---|
| Proper choke algorithm (tit-for-tat, optimistic unchoke, hysteresis) | choke |
| Anti-snubbing (abandon a peer silent for 60s, redistribute its blocks) | scheduling |
| Adaptive pipeline depth via bandwidth-delay product, RTT-tuned per peer | scheduling |
| Per-request timeout (re-queue one stuck block instead of killing the connection) | scheduling |
| Endgame mode + endgame Cancel (stop duplicate transfers once a block arrives) | scheduling |
| SHA-1 piece verification outside the storage lock (concurrent hashing) | storage |
| Verified-piece disk writes outside the storage lock (lock-free positional writes) | storage |
| Peer churn (recycle idle connection slots for freshly discovered peers) | peers |
| Cancellable rate-limiter waits (100ms slices + closed-connection probe) | shutdown |

## Backlog

### P1 — clear value, reasonable effort

| # | Item | Benefit | Effort | Risk | Notes |
|---|---|---|---|---|---|
| 1 | **Parallel recheck on resume** | Startup time on large torrents: hash pieces concurrently (virtual threads) instead of one-by-one in `FileStorage.recheck()` | S | S | Pure CPU fan-out over read-only data; join at the end |
| 2 | **File preallocation** | Fewer filesystem fragments, disk-full surfaces at start instead of mid-download | S | S | `FileChannel.write` at `length-1` or `RandomAccessFile.setLength` when a file is first opened |
| 3 | **Persist partial-piece progress in `.bt4j`** | Resume without re-downloading up to 32 in-flight pieces (each up to pieceLength) after a stop/crash | M | M | Serialize `pieceBuffers` block bitmaps + data (or just discard data, keep received-block map and refetch missing blocks) |
| 4 | **Local Service Discovery (BEP 14)** | Finds LAN peers (multicast announce): huge speedup when a same-LAN peer exists | M | S | UDP multicast 239.192.152.143:6771; zero effect otherwise |
| 5 | **`fsync` before completion events** | Durability: force channels before firing `onDownloadCompleted` / deleting `.bt4j`, so a power cut cannot leave a "complete" file with unflushed pages | S | S | `FileChannel.force(false)` once per file at completion |

### P2 — real value, more effort or more situational

| # | Item | Benefit | Effort | Risk | Notes |
|---|---|---|---|---|---|
| 6 | **Reputation-aware churn** | Better slot usage in poor swarms: evict below-median (not just zero-rate) peers; remember which addresses unchoked us across reconnects | M | M | Needs anti-thrash guardrails; builds on existing churn |
| 7 | **Endgame duplicates only to fastest peers** | Less wasted tail bandwidth: pick duplicate targets by `recentRate`/RTT instead of any peer holding the piece | S | S | Touches only the endgame branch of `RarestFirstPicker.pick` |
| 8 | **Holepunch / NAT traversal (BEP 55)** | Reach NATed peers others cannot: more usable sources per swarm | L | M | Relay via connected peers; interacts with the extension protocol |
| 9 | **DHT hardening** | Faster peer discovery when peer-starved: token caching, announce refresh, more aggressive lookups while under-peered | M | M | Currently a fixed 60s loop regardless of need |
| 10 | **PEX dropped-hints** | Stop dialing peers the swarm reports as gone | S | S | Currently only "added" entries are consumed |
| 11 | **Upload-side request queue with Cancel support** | Serve Requests from a per-peer queue drained by the writer, letting incoming Cancel actually drop pending work | M | S | Today serving is synchronous, so incoming Cancel is a documented no-op |

### P3 — large, speculative, or measure-first

| # | Item | Benefit | Effort | Risk | Notes |
|---|---|---|---|---|---|
| 12 | **uTP transport (BEP 29)** | Reaches uTP-only peers; avoids bufferbloat on congested home links | L | L | A full reliable-transport implementation over UDP; biggest remaining protocol gap |
| 13 | **IPv6 DHT (BEP 32)** | Peer discovery on IPv6-only networks | M | M | Separate routing table + `want` parameter |
| 14 | **Super-seeding (BEP 16)** | Seeding-side efficiency for initial seeders (does not speed up downloads) | M | M | Advertise pieces selectively; only worth it for first-seed scenarios |
| 15 | **Socket buffer tuning (SO_RCVBUF/SO_SNDBUF)** | Single-connection throughput on high-BDP paths | S | M | Measure first: manual sizing disables OS autotuning (especially on Windows) and can easily hurt |
| 16 | **Block buffer pooling** | Less GC pressure at very high rates (each block is a fresh `byte[]`) | M | M | Measure allocation rates first; ZGC/G1 may make this unnecessary |
| 17 | **Sequential/streaming pick mode** | Preview-while-downloading (media); not a speed optimization | M | S | API flag switching the picker order; keep rarest-first as default |
| 18 | **Seeding read cache** | Fewer disk reads for hot pieces while seeding | M | M | The OS page cache already covers most of this; measure before building |

## Guiding constraints

- Zero runtime dependencies and Java-native sockets stay non-negotiable (see `CLAUDE.md`).
- Every optimization lands with a deterministic test that fails without it, wherever feasible
  (see `PeerChurnTest`, `EndgameCancelTest`, `AntiSnubbingTest`, `PerRequestTimeoutTest` for the pattern).
- Conservative defaults: an optimization must not regress a healthy swarm to improve a pathological one.
