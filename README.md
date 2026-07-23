# bt4j

純 Java 25 實作的 BitTorrent 下載套件（library）。**執行期零外部依賴**，網路層只用 Java 原生 `Socket` / `ServerSocket` / `DatagramSocket`，全面採用 Virtual Threads。

適合嵌入自己的伺服器 / 排程程式：丟磁力連結或 .torrent 進來，幾秒內取得檔案清單，讓使用者勾選要下載的檔案與位置，下載完可繼續做種或手動關閉上傳，重啟後自動續傳。

---

## 特色

- **零執行期依賴**：只靠 JDK，`pom.xml` 不含任何 runtime 依賴（JUnit 5 僅測試用）。
- **磁力連結與 .torrent**：兩者皆可；磁力連結透過 DHT / tracker 向 swarm 取得 metadata。
- **選擇性下載**：多檔 torrent 可只勾選部分檔案，未勾選的檔案完全不落地。
- **自動續傳**：目標目錄的 `<info-hash>.bt4j` 快取進度；沒有快取時掃描既有半成品續傳。
- **做種控制**：下載完可自動做種，也可隨時手動停止上傳。
- **全域限速**：可設定上傳／下載頻寬上限。
- **實戰協定支援**：DHT、PEX、Fast Extension、UDP tracker、多 tracker、壞 peer 黑名單、private torrent。
- **Virtual Threads**：每條 peer 連線兩條 virtual thread（阻塞式 IO），數百連線無壓力。

---

## 需求與建置

- **JDK 25**（使用 record、sealed、pattern matching、virtual threads）。
- Maven（僅作建置工具）。

```bash
mvn compile        # 編譯
mvn test           # 執行單元測試
mvn test -Dbt4j.integration=true    # 額外跑需要網路的整合測試（預設略過）
```

---

## 快速開始

對外入口是 `net.derrek.bt4j.Bt`。

### 1. 磁力連結下載（勾選檔案）

```java
import net.derrek.bt4j.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

try (Bt bt = Bt.builder().listenPort(6881).build()) {

    // 取得內容（阻塞直到 metadata 到齊或逾時）
    TorrentContent content = bt.fromMagnet("magnet:?xt=urn:btih:...", Duration.ofMinutes(3));
    System.out.println("名稱：" + content.name() + "，共 " + content.getFileList().size() + " 個檔案");

    // 過濾要下載的檔案（例如只下載 .mp4）
    List<TorrentContentFile> wanted = content.getFileList().stream()
            .filter(f -> f.path().endsWith(".mp4"))
            .toList();

    // 建立任務（true = 下載完後繼續做種），會在目標目錄寫入 <info-hash>.bt4j
    TorrentDownloadJob job = bt.createDownloadJob(wanted, Path.of("/data/movie"), true);

    // 開始下載
    TorrentDownloadTask task = bt.download(job);

    // 輪詢進度更新 UI
    while (task.state() == TaskState.DOWNLOADING) {
        System.out.printf("進度 %.1f%%  ↓%d KB/s  peers=%d%n",
                task.progress() * 100, task.downloadRate() / 1024, task.connectedPeers());
        for (TorrentFileProgress fp : task.fileProgress()) {
            System.out.printf("  %s  %.1f%%%n", fp.file().path(), fp.progress() * 100);
        }
        Thread.sleep(1000);
    }
}
```

### 2. 從 .torrent 檔下載

```java
TorrentContent content = bt.fromTorrent(Path.of("ubuntu.torrent"));   // 亦接受 java.io.File
TorrentDownloadJob job = bt.createDownloadJob(content.getFileList(), Path.of("/data/iso"), false);
bt.download(job);
```

### 3. 重啟後自動續傳整個目錄

```java
// 伺服器重啟後，掃描目錄裡所有 .bt4j 並全部恢復（download 以 info-hash 去重，重複自動略過）
bt.restoreDownloadJobs(Path.of("/data/movie")).forEach(bt::download);
```

### 4. 做種與停止

```java
List<TorrentDownloadTask> downloading = bt.getDownloadTaskList();   // 下載中
List<TorrentDownloadTask> seeding     = bt.getSeedingTaskList();    // 做種中

bt.stop(task);        // 硬停：保留已下載檔案與 .bt4j（之後可再 restore）
bt.deleteJob(task);   // 只刪除 .bt4j，保留已下載的資料檔案
```

### 5. 限速與其他設定

```java
Bt bt = Bt.builder()
        .listenPort(6881)              // TCP listen port（同時作 DHT UDP port）；0 = 系統指派
        .dhtEnabled(true)              // DHT（預設開）
        .downloadRateLimit(2_000_000)  // 下載上限 2 MB/s（<=0 = 不限）
        .uploadRateLimit(500_000)      // 上傳上限 500 KB/s
        .maxPeersPerTorrent(50)        // 每 torrent 最大連線數
        .build();
```

上傳限速有特殊語意：

```java
.uploadRateLimit(500_000)  // > 0：限制在該速率（500 KB/s）
.uploadRateLimit(-1)       // < 0：不限速（預設）
.uploadRateLimit(0)        // = 0：完全不上傳（下載/做種期間對 peer 保持 choke、拒絕 request）
```

---

## API 一覽

| 型別 | 說明 |
|------|------|
| `Bt` | 引擎入口。`Bt.builder()...build()` 建立；`AutoCloseable`。 |
| `TorrentContent` | torrent 內容：`name()`、`getFileList()`、`totalSize()`、`infoHashHex()`。 |
| `TorrentContentFile` | 單一檔案：`index()`、`path()`、`size()`。供過濾勾選。 |
| `TorrentDownloadJob` | 下載任務描述（對應 `.bt4j`）。由 `createDownloadJob` / `restoreDownloadJobs` 產生。 |
| `TorrentDownloadTask` | 執行中把手，**全 getter**：`state()`、`progress()`、`downloadedBytes()`、`uploadedBytes()`、`downloadRate()`、`uploadRate()`、`connectedPeers()`、`fileProgress()`。 |
| `TorrentFileProgress` | 逐檔進度：`file()`、`downloadedBytes()`、`totalBytes()`、`progress()`、`completed()`。 |
| `TaskState` | `DOWNLOADING` / `SEEDING` / `STOPPED` / `ERROR`。 |

`Bt` 主要方法：

- `fromMagnet(String, Duration)` — 阻塞取 metadata，逾時拋 `TimeoutException`。
- `fromTorrent(Path | File)` — 解析 .torrent，立即回傳。
- `createDownloadJob(List<TorrentContentFile>, Path | File, boolean seedAfter)` — 建立任務並寫 `.bt4j`。混入不同種子的檔案會拋 `IllegalArgumentException`；同種子的 `.bt4j` 已存在會拋 `IllegalStateException`。
- `restoreDownloadJobs(Path | File)` — 掃描目錄回傳 `List`（沒有回空清單）。
- `download(TorrentDownloadJob)` — 開始執行，回傳 task（以 info-hash 去重）。
- `getDownloadTaskList()` / `getSeedingTaskList()`、`stop(task)`、`deleteJob(task)`、`close()`。

---

## `.bt4j` 續傳機制

持久化由引擎自動管理，AP 只需維護「種子 / 資料夾」：

- 每個任務在其目標目錄下有一個 `<info-hash>.bt4j` 檔（同一目錄可並存多個 torrent）。
- 內含 metadata、已完成的 piece、勾選檔案、目的地等；以 bencoding 序列化。
- **原子寫入**（temp + rename），每 5 秒且進度有變化才寫，避免寫到一半 crash 損毀。
- **下載完成**：不做種 → 刪除 `.bt4j`；做種 → 保留（做種中重啟仍可續傳）。
- **啟動續傳的兩條路徑**：
  - 有 `.bt4j` → 信任其進度，**不重算 SHA-1**，快速續傳。
  - 無 `.bt4j` 但目錄有半成品 → 掃描並重新驗證磁碟（recheck），救回已完成的部分。

---

## Logging

使用 JDK 內建 `System.Logger`（維持零依賴），等級只用 **WARNING / ERROR / DEBUG / TRACE**（無 INFO），訊息皆為英文。

引用方若要導向 SLF4J / Logback，只需在**自己的專案**加入橋接依賴（bt4j 本身不加）：

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-jdk-platform-logging</artifactId>
    <version>2.0.x</version>
</dependency>
```

之後即可用 `net.derrek.bt4j` 這個 logger 名稱在 logback 控制等級。

---

## 支援的 BEP

| BEP | 內容 |
|-----|------|
| 3 | 協定本體（bencoding、.torrent、tracker、peer wire protocol） |
| 4 | reserved bits 分配 |
| 5 | DHT（trackerless magnet 找 peer） |
| 6 | Fast Extension（HaveAll/HaveNone、Reject、Allowed Fast） |
| 7 | IPv6 compact peers |
| 9 | 磁力連結取 metadata（ut_metadata） |
| 10 | 擴充協定框架 |
| 11 | Peer Exchange（ut_pex） |
| 12 | 多 tracker（announce-list） |
| 15 | UDP tracker |
| 20 | Peer ID 慣例 |
| 23 | Tracker compact peer list |
| 27 | Private torrents（停用 DHT/PEX） |

規格原文與實作優先序見 [`doc/`](doc/)。

---

## 專案結構

```
net.derrek.bt4j            對外 facade（Bt、TorrentContent、TorrentDownloadTask…）
├── bencode               bencoding 編解碼
├── metainfo              .torrent / magnet 解析、info-hash
├── tracker               HTTP / UDP tracker、多 tracker 排程
├── dht                   Kademlia DHT（BEP 5）
├── peer                  peer wire protocol、連線管理
│   └── ext               擴充協定（BEP 10）、ut_metadata、ut_pex
├── piece                 piece 排程（rarest-first）、選擇性下載、SHA-1 驗證
├── storage               磁碟 IO、resume 資料
├── session               內部引擎（BtClient、TorrentSession）
└── util                  限速器等工具
```

---

## 限制與備註

- 初版為純 TCP 明文，不支援 uTP（BEP 29）與加密連線（MSE/PE）。
- 支援 BitTorrent v1；v2（BEP 52）尚未實作。
- `fromMagnet` 是阻塞呼叫；死種（swarm 無任何 peer）會逾時拋錯。
