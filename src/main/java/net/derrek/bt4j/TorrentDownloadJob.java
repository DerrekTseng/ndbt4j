package net.derrek.bt4j;

import java.nio.file.Path;
import java.util.List;

/**
 * Persistent description of a download task, corresponding to the {@code .bt4j} file in the target directory.
 *
 * Produced by {@link Bt#createDownloadJob} (new task, creates .bt4j on the spot) or
 * {@link Bt#restoreDownloadJob} (reads an existing .bt4j to resume).
 * Actually starts running only when passed to {@link Bt#download}.
 */
public interface TorrentDownloadJob {

    /** The torrent content it belongs to. */
    TorrentContent content();

    /** Download target directory (where the .bt4j file lives). */
    Path targetDirectory();

    /** Files selected for download (restored from .bt4j when restoring). */
    List<TorrentContentFile> selectedFiles();

    /** Whether to move into the seeding list after download completes. */
    boolean seedAfterComplete();

    /** Bytes completed and verified (0 for a new task; existing progress when restoring). */
    long completedBytes();
}
