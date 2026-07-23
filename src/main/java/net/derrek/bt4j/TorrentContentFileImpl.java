package net.derrek.bt4j;

import net.derrek.bt4j.metainfo.FileEntry;

/** {@link TorrentContentFile} 的實作，包裝 {@link FileEntry} 並記住所屬 content。 */
final class TorrentContentFileImpl implements TorrentContentFile {

    private final TorrentContentImpl content;
    final FileEntry entry;

    TorrentContentFileImpl(TorrentContentImpl content, FileEntry entry) {
        this.content = content;
        this.entry = entry;
    }

    @Override
    public int index() {
        return entry.index();
    }

    @Override
    public String path() {
        return entry.displayPath();
    }

    @Override
    public long size() {
        return entry.length();
    }

    @Override
    public TorrentContent content() {
        return content;
    }

    @Override
    public String toString() {
        return "TorrentContentFile[" + path() + ", " + size() + " bytes]";
    }
}
