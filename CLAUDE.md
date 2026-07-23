# ndbt4j

BitTorrent 下載套件（library），純 Java 實作。

## 硬性限制

- **Java 25**（本機為 OpenJDK 25），可使用最新語言特性（record、sealed、pattern matching、virtual threads 等）。
- **零外部依賴**：`pom.xml` 不得加入任何 `<dependencies>`（測試用依賴例外，需先與使用者確認）。Maven 僅作為建置工具。
- 網路層只用 Java 原生 API：`java.net.Socket` / `ServerSocket` / `DatagramSocket`（或 `java.nio` channels）。

## 程式結構

- 根 package：`net.derrek.bt4j`（bencode / metainfo / tracker / dht / peer / peer.ext / piece / storage / session）。
- 所有類別骨架已建立，方法本體為 `UnsupportedOperationException("尚未實作")`，依 `doc/TODO.md` 逐項實作。
- 對外 API 入口：`session.BtClient` 與 `session.TorrentSession`。

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
