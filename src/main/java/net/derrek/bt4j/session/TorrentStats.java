package net.derrek.bt4j.session;

/**
 * A snapshot of session statistics (immutable and safe to poll — for periodic pulls by a scheduler/UI).
 *
 * @param downloadedBytes bytes downloaded and verified (counting only the selected range)
 * @param uploadedBytes   cumulative uploaded bytes
 * @param wantedBytes     total bytes of the selected range (the progress denominator; 0 when metadata is not yet ready)
 * @param progress        0.0 ~ 1.0
 * @param connectedPeers  the number of currently connected peers
 * @param downloadRate    recent download rate (bytes/s)
 * @param uploadRate      recent upload rate (bytes/s)
 */
public record TorrentStats(long downloadedBytes,
                           long uploadedBytes,
                           long wantedBytes,
                           double progress,
                           int connectedPeers,
                           long downloadRate,
                           long uploadRate) {
}
