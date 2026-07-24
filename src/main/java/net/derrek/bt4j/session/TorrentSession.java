package net.derrek.bt4j.session;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.storage.ResumeData;

/**
 * The lifecycle of a single torrent. Created by {@link BtClient#addMagnet} / {@link BtClient#addTorrent}.
 * See {@link SessionState} for state transitions. All methods are thread-safe.
 */
public interface TorrentSession extends AutoCloseable {

    InfoHash infoHash();

    SessionState state();

    /** The metadata. Empty for a magnet link during the FETCHING_METADATA stage. */
    Optional<Metainfo> metadata();

    /**
     * Blocks until metadata is ready (intended for use with virtual threads; a .torrent source returns immediately).
     *
     * @throws TimeoutException if metadata is still not obtained after the timeout (e.g. a dead torrent)
     */
    Metainfo awaitMetadata(Duration timeout) throws TimeoutException, InterruptedException;

    /**
     * Starts downloading according to the plan (METADATA_READY → DOWNLOADING).
     * <p>
     * Calling again while DOWNLOADING or SEEDING re-selects which files to download: newly selected files begin
     * downloading (a completed, seeding torrent flips back to DOWNLOADING), deselected files stop and their
     * partial data is left on disk. The download directory cannot be changed this way — pass the same
     * {@code saveTo} or an {@link IllegalArgumentException} is thrown.
     */
    void start(DownloadPlan plan);

    /**
     * Manually stops uploading: sends stopped to the tracker, stops accepting incoming connections, and disconnects (→ STOPPED).
     * Called in the DOWNLOADING state, it also aborts the download.
     */
    void stopSeeding();

    /** A snapshot of the current statistics (pollable in any state). */
    TorrentStats stats();

    /** Per-file download progress (only the selected files are listed). An empty list when metadata is not yet ready. */
    java.util.List<FileProgress> fileProgress();

    /** Download progress of a single file. */
    record FileProgress(int fileIndex, java.util.List<String> path, long downloadedBytes, long totalBytes) {
    }

    void addListener(SessionListener listener);

    void removeListener(SessionListener listener);

    /** Exports the current progress as resume data (the caller is responsible for persisting it and restoring it into BtClient after a restart). */
    ResumeData resumeData();

    /** Stops and releases resources (implies stopSeeding's tracker stopped announce). Downloaded files are kept. */
    @Override
    void close();
}
