# BEP Specification Index

Source: https://www.bittorrent.org/beps/bep_0000.html (downloaded 2026-07-23; the original HTML is stored under `beps/`).

## Core (required reading to implement a downloader)

| BEP | Title | Status | Notes |
|-----|-------|--------|-------|
| [0003](beps/bep_0003.html) | The BitTorrent Protocol Specification | Final | The core protocol: .torrent format, bencoding, tracker HTTP protocol, peer wire protocol |
| [0020](beps/bep_0020.html) | Peer ID Conventions | Final | Peer ID naming convention (e.g. `-ND1000-`) |
| [0023](beps/bep_0023.html) | Tracker Returns Compact Peer Lists | Final | The tracker's compact peer list (essential in practice) |
| [0004](beps/bep_0004.html) | Known Number Allocations | Active | Allocation of handshake reserved bits |
| [0000](beps/bep_0000.html) | Index of BitTorrent Enhancement Proposals | Active | The overall BEP index |

## Common extensions (in implementation-priority order)

| BEP | Title | Status | Notes |
|-----|-------|--------|-------|
| [0010](beps/bep_0010.html) | Extension Protocol | Final | Extension message framework, the basis for BEP 9/11 |
| [0009](beps/bep_0009.html) | Extension for Peers to Send Metadata Files | Final | Magnet links: fetch metadata from peers |
| [0012](beps/bep_0012.html) | Multitracker Metadata Extension | Accepted | `announce-list` multi-tracker |
| [0015](beps/bep_0015.html) | UDP Tracker Protocol | Final | UDP trackers (used by most trackers) |
| [0005](beps/bep_0005.html) | DHT Protocol | Final | Trackerless peer discovery (Kademlia) |
| [0006](beps/bep_0006.html) | Fast Extension | Final | Have All/Have None, Reject Request, etc. |
| [0011](beps/bep_0011.html) | Peer Exchange (PEX) | Final | Exchange more peers with connected peers |
| [0027](beps/bep_0027.html) | Private Torrents | Accepted | private flag: disable DHT/PEX |
| [0014](beps/bep_0014.html) | Local Service Discovery | Draft | LAN peer discovery |
| [0019](beps/bep_0019.html) | WebSeed - HTTP/FTP Seeding | Final | HTTP source as a seed |
| [0007](beps/bep_0007.html) | IPv6 Tracker Extension | Draft | IPv6 support |
| [0021](beps/bep_0021.html) | Extension for partial seeds | Final | Partial seeding |

## Advanced / reference

| BEP | Title | Status | Notes |
|-----|-------|--------|-------|
| [0029](beps/bep_0029.html) | uTorrent Transport Protocol (uTP) | Draft | Transport protocol over UDP |
| [0040](beps/bep_0040.html) | Canonical Peer Priority | Draft | Peer connection priority |
| [0042](beps/bep_0042.html) | DHT Security Extension | Draft | DHT security |
| [0047](beps/bep_0047.html) | Padding files and extended file attributes | Draft | Padding files |
| [0048](beps/bep_0048.html) | Tracker Protocol Extension: Scrape | Draft | Tracker scrape |
| [0052](beps/bep_0052.html) | The BitTorrent Protocol Specification v2 | Draft | BitTorrent v2 (SHA-256, merkle tree) |
| [0053](beps/bep_0053.html) | Magnet URI extension - Select specific file indices | Draft | Select specific files in a magnet |
| [0055](beps/bep_0055.html) | Holepunch extension | Draft | NAT traversal |
