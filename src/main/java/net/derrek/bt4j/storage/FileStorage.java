package net.derrek.bt4j.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import net.derrek.bt4j.metainfo.FileEntry;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;
import net.derrek.bt4j.piece.PieceSelection;

/**
 * 以 FileChannel 實作的檔案儲存。
 *
 * 暫存策略：piece 未驗證前的 block 累積在記憶體緩衝（上限由
 * {@code RarestFirstPicker.MAX_ACTIVE_PIECES} × pieceLength 決定），
 * 驗證通過才寫入磁碟——邊界 piece 中屬於未勾選檔案的位元組區段直接丟棄，
 * 未勾選的檔案完全不建立。驗證失敗的 piece 整個丟棄重來。
 *
 * 所有方法同步，可由多條 peer 執行緒併發呼叫。
 */
public final class FileStorage implements Storage {

    private static final System.Logger LOG = System.getLogger(FileStorage.class.getName());

    private final Metainfo metainfo;
    private final PieceSelection selection;
    private final Path root;
    private final Map<Integer, byte[]> pieceBuffers = new HashMap<>();
    private final Map<Integer, FileChannel> channels = new HashMap<>();
    private final Bitfield completed;
    private boolean closed;

    /**
     * @param saveTo 下載根目錄（檔案路徑 = saveTo/FileEntry.path…；多檔 torrent 的第一層即 torrent name）
     */
    public FileStorage(Metainfo metainfo, PieceSelection selection, Path saveTo) {
        this(metainfo, selection, saveTo, new Bitfield(metainfo.pieceCount()));
    }

    /**
     * resume 用：以既有的已完成 piece 集合建立（其資料視為已在磁碟上，之前驗證通過才會被標記）。
     */
    public FileStorage(Metainfo metainfo, PieceSelection selection, Path saveTo, Bitfield alreadyCompleted) {
        this.metainfo = metainfo;
        this.selection = selection;
        this.root = saveTo;
        this.completed = alreadyCompleted.copy();
    }

    @Override
    public synchronized void write(int pieceIndex, int offset, byte[] data) {
        if (closed || completed.get(pieceIndex)) {
            return;
        }
        byte[] buffer = pieceBuffers.computeIfAbsent(pieceIndex, p -> new byte[metainfo.pieceLengthAt(p)]);
        System.arraycopy(data, 0, buffer, offset, data.length);
    }

    @Override
    public synchronized byte[] read(int pieceIndex, int offset, int length) throws IOException {
        if (!completed.get(pieceIndex)) {
            throw new IllegalStateException("piece " + pieceIndex + " 尚未完成");
        }
        byte[] out = new byte[length];
        long pieceStart = (long) pieceIndex * metainfo.pieceLength() + offset;
        int filled = readFromFiles(pieceStart, out);
        if (filled != length) {
            throw new IllegalStateException("piece " + pieceIndex + " 的部分位元組不在磁碟上（未勾選檔案的區段）");
        }
        return out;
    }

    @Override
    public synchronized boolean verifyPiece(int pieceIndex) throws IOException {
        if (completed.get(pieceIndex)) {
            return true;
        }
        byte[] buffer = pieceBuffers.get(pieceIndex);
        if (buffer == null) {
            throw new IllegalStateException("piece " + pieceIndex + " 沒有待驗證的資料");
        }
        pieceBuffers.remove(pieceIndex);
        if (!java.util.Arrays.equals(sha1(buffer), metainfo.pieceHash(pieceIndex))) {
            LOG.log(System.Logger.Level.DEBUG, () -> "piece " + pieceIndex + " failed SHA-1 verification, discarded for re-download");
            return false;
        }
        writeVerifiedPiece(pieceIndex, buffer);
        completed.set(pieceIndex);
        LOG.log(System.Logger.Level.TRACE, () -> "piece " + pieceIndex + " verified and written to disk");
        return true;
    }

    /** 把驗證過的 piece 中「屬於勾選檔案」的區段寫入對應檔案位置。 */
    private void writeVerifiedPiece(int pieceIndex, byte[] buffer) throws IOException {
        long pieceStart = (long) pieceIndex * metainfo.pieceLength();
        long pieceEnd = pieceStart + buffer.length;
        for (FileEntry file : metainfo.files()) {
            if (!selection.isFileWanted(file.index()) || file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            long overlapStart = Math.max(pieceStart, fileStart);
            long overlapEnd = Math.min(pieceEnd, fileEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            FileChannel channel = channel(file);
            ByteBuffer slice = ByteBuffer.wrap(buffer, (int) (overlapStart - pieceStart), (int) (overlapEnd - overlapStart));
            long position = overlapStart - fileStart;
            while (slice.hasRemaining()) {
                position += channel.write(slice, position);
            }
        }
    }

    private int readFromFiles(long globalStart, byte[] out) throws IOException {
        return readFromFiles(globalStart, out, true);
    }

    /**
     * 從磁碟讀取全域位移 globalStart 起的 out.length bytes（只跨勾選檔案）。回傳實際填入數。
     *
     * @param createIfMissing false 時（recheck 掃描）不建立不存在的檔案，缺檔即視為讀不到
     */
    private int readFromFiles(long globalStart, byte[] out, boolean createIfMissing) throws IOException {
        int filled = 0;
        long globalEnd = globalStart + out.length;
        for (FileEntry file : metainfo.files()) {
            if (!selection.isFileWanted(file.index()) || file.length() == 0) {
                continue;
            }
            long fileStart = file.offset();
            long fileEnd = fileStart + file.length();
            long overlapStart = Math.max(globalStart, fileStart);
            long overlapEnd = Math.min(globalEnd, fileEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            FileChannel channel = channel(file, createIfMissing);
            if (channel == null) {
                return filled; // 檔案不存在（recheck 掃描時）→ 此 piece 不完整
            }
            ByteBuffer slice = ByteBuffer.wrap(out, (int) (overlapStart - globalStart), (int) (overlapEnd - overlapStart));
            long position = overlapStart - fileStart;
            while (slice.hasRemaining()) {
                int n = channel.read(slice, position);
                if (n < 0) {
                    return filled; // 檔案比預期短（不完整）
                }
                position += n;
            }
            filled += (int) (overlapEnd - overlapStart);
        }
        return filled;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-1").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("JDK 必定內建 SHA-1", e);
        }
    }

    private FileChannel channel(FileEntry file) throws IOException {
        return channel(file, true);
    }

    /** @param createIfMissing false 且檔案尚未開啟／不存在時回傳 null（不建立檔案） */
    private FileChannel channel(FileEntry file, boolean createIfMissing) throws IOException {
        FileChannel existing = channels.get(file.index());
        if (existing != null) {
            return existing;
        }
        Path path = root;
        for (String component : file.path()) {
            path = path.resolve(component);
        }
        if (!createIfMissing && !Files.exists(path)) {
            return null;
        }
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        channels.put(file.index(), channel);
        return channel;
    }

    @Override
    public synchronized Bitfield completedPieces() {
        return completed.copy();
    }

    @Override
    public synchronized Bitfield recheck() throws IOException {
        pieceBuffers.clear();
        for (int p = 0; p < metainfo.pieceCount(); p++) {
            if (completed.get(p)) {
                continue;
            }
            // 只有整個 piece 都落在勾選檔案內才能從磁碟驗證；邊界 piece 一律重新下載
            if (selection.wantedBytesInPiece(p) != metainfo.pieceLengthAt(p)) {
                continue;
            }
            byte[] buffer = new byte[metainfo.pieceLengthAt(p)];
            if (readFromFiles((long) p * metainfo.pieceLength(), buffer, false) != buffer.length) {
                continue; // 檔案不存在或不完整
            }
            if (java.util.Arrays.equals(sha1(buffer), metainfo.pieceHash(p))) {
                completed.set(p);
            }
        }
        return completed.copy();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        pieceBuffers.clear();
        IOException first = null;
        for (FileChannel channel : channels.values()) {
            try {
                channel.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        channels.clear();
        if (first != null) {
            throw first;
        }
    }
}
