package net.derrek.bt4j.session;

/**
 * TorrentSession state machine.
 * <pre>
 * addMagnet ─→ FETCHING_METADATA ─→ METADATA_READY ─ start() ─→ DOWNLOADING ─→ SEEDING
 * addTorrent ─────────────────────→       │                        │              │
 *                                          └── close() ──→ STOPPED ←─ stopSeeding()┘
 * An unrecoverable error in any state ─→ ERROR
 * </pre>
 */
public enum SessionState {
    /** Magnet link: fetching metadata from the swarm. */
    FETCHING_METADATA,
    /** Metadata is ready, waiting for the caller to invoke start() (the UI file-selection stage). */
    METADATA_READY,
    DOWNLOADING,
    /** Download complete, continuing to upload. */
    SEEDING,
    /** Stopped (via stopSeeding or close). */
    STOPPED,
    ERROR
}
