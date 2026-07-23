package net.derrek.bt4j;

import java.util.List;

/**
 * Content description of a torrent (name, file list, total size).
 * Source: parsed directly by {@link Bt#fromTorrent}, or after {@link Bt#fromMagnet} obtains metadata from the swarm.
 * Once obtained it is pure data and holds no network resources.
 */
public interface TorrentContent {

    /** The info-hash as 40-character lowercase hex. */
    String infoHashHex();

    /** Torrent name. */
    String name();

    /** All files, in torrent order. The UI can filter this to pick the files to download. */
    List<TorrentContentFile> getFileList();

    /** Total size of all files (bytes). */
    long totalSize();
}
