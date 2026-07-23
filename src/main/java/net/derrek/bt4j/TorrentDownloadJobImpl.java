package net.derrek.bt4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.storage.ResumeData;

/**
 * {@link TorrentDownloadJob} 的實作。
 * 新任務（{@link Bt#createDownloadJob}）fromRestore=false；續傳（{@link Bt#restoreDownloadJobs}）fromRestore=true。
 */
final class TorrentDownloadJobImpl implements TorrentDownloadJob {

    final TorrentContentImpl content;
    final Set<Integer> selectedIndices; // 空集合＝全選
    final Path targetDir;
    final boolean seedAfter;
    final boolean fromRestore;
    final ResumeData resumeData; // fromRestore 時非 null

    TorrentDownloadJobImpl(TorrentContentImpl content, Set<Integer> selectedIndices, Path targetDir,
                           boolean seedAfter, boolean fromRestore, ResumeData resumeData) {
        this.content = content;
        this.selectedIndices = Set.copyOf(selectedIndices);
        this.targetDir = targetDir;
        this.seedAfter = seedAfter;
        this.fromRestore = fromRestore;
        this.resumeData = resumeData;
    }

    @Override
    public TorrentContent content() {
        return content;
    }

    @Override
    public Path targetDirectory() {
        return targetDir;
    }

    @Override
    public List<TorrentContentFile> selectedFiles() {
        return content.getFileList().stream()
                .filter(f -> selectedIndices.isEmpty() || selectedIndices.contains(f.index()))
                .toList();
    }

    @Override
    public boolean seedAfterComplete() {
        return seedAfter;
    }

    @Override
    public long completedBytes() {
        if (!fromRestore || resumeData == null) {
            return 0;
        }
        var completed = resumeData.completedPieces();
        var meta = content.metainfo;
        long pieceLength = meta.pieceLength();
        long total = 0;
        for (FileEntry file : meta.files()) {
            if (!selectedIndices.isEmpty() && !selectedIndices.contains(file.index())) {
                continue;
            }
            if (file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            int firstPiece = (int) (fileStart / pieceLength);
            int lastPiece = (int) ((fileEnd - 1) / pieceLength);
            for (int p = firstPiece; p <= lastPiece; p++) {
                if (completed.get(p)) {
                    long ps = (long) p * pieceLength;
                    long pe = ps + meta.pieceLengthAt(p);
                    total += Math.min(fileEnd, pe) - Math.max(fileStart, ps);
                }
            }
        }
        return total;
    }
}
