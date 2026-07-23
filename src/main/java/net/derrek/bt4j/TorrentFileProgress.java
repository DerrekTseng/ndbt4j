package net.derrek.bt4j;

/**
 * Download progress of a single file (an element of {@link TorrentDownloadTask#fileProgress()}).
 * All getters, safely pollable.
 *
 * Note: before a boundary piece shared across files is complete, the progress of both adjacent files may lag slightly; this is normal.
 */
public interface TorrentFileProgress {

    /** The corresponding file. */
    TorrentContentFile file();

    /** Bytes of this file downloaded and verified. */
    long downloadedBytes();

    /** Total size of this file (= file().size()). */
    long totalBytes();

    /** 0.0 ~ 1.0. */
    double progress();

    /** Whether this file is fully complete. */
    boolean completed();
}
