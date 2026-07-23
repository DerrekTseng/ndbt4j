# TODO

> 依 [DESIGN-DRAFT.md](DESIGN-DRAFT.md) 里程碑展開。骨架接口已建立於 `net.derrek.bt4j`，
> 「實作」指把 `UnsupportedOperationException("尚未實作")` 換成真正邏輯 + 單元測試。

## 前置

- [x] 下載 BEP 規格至 `doc/beps/`（2026-07-23）
- [x] pom.xml 設定 Java 25、零依賴
- [x] 設計草稿（DESIGN-DRAFT.md）
- [x] 全部 class/interface 骨架（`net.derrek.bt4j`，可編譯）
- [ ] 決定測試框架：JUnit（test scope 依賴）或純 main 方法測試 ← 需使用者確認

## M0 — bencoding

- [ ] `Bencode.decode`（含 offset 版本、raw bytes 區段回報）
- [ ] `Bencode.encode`（canonical：dict key 排序）
- [ ] `BValue.BDictionary.get(String)`
- [ ] 單元測試：torture cases（巢狀、空值、非 UTF-8 字串、格式錯誤拒絕）

## M1 — metainfo

- [ ] `InfoHash`：hex/fromHex/ofInfoDict（SHA-1 用 `MessageDigest`）
- [ ] `Metainfo.parse`：單檔/多檔、announce-list、private flag、piece hashes
- [ ] `Metainfo.fromInfoDict` / `toTorrentBytes` / `saveTorrentFile`
- [ ] `MagnetUri.parse`：btih hex、dn、tr、x.pe
- [ ] 測試：對真實 .torrent 檔驗證 info-hash 與已知值一致

## M2 — HTTP tracker

- [ ] `PeerId.generate`（"-ND1000-" + 隨機）
- [ ] `PeerAddress.fromCompact`
- [ ] `HttpTracker.announce`（HttpClient、URL encoding 的 info_hash 陷阱、compact=1）
- [ ] 整合測試：對公開 tracker 取得 peer 清單

## M3 — peer wire：端到端下載單檔 torrent

- [ ] `Handshake` encode/decode、reserved bits
- [ ] `PeerMessage` 編解碼（framing：length-prefix）
- [ ] `PeerConnection`：讀/寫 virtual thread、狀態旗標、keep-alive
- [ ] `Bitfield`
- [ ] `RarestFirstPicker`：pick/onBlockReceived/endgame
- [ ] `FileStorage`（先做全選情境）+ `verifyPiece`
- [ ] session 組裝：choke 處理、pipeline 請求、piece 完成 → Have 廣播
- [ ] 驗收：實際下載一個小型合法 torrent（例如 Linux ISO）且 hash 全過

## M4 — 檔案選擇

- [ ] `PieceSelection.of`：檔案 → piece 需求集合（邊界 piece）
- [ ] `FileStorage`：未勾選檔案不落地、邊界 piece 記憶體驗證
- [ ] `TorrentSession.start` 重複呼叫 = 變更計畫
- [ ] 驗收：多檔 torrent 只勾選部分檔案，磁碟上只出現勾選的檔案

## M5 — UDP tracker + 多 tracker

- [ ] `UdpTracker`：connect/announce、connection id 快取、退避重送
- [ ] `TrackerManager`：BEP 12 tiers、interval 排程、STARTED/COMPLETED/STOPPED

## M6 — 擴充協定 + 磁力連結

- [ ] `ExtensionRegistry`：extension handshake、id 對映、分派
- [ ] `MetadataExchange`：分塊請求、SHA-1 驗證、拒絕偽造、`supply` 回應他人
- [ ] `BtClient.addMagnet` 全流程：FETCHING_METADATA → METADATA_READY
- [ ] 驗收：有 tracker 的磁力連結能列出檔案清單、匯出 .torrent

## M7 — DHT

- [ ] `KrpcMessage` 編解碼
- [ ] `RoutingTable`：bucket、汰換規則
- [ ] `DhtClient`：bootstrap、迭代 get_peers、announce_peer、token 處理
- [ ] `PeerMessage.Port` 整合（從 peer 學到 DHT 節點）
- [ ] 驗收：無 tracker 的磁力連結純靠 DHT 完成下載

## M8 — 做種 / 關閉上傳 / resume

- [ ] 回應他人 request（上傳路徑）、完成後 SEEDING 狀態
- [ ] `stopSeeding`：tracker stopped、停收連入、斷線
- [ ] `ResumeData` save/load（bencoding 格式）、`BtClient.restore`、`Storage.recheck`
- [ ] 驗收：重啟程序後續傳，不重下已完成 piece

## M9 — 擴充功能

- [ ] `PeerExchange`（BEP 11，≥60s 週期）
- [ ] Fast Extension 訊息處理（BEP 6）
- [ ] private torrent（BEP 27）：停用 DHT/PEX
- [ ] 連入連線 listener（`PeerConnection.incoming` 接線到對應 session）

## 待決（實作前要拍板）

- [ ] 測試框架選擇（見「前置」）
- [ ] 全域限速要不要進初版
- [ ] block 暫存策略：piece 未驗證前放記憶體 or 直接寫檔（影響 FileStorage 設計）
- [ ] logging：`System.Logger`（JDK 內建，零依賴）？
