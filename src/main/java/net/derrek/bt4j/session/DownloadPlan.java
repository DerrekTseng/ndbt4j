package net.derrek.bt4j.session;

import java.nio.file.Path;
import java.util.Set;

/**
 * Download plan: a wrapper around the UI selection, passed to {@link TorrentSession#start(DownloadPlan)}.
 *
 * @param saveTo              the download root directory
 * @param selectedFileIndices the selected file indices (FileEntry.index); an empty set means download everything
 * @param seedAfterComplete   whether to seed after completion (default true; the user can still stopSeeding at any time)
 */
public record DownloadPlan(Path saveTo,
                           Set<Integer> selectedFileIndices,
                           boolean seedAfterComplete) {

    /** Download all files and seed after completion. */
    public static DownloadPlan allFiles(Path saveTo) {
        return new DownloadPlan(saveTo, Set.of(), true);
    }

    public static DownloadPlan files(Path saveTo, Set<Integer> selectedFileIndices) {
        return new DownloadPlan(saveTo, Set.copyOf(selectedFileIndices), true);
    }
}
