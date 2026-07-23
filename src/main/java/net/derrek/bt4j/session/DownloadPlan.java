package net.derrek.bt4j.session;

import java.nio.file.Path;
import java.util.Set;

/**
 * 下載計畫：UI 勾選結果的封裝，傳給 {@link TorrentSession#start(DownloadPlan)}。
 *
 * @param saveTo              下載根目錄
 * @param selectedFileIndices 勾選的檔案 index（FileEntry.index）；空集合＝下載全部
 * @param seedAfterComplete   完成後是否做種（預設 true；使用者仍可隨時 stopSeeding）
 */
public record DownloadPlan(Path saveTo,
                           Set<Integer> selectedFileIndices,
                           boolean seedAfterComplete) {

    /** 下載全部檔案、完成後做種。 */
    public static DownloadPlan allFiles(Path saveTo) {
        return new DownloadPlan(saveTo, Set.of(), true);
    }

    public static DownloadPlan files(Path saveTo, Set<Integer> selectedFileIndices) {
        return new DownloadPlan(saveTo, Set.copyOf(selectedFileIndices), true);
    }
}
