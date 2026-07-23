package net.derrek.bt4j;

import java.nio.file.Path;
import java.util.List;

/**
 * Handle to a running download/seeding task. **All getters, thread-safe, continuously pollable** (snapshot-style, no setters).
 * Lifecycle is controlled from {@link Bt} (stop / deleteJob).
 */
public interface TorrentDownloadTask {

    /** 40-character hex info-hash. */
    String infoHashHex();

    /** Torrent name. */
    String name();

    /** Download target directory. */
    Path targetDirectory();

    TaskState state();

    // ---- overall progress ----

    /** Total size of the selected set (bytes) -- the denominator of overall progress. */
    long totalBytes();

    /** Bytes downloaded and verified (selected set only). */
    long downloadedBytes();

    /** Cumulative uploaded bytes. */
    long uploadedBytes();

    /** Overall progress 0.0 ~ 1.0. */
    double progress();

    /** Recent download rate (bytes/s). */
    long downloadRate();

    /** Recent upload rate (bytes/s). */
    long uploadRate();

    /** Number of currently connected peers. */
    int connectedPeers();

    // ---- per-file progress ----

    /** Progress of each selected file. */
    List<TorrentFileProgress> fileProgress();
}
