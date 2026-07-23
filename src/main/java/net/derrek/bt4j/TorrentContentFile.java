package net.derrek.bt4j;

/**
 * A file within a torrent (for browsing and selection; no download progress -- progress is in {@link TorrentFileProgress}).
 * Obtained from {@link TorrentContent#getFileList()} and, after filtering, passed to
 * {@link Bt#createDownloadJob}. Only files produced by the same {@link TorrentContent} can be used together to create a job.
 */
public interface TorrentContentFile {

    /** Index within the torrent (0-based), used to specify which files to download. */
    int index();

    /** Relative path joined with '/' (multi-file torrents include the top-level directory name). */
    String path();

    /** File size (bytes). */
    long size();

    /** The torrent content it belongs to (used to verify the same torrent). */
    TorrentContent content();
}
