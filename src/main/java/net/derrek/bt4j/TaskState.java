package net.derrek.bt4j;

/**
 * Public-facing state of a download task (facade layer; does not expose internal phases such as metadata fetching).
 */
public enum TaskState {
    /** Downloading. */
    DOWNLOADING,
    /** Download complete, continuing to upload (seeding). */
    SEEDING,
    /** Stopped (manual stop). */
    STOPPED,
    /** An unrecoverable error occurred. */
    ERROR
}
