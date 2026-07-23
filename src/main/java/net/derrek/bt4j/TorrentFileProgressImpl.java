package net.derrek.bt4j;

/** {@link TorrentFileProgress} 的實作。 */
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
