package net.derrek.bt4j;

import java.util.List;
import net.derrek.bt4j.metainfo.Metainfo;

/** Implementation of {@link TorrentContent}, wrapping a {@link Metainfo} (pure data). */
final class TorrentContentImpl implements TorrentContent {

    final Metainfo metainfo;
    private final List<TorrentContentFile> files;

    TorrentContentImpl(Metainfo metainfo) {
        this.metainfo = metainfo;
        this.files = metainfo.files().stream()
                .map(entry -> (TorrentContentFile) new TorrentContentFileImpl(this, entry))
                .toList();
    }

    @Override
    public String infoHashHex() {
        return metainfo.infoHash().hex();
    }

    @Override
    public String name() {
        return metainfo.name();
    }

    @Override
    public List<TorrentContentFile> getFileList() {
        return files;
    }

    @Override
    public long totalSize() {
        return metainfo.totalLength();
    }

    @Override
    public String toString() {
        return "TorrentContent[" + name() + ", " + files.size() + " files]";
    }
}
