# TODO

> 依 [DESIGN-DRAFT.md](DESIGN-DRAFT.md) 里程碑展開。骨架接口已建立於 `net.derrek.bt4j`，
> 「實作」指把 `UnsupportedOperationException("尚未實作")` 換成真正邏輯 + 單元測試。

## 前置

- [x] 下載 BEP 規格至 `doc/beps/`（2026-07-23）
- [x] pom.xml 設定 Java 25、零依賴
- [x] 設計草稿（DESIGN-DRAFT.md）
- [x] 全部 class/interface 骨架（`net.derrek.bt4j`，可編譯）
- [x] 決定測試框架：JUnit 5（test scope，使用者已確認 2026-07-23）
- [x] artifactId 改為 `bt4j`

## M0 — bencoding ✅

- [x] `Bencode.decode`（含 offset 版本、raw bytes 區段回報）
- [x] `Bencode.encode`（canonical：dict key 無號位元組排序）
- [x] `BValue.BDictionary.get(String)`、`BString` equals/hashCode（byte[] 內容比較）
- [x] 單元測試：torture cases（22 tests——巢狀、空值、非 UTF-8 字串、前導零、溢位、深度炸彈、格式錯誤拒絕）

## M1 — metainfo ✅

- [x] `InfoHash`：hex/fromHex/fromBase32/ofInfoDict（SHA-1 用 `MessageDigest`）
- [x] `Metainfo.parse`：單檔/多檔、announce-list、private flag、piece hashes、路徑穿越防護
- [x] `Metainfo.fromInfoDict` / `toTorrentBytes` / `saveTorrentFile`（info 原始位元組直接嵌入，hash 不變）
- [x] `MagnetUri.parse`：btih 支援 hex 與 Base32（測試連結見 doc/TEST-MAGNETS.md）、dn、tr、x.pe
- [x] 測試：22 個（含非 canonical info dict 的 raw-bytes hash 驗證、使用者提供的 magnet 案例）
- [ ] （延後至 M2/M3 整合測試）對真實 .torrent 檔驗證 info-hash

## M2 — HTTP tracker ✅

- [x] `PeerId.generate`（"-ND1000-" + 12 隨機可列印字元）
- [x] `PeerAddress.fromCompact` / `fromCompact6`（BEP 23 / BEP 7 peers6，port 0 跳過）
- [x] `HttpTracker.announce`（HttpClient、info_hash 原始位元組逐一 percent-encode、compact=1、dict 形式 peers 也支援、failure reason → TrackerException）
- [x] `Tracker.of`（scheme 分派；udp 留給 M5）
- [x] 整合測試：對公開 tracker（opentrackr.org）announce 成功（`mvn test -Dbt4j.integration=true` 執行，預設跳過）

## M3 — peer wire：端到端下載單檔 torrent ✅

- [x] `Handshake` encode/decode、reserved bits（DHT/擴充/Fast）
- [x] `PeerMessage` 編解碼（length-prefix framing、未知 id 寬容略過、長度上限防護、含 Fast Extension 訊息）
- [x] `PeerConnection`：讀/寫 virtual thread、四態旗標、110 秒 keep-alive、peerBitfield 追蹤
- [x] `Bitfield`（MSB-first、padding 驗證）
- [x] `RarestFirstPicker`：rarest-first、進行中 piece 上限 32、endgame 重複派發、驗證失敗重排
- [x] `FileStorage`：記憶體 piece 緩衝 → 驗證通過才落地（含選擇性下載的邊界丟棄）、recheck
- [x] session 組裝：`DefaultTorrentSession`（announce 迴圈、connector、pipeline 16、Have 廣播、狀態機、事件）+ `BtClient`
- [x] 驗收：端到端測試（本地假 tracker + 測試 seeder，7 pieces 下載完成、位元組一致）
- [ ] （延後）對真實公網 torrent 的下載驗證——建議 M5 UDP tracker 完成後用 doc/TEST-MAGNETS.md 或 Linux 發行版 torrent 實測

## M4 — 檔案選擇（M3 已完成大半）

- [x] `PieceSelection.of`：檔案 → piece 需求集合（邊界 piece、wantedBytesInPiece）
- [x] `FileStorage`：未勾選檔案不落地、邊界 piece 記憶體驗證（測試：只勾中間檔案，磁碟只出現該檔且內容正確）
- [ ] `TorrentSession.start` 重複呼叫 = 變更計畫
- [ ] 驗收：端到端測試以 DownloadPlan 勾選部分檔案

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
  - 已決定：內建 5 個預設 bootstrap 節點（`DhtClient.DEFAULT_BOOTSTRAP_NODES`），`BtClient.Builder.dhtBootstrapNodes()` 可覆寫；路由表隨 resume 持久化，重啟用既有節點暖機
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
- [x] block 暫存策略：已決定「未驗證前放記憶體，驗證通過才落地」（上限 = MAX_ACTIVE_PIECES 32 × pieceLength；邊界丟棄自然成立）
- [ ] logging：`System.Logger`（JDK 內建，零依賴）？
