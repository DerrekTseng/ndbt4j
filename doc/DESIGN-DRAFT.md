# ndbt4j 設計草稿

> 狀態：草稿（2026-07-23）。API 細節尚未定案，先確立目標、流程與里程碑。

## 1. 最終目標

做一個純 Java 25、零外部依賴的 BitTorrent 下載套件，部署在使用者自己的伺服器上，由上層應用（排程器 + Web UI）呼叫。

### 使用情境（上層應用的需求，套件必須支援）

1. 伺服器排程自動抓到多個磁力連結，交給套件處理。
2. 加入磁力連結後，先只取得 metadata（檔案清單），**不開始下載**。
3. UI 顯示 torrent 內的檔案清單，使用者**勾選要下載的檔案**與**下載目的地**後才開始下載。
4. 下載完成後預設繼續做種（上傳），使用者可在 UI **手動關閉上傳**。
5. 也能直接讀取 .torrent 檔案走同樣流程。

### 明確不做（初版）

- 建立/製作 .torrent 檔（只讀不寫，但可把 magnet 取得的 metadata 匯出成 .torrent）
- uTP（BEP 29）、加密連線（MSE/PE）——初版純 TCP 明文
- BitTorrent v2（BEP 52）——先支援 v1，v2 留介面擴充空間

## 2. 關鍵問題：磁力連結如何變成 .torrent？

磁力連結**不含**檔案清單，只含：

```
magnet:?xt=urn:btih:<40字元 hex 的 info-hash>
        &dn=<顯示名稱>            (選填)
        &tr=<tracker URL>         (選填、可多個)
        &x.pe=<peer 位址>         (選填)
```

metadata（bencoded 的 info 字典）要**向 swarm 中的 peer 索取**，流程：

```
磁力連結
  │ 解析出 info-hash + tracker 清單
  ▼
找 peer ── tracker announce (BEP 3/15/23)
  │     └─ DHT get_peers (BEP 5)     ← 沒有 tracker 時的唯一辦法
  ▼
TCP 連上 peer，handshake 時亮出 extension bit (BEP 10)
  ▼
extension handshake：對方支援 ut_metadata 且告知 metadata_size (BEP 9)
  ▼
分塊（16 KiB/塊）向多個 peer 請求 metadata piece
  ▼
拼完後驗證 SHA-1(info) == info-hash   ← 防偽造，必做
  ▼
得到 info 字典 → 檔案清單可給 UI 顯示
                → 可包上 announce 等欄位匯出成標準 .torrent 檔
```

**結論：可以。** 只要實作 BEP 5 + 10 + 9，就能從磁力連結還原出 .torrent（等價的 metadata），這也正是 UI 要「先看檔案清單再勾選」所依賴的機制。

注意：如果 swarm 裡**一個 peer 都找不到**（死種），metadata 就拿不到——這是協定天性，上層要有逾時/失敗狀態可以顯示。

## 3. 功能需求 ↔ BEP 對照

| 需求 | 依賴的 BEP | 優先級 |
|------|-----------|:------:|
| bencoding 編/解碼 | 3 | P0 |
| 讀取 .torrent 檔 | 3 | P0 |
| HTTP tracker announce（compact） | 3, 23 | P0 |
| Peer wire protocol、piece 下載與 SHA-1 驗證 | 3 | P0 |
| Peer ID | 20 | P0 |
| **選擇性下載指定檔案** | 3（piece 選擇策略） | P0 |
| UDP tracker | 15 | P1 |
| 多 tracker（announce-list） | 12 | P1 |
| 擴充協定框架 | 10 | P1 |
| **磁力連結 → metadata** | 9 | P1 |
| **DHT**（磁力連結沒 tracker 時必要） | 5 | P1 |
| 做種／關閉上傳 | 3 | P1 |
| PEX 加速找 peer | 11 | P2 |
| Fast Extension | 6 | P2 |
| private torrent（停用 DHT/PEX） | 27 | P2 |
| 本地探索 LSD | 14 | P3 |

### 「選擇檔案下載」的技術重點

torrent 的 piece 是跨檔案連續切的，勾選檔案 A 時，A 頭尾的 piece 可能**與未勾選的檔案共用**。策略：

- 需求集合 = 所有「與勾選檔案有重疊」的 piece。
- 邊界 piece 中不屬於勾選檔案的部分：仍要下載（為了驗證 hash），但只把勾選檔案的位元組寫進目的地。
- 未勾選檔案不落地（不建檔、不預配空間）。

### 「關閉上傳」的語意

- 停止做種 = 對 tracker 發 `stopped` announce、關閉接受連入、對既有連線停止回應 request（或直接斷線）。
- 需要區分三種狀態：`下載中`（邊下邊上傳，協定要求互惠）、`做種中`、`已停止`。
- 「下載中完全不上傳」不在初版目標（會被 choke 演算法懲罰，效率極差）。

## 4. 架構草圖（模組切分）

```
net.derrek.bt4j
├── bencode      # bencoding 編/解碼（純函式，無 IO）
├── metainfo     # .torrent / info 字典模型、magnet URI 解析、info-hash 計算
├── tracker      # HTTP(S) tracker、UDP tracker、announce 排程
├── dht          # BEP 5：路由表、KRPC(UDP)、get_peers/announce_peer
├── peer         # peer wire protocol：handshake、訊息編解碼、choke/interest 狀態機
│   └── ext      # BEP 10 擴充框架、BEP 9 ut_metadata、BEP 11 PEX
├── piece        # piece/block 排程（rarest-first）、SHA-1 驗證、檔案選擇 → piece 集合
├── storage      # 磁碟 IO：piece ↔ 檔案位移對映、稀疏寫入、resume 資料
└── session      # 對外入口：TorrentSession 生命週期、狀態、事件回呼
```

技術選型（傾向，未定案）：

- **每 peer 連線一條 virtual thread + 阻塞式 `java.net.Socket`**：Java 25 下比 NIO selector 簡單得多，數百連線毫無壓力。
- UDP（tracker/DHT）用 `DatagramSocket`，各一條專屬 thread。
- 磁碟 IO 用 `RandomAccessFile`／`FileChannel`。
- 事件通知用 listener/callback 介面，讓上層 UI 拿到進度、狀態變化。

## 5. API 方向（僅示意，之後再定案）

上層情境需要的最小能力，先用虛擬碼描述：

```java
var client = BtClient.builder()
        .listenPort(6881)
        .build();                                 // 全域：DHT、port、peer id

// 磁力連結 → 先只抓 metadata
var session = client.addMagnet("magnet:?xt=urn:btih:...");
Metainfo meta = session.awaitMetadata(Duration.ofMinutes(5)); // 或 callback
meta.files();                                     // UI 顯示檔案清單
meta.saveTorrentFile(path);                       // 需要的話匯出 .torrent

// 使用者勾選後才開始下載
session.start(DownloadPlan.builder()
        .saveTo(Path.of("/data/downloads"))
        .selectFiles(fileIndex1, fileIndex2)
        .build());

session.onProgress(p -> ...);                     // 進度事件給 UI
session.stopSeeding();                            // 手動關閉上傳
session.close();

// .torrent 檔走同一條路，只是跳過 metadata 取得
var session2 = client.addTorrentFile(Path.of("x.torrent"));
```

待決事項：

- [ ] 錯誤/逾時模型（checked exception？狀態機 + 事件？）
- [ ] resume：重啟伺服器後如何恢復半途的下載（需持久化已完成 piece 的 bitfield）
- [ ] 全域限速（上傳/下載頻寬）要不要進初版
- [ ] 事件模型：callback vs. 可輪詢的狀態快照（給排程器用可能後者更好）

## 6. 里程碑

| # | 內容 | 驗收方式 |
|---|------|---------|
| M0 | bencoding 編/解碼 | 單元測試（含 torture case） |
| M1 | .torrent 解析、info-hash、magnet URI 解析 | 對真實 .torrent 檔驗證 hash |
| M2 | HTTP tracker announce + compact peers | 對公開 tracker 拿到 peer 清單 |
| M3 | peer wire：單檔 torrent 完整下載 + SHA-1 驗證 | 實際下載一個小型合法 torrent |
| M4 | 多檔 torrent + **檔案選擇下載** | 只落地勾選的檔案 |
| M5 | UDP tracker + 多 tracker | |
| M6 | BEP 10 擴充框架 + **BEP 9（磁力 → metadata）** | 磁力連結能列出檔案清單並匯出 .torrent |
| M7 | **DHT**（無 tracker 的磁力連結） | 純 DHT 找到 peer |
| M8 | 做種、**stopSeeding**、resume 持久化 | 重啟後續傳；UI 可關上傳 |
| M9 | PEX、Fast Extension、private torrent | |

> M0–M4 不需要 DHT 也能端到端跑通（用有 tracker 的 torrent 測試）；
> 磁力連結情境要到 M6（有 tracker 的磁力連結）／M7（純 DHT）才完整。
