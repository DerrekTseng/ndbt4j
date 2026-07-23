# ndbt4j

A BitTorrent download library, pure Java implementation.

## Hard constraints

- **Java 25** (local JDK is OpenJDK 25); the latest language features are allowed (record, sealed, pattern matching, virtual threads, etc.).
- **Zero runtime external dependencies**: `pom.xml` only allows test-scope dependencies (JUnit 5, already approved by the user). Maven is a build tool only.
- artifactId is `bt4j` (matching the root package `net.derrek.bt4j`).
- The network layer uses only native Java APIs: `java.net.Socket` / `ServerSocket` / `DatagramSocket` (or `java.nio` channels).

## Code structure

- Root package: `net.derrek.bt4j` (bencode / metainfo / tracker / dht / peer / peer.ext / piece / storage / session / util).
- **Public API entry point: `net.derrek.bt4j.Bt` (facade)** — `Bt.builder()` → `fromMagnet`/`fromTorrent` → `createDownloadJob` → `download` → `TorrentDownloadTask`. The underlying `session.BtClient` is the internal engine.
- Persistence: each job has a `<info-hash>.bt4j` in the target directory (engine-managed, atomic writes, deleted on completion without seeding, kept while seeding). A fresh download salvages existing partial files via the recheck inside `start()`; a restore trusts the .bt4j bitfield for fast resume.
- Progress: see `doc/TODO.md`. M0–M9 are all complete, and the public facade API is done.

## Language

- **All code comments, Javadoc, and log output are in English.**

## Logging

- Always use the JDK built-in `System.Logger` (`System.getLogger(Xxx.class.getName())`), to keep zero dependencies.
- **Use only the WARNING / ERROR / DEBUG / TRACE levels; do not use INFO.**
- Use a lambda supplier for DEBUG/TRACE messages (`LOG.log(Level.DEBUG, () -> ...)`) to avoid the string cost when disabled.
- To route to slf4j/logback, the consumer adds `org.slf4j:slf4j-jdk-platform-logging` in the *consuming* project; bt4j itself adds no dependency.

## Design

See `doc/DESIGN-DRAFT.md` for the overall goals, the magnet→metadata flow, module breakdown, and milestones.
Core use case: a server scheduler adds a magnet link → first fetch metadata to list files → the UI selects files and a destination before downloading → uploading can be stopped manually after completion.

## Specification documents

The official BEP specs are downloaded to `doc/beps/`; the index and implementation priority are in `doc/README.md`.
The most important are BEP 3 (the core protocol), BEP 20, and BEP 23; extensions are implemented in the order given in doc/README.md.

## Build

```
mvn compile
```
