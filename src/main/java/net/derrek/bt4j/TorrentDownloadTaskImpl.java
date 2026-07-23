package net.derrek.bt4j;

import java.nio.file.Path;
import java.util.List;
import net.derrek.bt4j.session.SessionState;
import net.derrek.bt4j.session.TorrentSession;

/** Implementation of {@link TorrentDownloadTask}, wrapping a running {@link TorrentSession}. All getters. */
final class TorrentDownloadTaskImpl implements TorrentDownloadTask {

    final TorrentSession session;
    final TorrentContentImpl content;
    final Path bt4jPath;
    final boolean seedAfter;

    TorrentDownloadTaskImpl(TorrentSession session, TorrentContentImpl content, Path bt4jPath, boolean seedAfter) {
        this.session = session;
        this.content = content;
        this.bt4jPath = bt4jPath;
        this.seedAfter = seedAfter;
    }

    @Override
    public String infoHashHex() {
        return content.infoHashHex();
    }

    @Override
    public String name() {
        return content.name();
    }

    @Override
    public Path targetDirectory() {
        return bt4jPath.getParent();
    }

    @Override
    public TaskState state() {
        return switch (session.state()) {
            case DOWNLOADING, METADATA_READY, FETCHING_METADATA -> TaskState.DOWNLOADING;
            case SEEDING -> TaskState.SEEDING;
            case STOPPED -> TaskState.STOPPED;
            case ERROR -> TaskState.ERROR;
        };
    }

    @Override
    public long totalBytes() {
        return session.stats().wantedBytes();
    }

    @Override
    public long downloadedBytes() {
        return session.stats().downloadedBytes();
    }

    @Override
    public long uploadedBytes() {
        return session.stats().uploadedBytes();
    }

    @Override
    public double progress() {
        return session.stats().progress();
    }

    @Override
    public long downloadRate() {
        return session.stats().downloadRate();
    }

    @Override
    public long uploadRate() {
        return session.stats().uploadRate();
    }

    @Override
    public int connectedPeers() {
        return session.stats().connectedPeers();
    }

    @Override
    public List<TorrentFileProgress> fileProgress() {
        List<TorrentContentFile> files = content.getFileList();
        return session.fileProgress().stream()
                .map(fp -> (TorrentFileProgress) new TorrentFileProgressImpl(
                        files.get(fp.fileIndex()), fp.downloadedBytes(), fp.totalBytes()))
                .toList();
    }

    SessionState sessionState() {
        return session.state();
    }
}
