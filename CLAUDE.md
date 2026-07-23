# ndbt4j

BitTorrent 下載套件（library），純 Java 實作。

## 硬性限制

- **Java 25**（本機為 OpenJDK 25），可使用最新語言特性（record、sealed、pattern matching、virtual threads 等）。
- **執行期零外部依賴**：`pom.xml` 只允許 test scope 依賴（JUnit 5 已獲使用者同意）。Maven 僅作為建置工具。
- artifactId 為 `bt4j`（與根 package `net.derrek.bt4j` 一致）。
- 網路層只用 Java 原生 API：`java.net.Socket` / `ServerSocket` / `DatagramSocket`（或 `java.nio` channels）。

## 程式結構

- 根 package：`net.derrek.bt4j`（bencode / metainfo / tracker / dht / peer / peer.ext / piece / storage / session）。
- 對外 API 入口：`session.BtClient` 與 `session.TorrentSession`。
- 進度見 `doc/TODO.md`：M0–M8 已完成（含磁力連結、DHT、做種、resume），M9 為擴充功能。

## Logging

- 一律用 JDK 內建 `System.Logger`（`System.getLogger(Xxx.class.getName())`），維持零依賴。
- **只用 WARNING / ERROR / DEBUG / TRACE 四個等級，不使用 INFO。**
- DEBUG/TRACE 訊息用 lambda supplier（`LOG.log(Level.DEBUG, () -> ...)`）避免未啟用時的字串成本。
- 引用方要導向 slf4j/logback：在「引用方」專案加 `org.slf4j:slf4j-jdk-platform-logging`，bt4j 本身不加任何依賴。

## 設計

整體目標、magnet→metadata 流程、模組切分與里程碑見 `doc/DESIGN-DRAFT.md`。
核心使用情境：伺服器排程加入磁力連結 → 先取 metadata 列出檔案 → UI 勾選檔案與目的地後才下載 → 下載完可手動關閉上傳。

## 規格文件

BEP 官方規格已下載至 `doc/beps/`，索引與實作優先順序見 `doc/README.md`。
最重要的是 BEP 3（協定本體）、BEP 20、BEP 23；擴充功能依 doc/README.md 的順序實作。

## 建置

```
mvn compile
```
