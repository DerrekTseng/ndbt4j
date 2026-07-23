package net.derrek.bt4j.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.metainfo.Metainfo;
import net.derrek.bt4j.piece.Bitfield;

/**
 * resume 資料：伺服器重啟後恢復 session 所需的完整、自足狀態。
 * 內嵌 .torrent 位元組（磁力連結取得的 metadata 也已轉為 .torrent 形式），
 * 因此單一 resume 檔即可完整還原一個 session，不需另存 .torrent。
 * 以 bencoding 序列化（沿用自家編碼器，不引入其他格式）。
 *
 * @param torrentBytes        內嵌的 metainfo（standard .torrent bytes）
 * @param completedPieces     已驗證完成的 piece
 * @param selectedFileIndices 使用者勾選的檔案（空集合＝全選）
 * @param saveTo              下載目的地
 * @param uploaded            累計上傳量（統計沿續）
 * @param seedingStopped      使用者是否已手動關閉上傳
 * @param seedAfterComplete   下載完成後是否做種
 */
public record ResumeData(byte[] torrentBytes,
                         Bitfield completedPieces,
                         Set<Integer> selectedFileIndices,
                         Path saveTo,
                         long uploaded,
                         boolean seedingStopped,
                         boolean seedAfterComplete) {

    /** 解析內嵌的 metainfo。 */
    public Metainfo metainfo() {
        return Metainfo.parse(torrentBytes);
    }

    public InfoHash infoHash() {
        return metainfo().infoHash();
    }

    public void save(Path file) throws IOException {
        Files.write(file, encode());
    }

    public static ResumeData load(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /** 序列化為 bencoded 位元組。 */
    public byte[] encode() {
        List<BValue> selected = selectedFileIndices.stream()
                .sorted()
                .map(i -> (BValue) new BValue.BInteger(i))
                .toList();
        // TreeMap 讓 bencode 的 dict key 天然排序
        SequencedMap<BValue.BString, BValue> map = new TreeMap<>(
                (a, b) -> java.util.Arrays.compareUnsigned(a.bytes(), b.bytes()));
        map.put(BValue.BString.of("torrent"), new BValue.BString(torrentBytes));
        map.put(BValue.BString.of("pieces"), new BValue.BString(completedPieces.toBytes()));
        map.put(BValue.BString.of("selected"), new BValue.BList(selected));
        map.put(BValue.BString.of("saveTo"), BValue.BString.of(saveTo == null ? "" : saveTo.toString()));
        map.put(BValue.BString.of("uploaded"), new BValue.BInteger(uploaded));
        map.put(BValue.BString.of("stopped"), new BValue.BInteger(seedingStopped ? 1 : 0));
        map.put(BValue.BString.of("seedAfter"), new BValue.BInteger(seedAfterComplete ? 1 : 0));
        return Bencode.encode(new BValue.BDictionary(map));
    }

    public static ResumeData decode(byte[] data) {
        if (!(Bencode.decode(data) instanceof BValue.BDictionary dict)) {
            throw new IllegalArgumentException("resume 資料頂層必須是 dictionary");
        }
        byte[] torrentBytes = requireString(dict, "torrent");
        Metainfo metainfo = Metainfo.parse(torrentBytes);

        Bitfield completed = Bitfield.fromBytes(requireString(dict, "pieces"), metainfo.pieceCount());

        Set<Integer> selected = new LinkedHashSet<>();
        if (dict.get("selected").orElse(null) instanceof BValue.BList list) {
            for (BValue value : list.values()) {
                if (value instanceof BValue.BInteger(long i)) {
                    selected.add((int) i);
                }
            }
        }
        String saveToText = dict.get("saveTo").orElse(null) instanceof BValue.BString s ? s.utf8() : "";
        Path saveTo = saveToText.isEmpty() ? null : Path.of(saveToText);
        long uploaded = dict.get("uploaded").orElse(null) instanceof BValue.BInteger(long u) ? u : 0;
        boolean stopped = dict.get("stopped").orElse(null) instanceof BValue.BInteger(long v) && v == 1;
        boolean seedAfter = dict.get("seedAfter").orElse(null) instanceof BValue.BInteger(long sa) && sa == 1;

        return new ResumeData(torrentBytes, completed, Set.copyOf(selected), saveTo, uploaded, stopped, seedAfter);
    }

    private static byte[] requireString(BValue.BDictionary dict, String key) {
        if (dict.get(key).orElse(null) instanceof BValue.BString(byte[] bytes)) {
            return bytes;
        }
        throw new IllegalArgumentException("resume 資料缺少欄位: " + key);
    }
}
