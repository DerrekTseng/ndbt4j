# bt4j

A BitTorrent download library implemented in pure Java 25. **Zero runtime dependencies** — the network layer uses only the native Java `Socket` / `ServerSocket` / `DatagramSocket`, and it is built entirely on Virtual Threads.

Designed to be embedded in your own server / scheduler: feed it a magnet link or a .torrent, get the file list within seconds, let the user pick which files to download and where, keep seeding after completion or stop uploading manually, and resume automatically after a restart.

---

## Features

- **Zero runtime dependencies**: JDK only; `pom.xml` has no runtime dependency (JUnit 5 is test-scope only).
- **Magnet links and .torrent**: both are supported; magnet links fetch metadata from the swarm via DHT / trackers.
- **Selective download**: for multi-file torrents you can pick a subset; unselected files never touch disk.
- **Automatic resume**: progress is cached in `<info-hash>.bt4j` in the target directory; without a cache it scans existing partial files and resumes.
- **Seeding control**: seed automatically after completion, or stop uploading at any time.
- **Global rate limiting**: set upload / download bandwidth caps.
- **Battle-tested protocol support**: DHT, PEX, Fast Extension, UDP trackers, multi-tracker, bad-peer blacklist, private torrents.
- **Virtual Threads**: two virtual threads per peer connection (blocking IO); hundreds of connections are no problem.

---

## Requirements & build

- **JDK 25** (uses record, sealed, pattern matching, virtual threads).
- Maven (build tool only).

```bash
mvn compile        # compile
mvn test           # run unit tests
mvn test -Dbt4j.integration=true    # also run the network integration tests (skipped by default)
```

---

## Quick start

The entry point is `net.derrek.bt4j.Bt`.

### 1. Download from a magnet link (selected files)

```java
import net.derrek.bt4j.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

try (Bt bt = Bt.builder().listenPort(6881).build()) {

    // Fetch the content (blocks until metadata arrives or times out)
    TorrentContent content = bt.fromMagnet("magnet:?xt=urn:btih:...", Duration.ofMinutes(3));
    System.out.println("name: " + content.name() + ", " + content.getFileList().size() + " files");

    // Filter the files to download (e.g. only .mp4)
    List<TorrentContentFile> wanted = content.getFileList().stream()
            .filter(f -> f.path().endsWith(".mp4"))
            .toList();

    // Create the job (true = keep seeding after completion); writes <info-hash>.bt4j in the target dir
    TorrentDownloadJob job = bt.createDownloadJob(wanted, Path.of("/data/movie"), true);

    // Start downloading
    TorrentDownloadTask task = bt.download(job);

    // Poll progress to update the UI
    while (task.state() == TaskState.DOWNLOADING) {
        System.out.printf("progress %.1f%%  down %d KB/s  peers=%d%n",
                task.progress() * 100, task.downloadRate() / 1024, task.connectedPeers());
        for (TorrentFileProgress fp : task.fileProgress()) {
            System.out.printf("  %s  %.1f%%%n", fp.file().path(), fp.progress() * 100);
        }
        Thread.sleep(1000);
    }
}
```

### 2. Download from a .torrent file

```java
TorrentContent content = bt.fromTorrent(Path.of("ubuntu.torrent"));   // java.io.File also accepted
TorrentDownloadJob job = bt.createDownloadJob(content.getFileList(), Path.of("/data/iso"), false);
bt.download(job);
```

### 3. Resume an entire directory after a restart

```java
// After the server restarts, scan the directory for all .bt4j files and resume them all
// (download is idempotent by info-hash, so duplicates are skipped automatically)
bt.restoreDownloadJobs(Path.of("/data/movie")).forEach(bt::download);
```

### 4. Seed and stop

```java
List<TorrentDownloadTask> downloading = bt.getDownloadTaskList();   // downloading
List<TorrentDownloadTask> seeding     = bt.getSeedingTaskList();    // seeding

bt.stop(task);        // hard stop: keeps the downloaded files and the .bt4j (can be restored later)
bt.deleteJob(task);   // deletes only the .bt4j, keeps the downloaded data files
```

### 5. Rate limiting and other settings

```java
Bt bt = Bt.builder()
        .listenPort(6881)              // TCP listen port (also the DHT UDP port); 0 = system-assigned
        .dhtEnabled(true)              // DHT (on by default)
        .downloadRateLimit(2_000_000)  // download cap 2 MB/s (<=0 = unlimited)
        .uploadRateLimit(500_000)      // upload cap 500 KB/s
        .maxPeersPerTorrent(50)        // max connections per torrent
        .build();
```

The upload rate limit has special semantics:

```java
.uploadRateLimit(500_000)  // > 0: limit to that rate (500 KB/s)
.uploadRateLimit(-1)       // < 0: unlimited (default)
.uploadRateLimit(0)        // = 0: no uploading at all (stays choking and rejects requests while downloading/seeding)
```

---

## API overview

| Type | Description |
|------|-------------|
| `Bt` | Engine entry point. Created via `Bt.builder()...build()`; `AutoCloseable`. |
| `TorrentContent` | Torrent content: `name()`, `getFileList()`, `totalSize()`, `infoHashHex()`. |
| `TorrentContentFile` | A single file: `index()`, `path()`, `size()`. Used for filtering/selection. |
| `TorrentDownloadJob` | A download job description (maps to a `.bt4j`). Produced by `createDownloadJob` / `restoreDownloadJobs`. |
| `TorrentDownloadTask` | A live handle, **all getters**: `state()`, `progress()`, `downloadedBytes()`, `uploadedBytes()`, `downloadRate()`, `uploadRate()`, `connectedPeers()`, `fileProgress()`. |
| `TorrentFileProgress` | Per-file progress: `file()`, `downloadedBytes()`, `totalBytes()`, `progress()`, `completed()`. |
| `TaskState` | `DOWNLOADING` / `SEEDING` / `STOPPED` / `ERROR`. |

Main `Bt` methods:

- `fromMagnet(String, Duration)` — blocks fetching metadata; throws `TimeoutException` on timeout.
- `fromTorrent(Path | File)` — parses a .torrent and returns immediately.
- `createDownloadJob(List<TorrentContentFile>, Path | File, boolean seedAfter)` — creates a job and writes the `.bt4j`. Mixing files from different torrents throws `IllegalArgumentException`; an existing `.bt4j` for the same torrent throws `IllegalStateException`.
- `restoreDownloadJobs(Path | File)` — scans the directory and returns a `List` (empty if none).
- `download(TorrentDownloadJob)` — starts execution, returns a task (deduplicated by info-hash).
- `getDownloadTaskList()` / `getSeedingTaskList()`, `stop(task)`, `deleteJob(task)`, `close()`.

---

## The `.bt4j` resume mechanism

Persistence is managed automatically by the engine; the application only maintains "torrents / directories":

- Each job has one `<info-hash>.bt4j` file in its target directory (multiple torrents can coexist in one directory).
- It contains the metadata, completed pieces, selected files, destination, etc.; serialized with bencoding.
- **Atomic writes** (temp + rename), only every 5 seconds and only when progress changed, to avoid corruption from a crash mid-write.
- **On completion**: no seeding → delete the `.bt4j`; seeding → keep it (still resumable if restarted while seeding).
- **Two resume paths on start**:
  - `.bt4j` present → trust its progress, **no SHA-1 recompute**, fast resume.
  - No `.bt4j` but partial files exist in the directory → scan and re-verify the disk (recheck) to salvage the completed parts.

---

## Logging

Uses the JDK's built-in `System.Logger` (keeping zero dependencies), with only the **WARNING / ERROR / DEBUG / TRACE** levels (no INFO); messages are in English.

To route logs to SLF4J / Logback, add the bridge dependency in **your own project** (bt4j itself adds nothing):

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-jdk-platform-logging</artifactId>
    <version>2.0.x</version>
</dependency>
```

Then you can control levels under the `net.derrek.bt4j` logger name in logback.

---

## Supported BEPs

| BEP | Content |
|-----|---------|
| 3 | The core protocol (bencoding, .torrent, tracker, peer wire protocol) |
| 4 | Reserved bit allocations |
| 5 | DHT (finding peers for trackerless magnets) |
| 6 | Fast Extension (HaveAll/HaveNone, Reject, Allowed Fast) |
| 7 | IPv6 compact peers |
| 9 | Fetching metadata from a magnet (ut_metadata) |
| 10 | Extension protocol framework |
| 11 | Peer Exchange (ut_pex) |
| 12 | Multi-tracker (announce-list) |
| 15 | UDP tracker |
| 20 | Peer ID conventions |
| 23 | Tracker compact peer list |
| 27 | Private torrents (disable DHT/PEX) |

The spec documents and implementation priority are under [`doc/`](doc/).

---

## Project layout

```
net.derrek.bt4j            public facade (Bt, TorrentContent, TorrentDownloadTask, ...)
├── bencode               bencoding encode/decode
├── metainfo              .torrent / magnet parsing, info-hash
├── tracker               HTTP / UDP tracker, multi-tracker scheduling
├── dht                   Kademlia DHT (BEP 5)
├── peer                  peer wire protocol, connection management
│   └── ext               extension protocol (BEP 10), ut_metadata, ut_pex
├── piece                 piece scheduling (rarest-first), selective download, SHA-1 verification
├── storage               disk IO, resume data
├── session               internal engine (BtClient, TorrentSession)
└── util                  rate limiter and other utilities
```

---

## Limitations & notes

- The initial version is plain TCP; uTP (BEP 29) and encrypted connections (MSE/PE) are not supported.
- Supports BitTorrent v1; v2 (BEP 52) is not implemented yet.
- `fromMagnet` is a blocking call; a dead torrent (no peers in the swarm) will time out with an error.
