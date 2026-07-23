package net.derrek.bt4j.tracker;

/** The event parameter of a tracker announce (BEP 3). */
public enum AnnounceEvent {
    /** Download started (first announce). */
    STARTED,
    /** Stopped (sent when uploading or the session is shut down). */
    STOPPED,
    /** Download completed. */
    COMPLETED,
    /** Periodic report (no event parameter). */
    NONE
}
