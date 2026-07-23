# BEP 規格文件索引

來源：https://www.bittorrent.org/beps/bep_0000.html（下載日期：2026-07-23，HTML 原文存放於 `beps/`）

## 核心（實作下載器必讀）

| BEP | 標題 | 狀態 | 說明 |
|-----|------|------|------|
| [0003](beps/bep_0003.html) | The BitTorrent Protocol Specification | Final | 協定本體：.torrent 格式、bencoding、tracker HTTP 協定、peer wire protocol |
| [0020](beps/bep_0020.html) | Peer ID Conventions | Final | Peer ID 命名慣例（例如 `-ND1000-`） |
| [0023](beps/bep_0023.html) | Tracker Returns Compact Peer Lists | Final | Tracker 回傳的 compact peer list（實務上必備） |
| [0004](beps/bep_0004.html) | Known Number Allocations | Active | handshake reserved bits 的用途分配 |
| [0000](beps/bep_0000.html) | Index of BitTorrent Enhancement Proposals | Active | BEP 總索引 |

## 常見擴充（依實作優先順序）

| BEP | 標題 | 狀態 | 說明 |
|-----|------|------|------|
| [0010](beps/bep_0010.html) | Extension Protocol | Final | 擴充訊息框架，BEP 9/11 的基礎 |
| [0009](beps/bep_0009.html) | Extension for Peers to Send Metadata Files | Final | magnet link：從 peer 取得 metadata |
| [0012](beps/bep_0012.html) | Multitracker Metadata Extension | Accepted | `announce-list` 多 tracker |
| [0015](beps/bep_0015.html) | UDP Tracker Protocol | Final | UDP tracker（大多數 tracker 使用） |
| [0005](beps/bep_0005.html) | DHT Protocol | Final | 無 tracker 的 peer 發現（Kademlia） |
| [0006](beps/bep_0006.html) | Fast Extension | Final | Have All/Have None、Reject Request 等 |
| [0011](beps/bep_0011.html) | Peer Exchange (PEX) | Final | 從已連線 peer 交換更多 peer |
| [0027](beps/bep_0027.html) | Private Torrents | Accepted | private flag：停用 DHT/PEX |
| [0014](beps/bep_0014.html) | Local Service Discovery | Draft | 區網 peer 發現 |
| [0019](beps/bep_0019.html) | WebSeed - HTTP/FTP Seeding | Final | HTTP 來源當種子 |
| [0007](beps/bep_0007.html) | IPv6 Tracker Extension | Draft | IPv6 支援 |
| [0021](beps/bep_0021.html) | Extension for partial seeds | Final | 部分做種 |

## 進階／參考

| BEP | 標題 | 狀態 | 說明 |
|-----|------|------|------|
| [0029](beps/bep_0029.html) | uTorrent Transport Protocol (uTP) | Draft | UDP 上的傳輸協定 |
| [0040](beps/bep_0040.html) | Canonical Peer Priority | Draft | peer 連線優先序 |
| [0042](beps/bep_0042.html) | DHT Security Extension | Draft | DHT 安全性 |
| [0047](beps/bep_0047.html) | Padding files and extended file attributes | Draft | padding 檔案 |
| [0048](beps/bep_0048.html) | Tracker Protocol Extension: Scrape | Draft | tracker scrape |
| [0052](beps/bep_0052.html) | The BitTorrent Protocol Specification v2 | Draft | BitTorrent v2（SHA-256、merkle tree） |
| [0053](beps/bep_0053.html) | Magnet URI extension - Select specific file indices | Draft | magnet 選擇特定檔案 |
| [0055](beps/bep_0055.html) | Holepunch extension | Draft | NAT 穿透 |
