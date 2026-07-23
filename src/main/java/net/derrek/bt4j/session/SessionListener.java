package net.derrek.bt4j.session;

import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;

/**
 * Session event callbacks (push model; use {@link TorrentSession#stats()} for polling).
 * All methods have empty default implementations, so implementors override only the events they need.
 * Callbacks run on the package's internal threads and must not block for long.
 */
public interface SessionListener {

    default void onStateChanged(TorrentSession session, SessionState oldState, SessionState newState) {
    }

    /** A magnet link has fetched its metadata (the UI can now display the file list). */
    default void onMetadataReady(TorrentSession session, Metainfo metainfo) {
    }

    /** All pieces of a single file are complete. */
    default void onFileCompleted(TorrentSession session, FileEntry file) {
    }

    /** All selected files have finished downloading (transitions to SEEDING or STOPPED). */
    default void onDownloadCompleted(TorrentSession session) {
    }

    default void onError(TorrentSession session, Throwable error) {
    }
}
