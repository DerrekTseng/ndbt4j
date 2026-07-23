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

## M5 — UDP tracker + 多 tracker ✅

- [x] `UdpTracker`：connect/announce 二段式、connection id 快取 50s、逾時翻倍重送（5/10/20s）、error action 轉例外、交易 id 驗證
- [x] `TrackerManager`：BEP 12 tiers（建構時洗牌、成功者晉升 tier 最前）、interval 排程（上限 5 分鐘）、STARTED/COMPLETED/STOPPED 生命週期、全失敗 30s 重試
- [x] `DefaultTorrentSession` 改用 TrackerManager（移除自製 announce 迴圈）
- [x] 整合測試：對公開 UDP tracker（opentrackr:1337）announce 成功

## M6 — 擴充協定 + 磁力連結 ✅

- [x] `ExtensionRegistry`：extension handshake（m 字典、v、metadata_size）、雙向 id 對映、分派、id=0 停用語意
- [x] `MetadataExchange`：分塊請求、SHA-1 驗證、偽造整包丟棄可重試、metadata_size 上限 8MB、`supply` 回應他人 request
- [x] `BtClient.addMagnet` 全流程：FETCHING_METADATA →（tracker/x.pe 找 peer → BEP 10/9）→ METADATA_READY；metadata 階段連線於轉換時收掉、下載階段以正確 pieceCount 重連
- [x] `addListener` 補發 onMetadataReady（修正「metadata 秒到、listener 晚掛」的漏事件競態）
- [x] 驗收：磁力連結端到端測試——tr= 假 tracker 全流程（列檔案、匯出 .torrent hash 不變、續接下載完成）+ x.pe 直連無 tracker 取 metadata

## M7 — DHT ✅

- [x] `KrpcMessage` 編解碼（含 BEP 5 文件範例驗證）
- [x] `RoutingTable`：160 bucket、K=8、逾時汰換、偏好久經考驗節點
- [x] `DhtClient`：bootstrap（自身 id lookup、5 個預設節點可由 Builder 覆寫）、迭代 get_peers（α=3、16 輪）、
      announce_peer + token、server 端四種 RPC 回應、token 5 分鐘紀元輪替驗證、peer store 防灌爆
- [x] `PeerMessage.Port` 整合：握手宣告 DHT bit、互送 PORT、收到後 ping 入路由表
- [x] session DHT 迴圈：60 秒週期 findPeers、下載開始後 announce；private torrent（BEP 27）不啟用
- [x] 驗收（本地）：無 tracker 磁力連結靠三節點程序內 DHT 完成 metadata + 下載
- [x] 驗收（真實世界）：使用者的無 tracker 磁力連結（第 2 組）經公共 DHT 5.5 秒取得 metadata（72MB 單檔，名稱完整解出）
- [ ]（延後 M8）路由表隨 resume 持久化，重啟用既有節點暖機

## M8 — 做種 / 關閉上傳 / resume ✅

- [x] 連入連線 listener（BtClient 的 ServerSocket、依 info-hash 路由至 session；原訂 M9，提前以支撐真正做種）
- [x] `PeerConnection.incoming`（接手已握手 socket、回送我方 handshake）
- [x] 上傳路徑：Interested→Unchoke、Request→Piece（block 上限防護）、uploaded 統計；下載中也對 peer 互惠
- [x] 完成後 SEEDING（停止主動連出、tracker completed）；`stopSeeding` = STOPPED（tracker stopped、關連線、停止上傳）
- [x] `ResumeData` 自足化（內嵌 .torrent bytes）+ bencode save/load + `BtClient.restore`（跳過已完成 piece）
- [x] 全面 logging：`System.Logger`（零依賴，只用 WARNING/ERROR/DEBUG/TRACE，無 INFO；引用方可橋接 slf4j）
- [x] 驗收：兩個 bt4j client 一做種一下載（走真實 wire）、部分完成 resume 續傳只索取缺少 piece、stopped resume 保持 STOPPED

## M9 — 擴充功能

- [ ] `PeerExchange`（BEP 11，≥60s 週期）
- [ ] Fast Extension 訊息處理（BEP 6）
- [ ] private torrent（BEP 27）：停用 DHT/PEX（DHT 部分已於 M7 完成）
- [ ] 壞 peer 黑名單（連續送壞 piece）
- [ ] 全域限速（見待決）

## 待決（實作前要拍板）

- [ ] 全域限速（上傳/下載頻寬）要不要進初版
- [x] block 暫存策略：已決定「未驗證前放記憶體，驗證通過才落地」（上限 = MAX_ACTIVE_PIECES 32 × pieceLength；邊界丟棄自然成立）
- [x] logging：採 `System.Logger`（JDK 內建、零依賴）。等級只用 WARNING/ERROR/DEBUG/TRACE。引用方加 `slf4j-jdk-platform-logging` 即可導向 slf4j/logback，bt4j 本身維持零依賴
- [x] 路由表隨 resume 持久化：暫緩（DHT bootstrap 已足夠可用；未來可加）
