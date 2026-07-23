package net.derrek.bt4j.session;

/**
 * session 統計快照（不可變，可安全輪詢——排程器/UI 定期拉取用）。
 *
 * @param downloadedBytes 已下載且驗證的 bytes（僅計勾選範圍）
 * @param uploadedBytes   累計上傳 bytes
 * @param wantedBytes     勾選範圍總 bytes（進度分母；metadata 未就緒時為 0）
 * @param progress        0.0 ~ 1.0
 * @param connectedPeers  目前連線的 peer 數
 * @param downloadRate    近期下載速率（bytes/s）
 * @param uploadRate      近期上傳速率（bytes/s）
 */
public record TorrentStats(long downloadedBytes,
                           long uploadedBytes,
                           long wantedBytes,
                           double progress,
                           int connectedPeers,
                           long downloadRate,
                           long uploadRate) {
}
