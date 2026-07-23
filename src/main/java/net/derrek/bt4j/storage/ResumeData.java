package net.derrek.bt4j.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.piece.Bitfield;

/**
 * resume 資料：伺服器重啟後恢復 session 所需的最小狀態。
 * 以 bencoding 序列化存檔（沿用自家編碼器，不引入其他格式）。
 *
 * @param infoHash            所屬 torrent
 * @param completedPieces     已驗證完成的 piece
 * @param selectedFileIndices 使用者勾選的檔案
 * @param saveTo              下載目的地
 * @param uploaded            累計上傳量（統計沿續）
 * @param seedingStopped      使用者是否已手動關閉上傳
 */
public record ResumeData(InfoHash infoHash,
                         Bitfield completedPieces,
                         Set<Integer> selectedFileIndices,
                         Path saveTo,
                         long uploaded,
                         boolean seedingStopped) {

    public void save(Path file) throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }

    public static ResumeData load(Path file) throws IOException {
        throw new UnsupportedOperationException("尚未實作");
    }
}
