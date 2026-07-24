# Optimization Backlog

Status of performance work on bt4j. Effort/Risk: S(mall) / M(edium) / L(arge).

## Done

### Scheduling and peers
| Optimization | Notes |
|---|---|
| Proper choke algorithm | tit-for-tat, optimistic unchoke, hysteresis to avoid flapping |
| Anti-snubbing | abandon a peer silent for 60s, redistribute its blocks |
| Adaptive pipeline depth | bandwidth-delay product, RTT-tuned per peer |
| Per-request timeout | re-queue one stuck block instead of killing the connection |
| Endgame mode + Cancel | stop duplicate transfers as soon as a block arrives |
| Endgame duplicate targeting | duplicates only to peers that actually deliver (P2 #7) |
| Peer churn | recycle idle connection slots for freshly discovered peers |
| Reputation-aware churn | remember bytes per address across reconnects; laggard tier (P2 #6) |
| PEX dropped hints | skip dialing peers the swarm reports gone; never disconnects a live peer (P2 #10) |
| Local Service Discovery | BEP 14 LAN peer discovery over multicast, fail-soft (P1 #4) |
| Adaptive DHT cadence | 15s lookups while peer-starved, 60s when healthy (P2 #9) |

### Storage and I/O
| Optimization | Notes |
|---|---|
| SHA-1 outside the storage lock | concurrent piece hashing |
| Disk writes outside the storage lock | lock-free positional writes |
| Parallel recheck | resume scan fans out across cores (P1 #1) |
| File preallocation | full length reserved on first open; fewer extents, early disk-full (P1 #2) |
| flush() on completion | data forced to disk before completion is announced (P1 #5) |
| Partial-piece resume | in-flight pieces survive a restart instead of being refetched (P1 #3) |
| Seeding read cache | small LRU of hot pieces; disk read moved off the lock (P3 #18) |

### Other
| Optimization | Notes |
|---|---|
| Cancellable rate-limiter waits | 100ms slices + closed-connection probe |
| Upload queue with Cancel | serving moved off the read loop; Cancel drops queued work (P2 #11) |
| Sequential (streaming) mode | opt-in file-order picking for preview/playback (P3 #17) |
| Mid-download re-selection | change selected files on a running/seeding torrent (`start()` again / `Bt.changeSelection`) |
| NAT port mapping | NAT-PMP + UPnP-IGD, opt-in, so a NATed host accepts incoming peers |
| Web seeds (BEP 19) | download from an HTTP mirror listed in url-list; completes even with zero peers |
| uTP transport (BEP 29) | LEDBAT-controlled reliable stream over UDP; peers can connect over uTP or TCP (opt-in) |

## Remaining

### Deliberately not done — measure first
These were in the backlog flagged "measure first", and that judgement still stands: implementing them
blind is as likely to hurt as help, so they need a benchmark before any code lands.

| # | Item | Why not yet |
|---|---|---|
| 15 | Socket buffer tuning (SO_RCVBUF/SO_SNDBUF) | Manually sizing socket buffers **disables OS autotuning**, which on modern Linux and Windows is usually better than a fixed guess. A wrong value silently caps throughput on exactly the high-BDP links it is meant to help. Needs a real high-latency measurement first. |
| 16 | Block buffer pooling | Every block currently allocates a fresh `byte[]`. Pooling adds lifetime bugs and retention risk for a gain that modern GCs (ZGC/G1) may already make negligible. Needs an allocation-rate profile under load first. |

### Not done — large protocol work
| # | Item | Effort | Why not yet |
|---|---|---|---|
| 8 | Holepunch / NAT traversal (BEP 55) | L | Requires relayed coordination through connected peers, and cannot be meaningfully verified without a real NATed multi-host setup — local tests would prove nothing. |
| 13 | IPv6 DHT (BEP 32) | M | Needs a second routing table and `want` negotiation. Self-contained but sizeable; the IPv4 DHT plus BEP 7 compact IPv6 peers already cover the common cases. |
| 14 | Super-seeding (BEP 16) | M | Seeding-side only: it helps an *initial seeder* distribute a new torrent, and does **nothing** for download speed. Correct implementation needs per-peer piece-advertisement bookkeeping tied to observing swarm propagation. |

## Guiding constraints

- Zero runtime dependencies and Java-native sockets stay non-negotiable (see `CLAUDE.md`).
- Every optimization lands with a deterministic test that fails without it wherever feasible
  (`PeerChurnTest`, `EndgameCancelTest`, `AntiSnubbingTest`, `PerRequestTimeoutTest`, `UploadCancelTest`).
- Conservative defaults: an optimization must not regress a healthy swarm to improve a pathological one.
  This is why churn never evicts a contributing peer, endgame duplicates are withheld from idle peers, and
  sequential mode is opt-in.
