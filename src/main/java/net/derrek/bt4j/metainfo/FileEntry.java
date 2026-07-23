package net.derrek.bt4j.metainfo;

import java.util.List;

/**
 * A single file within a torrent.
 *
 * @param index  index within the torrent (0-based); used by UI file selection and DownloadPlan
 * @param path   relative path (single-file torrent: [name]; multi-file: [name, dir..., file])
 * @param length file length (bytes)
 * @param offset the file's start offset within the torrent's contiguous byte space, used for piece ↔ file mapping
 */
public record FileEntry(int index, List<String> path, long length, long offset) {

    /** Relative path for display, joined with '/'. */
    public String displayPath() {
        return String.join("/", path);
    }
}
