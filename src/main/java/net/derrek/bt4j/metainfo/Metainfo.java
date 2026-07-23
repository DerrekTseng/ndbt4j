package net.derrek.bt4j.metainfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;

/**
 * torrent metadata（BEP 3 的 metainfo）。
 * 來源可以是 .torrent 檔，或磁力連結經 BEP 9 從 peer 取得的 info 字典。
 * 不可變。
 *
 * info-hash 一律對 info 字典的「原始位元組」計算（保留輸入原貌，不重新編碼），
 * 因此非 canonical 的 .torrent 檔也能得到與其他客戶端一致的 hash。
 */
public final class Metainfo {

    private static final System.Logger LOG = System.getLogger(Metainfo.class.getName());

    private final byte[] infoDictBytes;
    private final InfoHash infoHash;
    private final String name;
    private final List<FileEntry> files;
    private final long totalLength;
    private final long pieceLength;
    private final byte[] pieces;
    private final List<List<URI>> announceList;
    private final boolean isPrivate;

    private Metainfo(byte[] infoDictBytes, List<List<URI>> announceList) {
        this.infoDictBytes = infoDictBytes;
        this.infoHash = InfoHash.ofInfoDict(infoDictBytes);
        this.announceList = announceList;

        if (!(Bencode.decode(infoDictBytes) instanceof BValue.BDictionary info)) {
            throw new IllegalArgumentException("info 必須是 dictionary");
        }
        this.name = requireString(info, "name").utf8();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("info.name 不得為空");
        }
        this.pieceLength = requireInteger(info, "piece length");
        if (pieceLength <= 0) {
            throw new IllegalArgumentException("piece length 必須為正數: " + pieceLength);
        }
        this.pieces = requireString(info, "pieces").bytes();
        if (pieces.length == 0 || pieces.length % 20 != 0) {
            throw new IllegalArgumentException("pieces 長度必須是 20 的正倍數: " + pieces.length);
        }
        this.files = List.copyOf(parseFiles(info));
        this.totalLength = files.stream().mapToLong(FileEntry::length).sum();
        long expectedPieces = (totalLength + pieceLength - 1) / pieceLength;
        if (expectedPieces != pieces.length / 20) {
            throw new IllegalArgumentException(
                    "piece 數不一致：依檔案總長 " + totalLength + " 應為 " + expectedPieces + "，pieces 欄位有 " + pieces.length / 20);
        }
        this.isPrivate = info.get("private").orElse(null) instanceof BValue.BInteger(long v) && v == 1;
        LOG.log(System.Logger.Level.TRACE, () -> "解析 metainfo: " + name + "（" + files.size()
                + " 檔案, " + pieceCount() + " pieces, private=" + isPrivate + "）");
    }

    // ---- 建構 ----

    /** 解析 .torrent 檔內容。 */
    public static Metainfo parse(byte[] torrentFileBytes) {
        if (!(Bencode.decode(torrentFileBytes) instanceof BValue.BDictionary outer)) {
            throw new IllegalArgumentException(".torrent 頂層必須是 dictionary");
        }
        byte[] infoBytes = extractInfoRawBytes(torrentFileBytes);
        return new Metainfo(infoBytes, parseAnnounceList(outer));
    }

    public static Metainfo parse(Path torrentFile) {
        try {
            return parse(Files.readAllBytes(torrentFile));
        } catch (IOException e) {
            throw new UncheckedIOException("讀取 .torrent 檔失敗: " + torrentFile, e);
        }
    }

    /**
     * 從 BEP 9 取得的 info 字典原始位元組建立（磁力連結情境）。
     * 呼叫端須先驗證 SHA-1(infoDictBytes) == 磁力連結的 info-hash。
     *
     * @param trackers 磁力連結 tr= 參數帶的 tracker，作為單一 tier
     */
    public static Metainfo fromInfoDict(byte[] infoDictBytes, List<URI> trackers) {
        List<List<URI>> tiers = trackers.isEmpty() ? List.of() : List.of(List.copyOf(trackers));
        return new Metainfo(infoDictBytes.clone(), tiers);
    }

    /** 頂層 dict 逐項掃描，取 info 值的原始位元組區段（info-hash 的計算對象）。 */
    private static byte[] extractInfoRawBytes(byte[] data) {
        int pos = 1; // 頂層 'd' 之後
        while (data[pos] != 'e') {
            Bencode.DecodeResult key = Bencode.decode(data, pos);
            Bencode.DecodeResult value = Bencode.decode(data, key.end());
            if (key.value() instanceof BValue.BString(byte[] k) && new String(k, java.nio.charset.StandardCharsets.UTF_8).equals("info")) {
                return Arrays.copyOfRange(data, value.start(), value.end());
            }
            pos = value.end();
        }
        throw new IllegalArgumentException(".torrent 缺少 info 字典");
    }

    /** announce-list（BEP 12）優先，否則單一 announce。無法解析的 URI 個別忽略。 */
    private static List<List<URI>> parseAnnounceList(BValue.BDictionary outer) {
        if (outer.get("announce-list").orElse(null) instanceof BValue.BList tiers) {
            List<List<URI>> result = new ArrayList<>();
            for (BValue tierValue : tiers.values()) {
                if (!(tierValue instanceof BValue.BList tier)) {
                    continue;
                }
                List<URI> uris = new ArrayList<>();
                for (BValue urlValue : tier.values()) {
                    if (urlValue instanceof BValue.BString url) {
                        toUri(url.utf8()).ifPresent(uris::add);
                    }
                }
                if (!uris.isEmpty()) {
                    result.add(List.copyOf(uris));
                }
            }
            if (!result.isEmpty()) {
                return List.copyOf(result);
            }
        }
        if (outer.get("announce").orElse(null) instanceof BValue.BString announce) {
            return toUri(announce.utf8()).map(uri -> List.of(List.of(uri))).orElse(List.of());
        }
        return List.of();
    }

    private static java.util.Optional<URI> toUri(String s) {
        try {
            return java.util.Optional.of(new URI(s));
        } catch (URISyntaxException e) {
            return java.util.Optional.empty();
        }
    }

    private List<FileEntry> parseFiles(BValue.BDictionary info) {
        List<FileEntry> result = new ArrayList<>();
        if (info.get("files").orElse(null) instanceof BValue.BList fileList) {
            // 多檔：路徑為 name/子路徑…
            long offset = 0;
            for (BValue entryValue : fileList.values()) {
                if (!(entryValue instanceof BValue.BDictionary entry)) {
                    throw new IllegalArgumentException("files 內每個項目必須是 dictionary");
                }
                long length = requireInteger(entry, "length");
                if (length < 0) {
                    throw new IllegalArgumentException("檔案長度不得為負: " + length);
                }
                if (!(entry.get("path").orElse(null) instanceof BValue.BList pathList) || pathList.values().isEmpty()) {
                    throw new IllegalArgumentException("files 項目缺少 path");
                }
                List<String> path = new ArrayList<>();
                path.add(validatePathComponent(name));
                for (BValue component : pathList.values()) {
                    if (!(component instanceof BValue.BString s)) {
                        throw new IllegalArgumentException("path 內每個成分必須是字串");
                    }
                    path.add(validatePathComponent(s.utf8()));
                }
                result.add(new FileEntry(result.size(), List.copyOf(path), length, offset));
                offset += length;
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("files 清單為空");
            }
        } else {
            // 單檔
            long length = requireInteger(info, "length");
            if (length < 0) {
                throw new IllegalArgumentException("檔案長度不得為負: " + length);
            }
            result.add(new FileEntry(0, List.of(validatePathComponent(name)), length, 0));
        }
        return result;
    }

    /** 防路徑穿越：path 成分不得為空、"."、".."，或含分隔符號／NUL。 */
    private static String validatePathComponent(String component) {
        if (component.isEmpty() || component.equals(".") || component.equals("..")
                || component.indexOf('/') >= 0 || component.indexOf('\\') >= 0 || component.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法的路徑成分: \"" + component + "\"");
        }
        return component;
    }

    private static BValue.BString requireString(BValue.BDictionary dict, String key) {
        if (dict.get(key).orElse(null) instanceof BValue.BString s) {
            return s;
        }
        throw new IllegalArgumentException("缺少或型別錯誤的欄位: " + key);
    }

    private static long requireInteger(BValue.BDictionary dict, String key) {
        if (dict.get(key).orElse(null) instanceof BValue.BInteger(long v)) {
            return v;
        }
        throw new IllegalArgumentException("缺少或型別錯誤的欄位: " + key);
    }

    // ---- 存取 ----

    public InfoHash infoHash() {
        return infoHash;
    }

    /** torrent 名稱（info.name）。 */
    public String name() {
        return name;
    }

    /** 所有檔案，依 torrent 內順序；單檔 torrent 也統一以此表示。 */
    public List<FileEntry> files() {
        return files;
    }

    /** 全部檔案長度總和。 */
    public long totalLength() {
        return totalLength;
    }

    /** 每個 piece 的長度（最後一個 piece 可能較短）。 */
    public long pieceLength() {
        return pieceLength;
    }

    public int pieceCount() {
        return pieces.length / 20;
    }

    /** 第 index 個 piece 的實際長度（處理最後一個 piece）。 */
    public int pieceLengthAt(int index) {
        java.util.Objects.checkIndex(index, pieceCount());
        if (index == pieceCount() - 1) {
            long last = totalLength - (long) index * pieceLength;
            return (int) last;
        }
        return (int) pieceLength;
    }

    /** 第 index 個 piece 的 SHA-1（20 bytes）。 */
    public byte[] pieceHash(int index) {
        java.util.Objects.checkIndex(index, pieceCount());
        return Arrays.copyOfRange(pieces, index * 20, index * 20 + 20);
    }

    /** tracker 分層清單（BEP 12 tiers）。可能為空（純 DHT）。 */
    public List<List<URI>> announceList() {
        return announceList;
    }

    /** private flag（BEP 27）。true 時不得使用 DHT / PEX / LSD。 */
    public boolean isPrivate() {
        return isPrivate;
    }

    /** info 字典的原始位元組（BEP 9 回應 metadata request 時直接使用）。 */
    public byte[] infoDictBytes() {
        return infoDictBytes.clone();
    }

    // ---- 輸出 ----

    /**
     * 序列化為標準 .torrent 檔內容（磁力連結取得 metadata 後可存檔）。
     * info 區段直接嵌入原始位元組，保證 info-hash 不變。
     */
    public byte[] toTorrentBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('d');
        // 頂層 key 需依 bencoding 排序："announce" < "announce-list" < "info"
        List<URI> flat = announceList.stream().flatMap(List::stream).toList();
        if (!flat.isEmpty()) {
            writeBString(out, "announce");
            writeBString(out, flat.getFirst().toString());
            if (flat.size() > 1 || announceList.size() > 1) {
                writeBString(out, "announce-list");
                BValue.BList tiers = new BValue.BList(announceList.stream()
                        .map(tier -> (BValue) new BValue.BList(tier.stream()
                                .map(uri -> (BValue) BValue.BString.of(uri.toString()))
                                .toList()))
                        .toList());
                out.writeBytes(Bencode.encode(tiers));
            }
        }
        writeBString(out, "info");
        out.writeBytes(infoDictBytes);
        out.write('e');
        return out.toByteArray();
    }

    public void saveTorrentFile(Path target) {
        try {
            Files.write(target, toTorrentBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("寫入 .torrent 檔失敗: " + target, e);
        }
    }

    private static void writeBString(ByteArrayOutputStream out, String text) {
        out.writeBytes(Bencode.encode(BValue.BString.of(text)));
    }

    @Override
    public String toString() {
        return "Metainfo[" + name + ", " + files.size() + " files, " + totalLength + " bytes, " + infoHash.hex() + "]";
    }
}
