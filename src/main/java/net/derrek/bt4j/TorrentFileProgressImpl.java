package net.derrek.bt4j;

/** Implementation of {@link TorrentFileProgress}. */
record TorrentFileProgressImpl(TorrentContentFile file, long downloadedBytes, long totalBytes)
        implements TorrentFileProgress {

    @Override
    public double progress() {
        return totalBytes == 0 ? 1.0 : (double) downloadedBytes / totalBytes;
    }

    @Override
    public boolean completed() {
        return downloadedBytes >= totalBytes;
    }
}
