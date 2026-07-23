package net.derrek.bt4j.session;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.storage.ResumeData;

/**
 * 單一 torrent 的生命週期。由 {@link BtClient#addMagnet}／{@link BtClient#addTorrent} 建立。
 * 狀態遷移見 {@link SessionState}。所有方法執行緒安全。
 */
public interface TorrentSession extends AutoCloseable {

    InfoHash infoHash();

    SessionState state();

    /** metadata。磁力連結在 FETCHING_METADATA 階段為 empty。 */
    Optional<Metainfo> metadata();

    /**
     * 阻塞等待 metadata 就緒（配合 virtual thread 使用；.torrent 來源立即回傳）。
     *
     * @throws TimeoutException 逾時仍未取得（例如死種）
     */
    Metainfo awaitMetadata(Duration timeout) throws TimeoutException, InterruptedException;

    /**
     * 依計畫開始下載（METADATA_READY → DOWNLOADING）。
     * 已在下載中時重複呼叫視為變更計畫（重算 piece 需求集合）。
     */
    void start(DownloadPlan plan);

    /**
     * 手動關閉上傳：對 tracker 發 stopped、停收連入、斷開連線（→ STOPPED）。
     * 在 DOWNLOADING 狀態呼叫則同時中止下載。
     */
    void stopSeeding();

    /** 目前統計快照（任何狀態皆可輪詢）。 */
    TorrentStats stats();

    void addListener(SessionListener listener);

    void removeListener(SessionListener listener);

    /** 匯出目前進度為 resume 資料（上層負責持久化與重啟後回填 BtClient）。 */
    ResumeData resumeData();

    /** 停止並釋放資源（隱含 stopSeeding 的 tracker stopped announce）。已下載的檔案保留。 */
    @Override
    void close();
}
