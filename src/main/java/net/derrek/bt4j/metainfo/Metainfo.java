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
 * Torrent metadata (the metainfo of BEP 3).
 * The source can be a .torrent file, or the info dictionary obtained from a peer via BEP 9 from a magnet link.
 * Immutable.
 *
 * The info-hash is always computed over the "raw bytes" of the info dictionary (preserving the input as-is, not re-encoding),
 * so even a non-canonical .torrent file yields a hash consistent with other clients.
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
            throw new IllegalArgumentException("info must be a dictionary");
        }
        this.name = requireString(info, "name").utf8();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("info.name must not be empty");
        }
        this.pieceLength = requireInteger(info, "piece length");
        if (pieceLength <= 0) {
            throw new IllegalArgumentException("piece length must be positive: " + pieceLength);
        }
        this.pieces = requireString(info, "pieces").bytes();
        if (pieces.length == 0 || pieces.length % 20 != 0) {
            throw new IllegalArgumentException("pieces length must be a positive multiple of 20: " + pieces.length);
        }
        this.files = List.copyOf(parseFiles(info));
        this.totalLength = files.stream().mapToLong(FileEntry::length).sum();
        long expectedPieces = (totalLength + pieceLength - 1) / pieceLength;
        if (expectedPieces != pieces.length / 20) {
            throw new IllegalArgumentException(
                    "piece count mismatch: total file length " + totalLength + " implies " + expectedPieces + ", but the pieces field has " + pieces.length / 20);
        }
        this.isPrivate = info.get("private").orElse(null) instanceof BValue.BInteger(long v) && v == 1;
        LOG.log(System.Logger.Level.TRACE, () -> "parsed metainfo: " + name + " (" + files.size()
                + " files, " + pieceCount() + " pieces, private=" + isPrivate + ")");
    }

    // ---- Construction ----

    /** Parse the contents of a .torrent file. */
    public static Metainfo parse(byte[] torrentFileBytes) {
        if (!(Bencode.decode(torrentFileBytes) instanceof BValue.BDictionary outer)) {
            throw new IllegalArgumentException(".torrent top level must be a dictionary");
        }
        byte[] infoBytes = extractInfoRawBytes(torrentFileBytes);
        return new Metainfo(infoBytes, parseAnnounceList(outer));
    }

    public static Metainfo parse(Path torrentFile) {
        try {
            return parse(Files.readAllBytes(torrentFile));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read .torrent file: " + torrentFile, e);
        }
    }

    /**
     * Build from the raw bytes of an info dictionary obtained via BEP 9 (magnet link scenario).
     * The caller must first verify that SHA-1(infoDictBytes) == the magnet link's info-hash.
     *
     * @param trackers the trackers carried by the magnet link's tr= parameter, as a single tier
     */
    public static Metainfo fromInfoDict(byte[] infoDictBytes, List<URI> trackers) {
        List<List<URI>> tiers = trackers.isEmpty() ? List.of() : List.of(List.copyOf(trackers));
        return new Metainfo(infoDictBytes.clone(), tiers);
    }

    /** Scan the top-level dict entry by entry, taking the raw byte span of the info value (the subject of the info-hash computation). */
    private static byte[] extractInfoRawBytes(byte[] data) {
        int pos = 1; // after the top-level 'd'
        while (data[pos] != 'e') {
            Bencode.DecodeResult key = Bencode.decode(data, pos);
            Bencode.DecodeResult value = Bencode.decode(data, key.end());
            if (key.value() instanceof BValue.BString(byte[] k) && new String(k, java.nio.charset.StandardCharsets.UTF_8).equals("info")) {
                return Arrays.copyOfRange(data, value.start(), value.end());
            }
            pos = value.end();
        }
        throw new IllegalArgumentException(".torrent is missing the info dictionary");
    }

    /** announce-list (BEP 12) takes precedence, otherwise a single announce. Unparseable URIs are individually ignored. */
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
            // multi-file: path is name/subpath...
            long offset = 0;
            for (BValue entryValue : fileList.values()) {
                if (!(entryValue instanceof BValue.BDictionary entry)) {
                    throw new IllegalArgumentException("each item in files must be a dictionary");
                }
                long length = requireInteger(entry, "length");
                if (length < 0) {
                    throw new IllegalArgumentException("file length must not be negative: " + length);
                }
                if (!(entry.get("path").orElse(null) instanceof BValue.BList pathList) || pathList.values().isEmpty()) {
                    throw new IllegalArgumentException("files item is missing path");
                }
                List<String> path = new ArrayList<>();
                path.add(validatePathComponent(name));
                for (BValue component : pathList.values()) {
                    if (!(component instanceof BValue.BString s)) {
                        throw new IllegalArgumentException("each component in path must be a string");
                    }
                    path.add(validatePathComponent(s.utf8()));
                }
                result.add(new FileEntry(result.size(), List.copyOf(path), length, offset));
                offset += length;
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("files list is empty");
            }
        } else {
            // single file
            long length = requireInteger(info, "length");
            if (length < 0) {
                throw new IllegalArgumentException("file length must not be negative: " + length);
            }
            result.add(new FileEntry(0, List.of(validatePathComponent(name)), length, 0));
        }
        return result;
    }

    /** Path-traversal guard: a path component must not be empty, ".", "..", or contain a separator / NUL. */
    private static String validatePathComponent(String component) {
        if (component.isEmpty() || component.equals(".") || component.equals("..")
                || component.indexOf('/') >= 0 || component.indexOf('\\') >= 0 || component.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("illegal path component: \"" + component + "\"");
        }
        return component;
    }

    private static BValue.BString requireString(BValue.BDictionary dict, String key) {
        if (dict.get(key).orElse(null) instanceof BValue.BString s) {
            return s;
        }
        throw new IllegalArgumentException("missing or wrong-typed field: " + key);
    }

    private static long requireInteger(BValue.BDictionary dict, String key) {
        if (dict.get(key).orElse(null) instanceof BValue.BInteger(long v)) {
            return v;
        }
        throw new IllegalArgumentException("missing or wrong-typed field: " + key);
    }

    // ---- Accessors ----

    public InfoHash infoHash() {
        return infoHash;
    }

    /** Torrent name (info.name). */
    public String name() {
        return name;
    }

    /** All files, in torrent order; single-file torrents are represented uniformly this way too. */
    public List<FileEntry> files() {
        return files;
    }

    /** Sum of all file lengths. */
    public long totalLength() {
        return totalLength;
    }

    /** The length of each piece (the last piece may be shorter). */
    public long pieceLength() {
        return pieceLength;
    }

    public int pieceCount() {
        return pieces.length / 20;
    }

    /** The actual length of piece number index (handles the last piece). */
    public int pieceLengthAt(int index) {
        java.util.Objects.checkIndex(index, pieceCount());
        if (index == pieceCount() - 1) {
            long last = totalLength - (long) index * pieceLength;
            return (int) last;
        }
        return (int) pieceLength;
    }

    /** The SHA-1 (20 bytes) of piece number index. */
    public byte[] pieceHash(int index) {
        java.util.Objects.checkIndex(index, pieceCount());
        return Arrays.copyOfRange(pieces, index * 20, index * 20 + 20);
    }

    /** Tracker tier list (BEP 12 tiers). May be empty (DHT-only). */
    public List<List<URI>> announceList() {
        return announceList;
    }

    /** private flag (BEP 27). When true, DHT / PEX / LSD must not be used. */
    public boolean isPrivate() {
        return isPrivate;
    }

    /** The raw bytes of the info dictionary (used directly when responding to a BEP 9 metadata request). */
    public byte[] infoDictBytes() {
        return infoDictBytes.clone();
    }

    // ---- Output ----

    /**
     * Serialize to standard .torrent file contents (can be saved after metadata is obtained from a magnet link).
     * The info section embeds the raw bytes directly, guaranteeing the info-hash is unchanged.
     */
    public byte[] toTorrentBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('d');
        // top-level keys must be bencoding-sorted: "announce" < "announce-list" < "info"
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
            throw new UncheckedIOException("failed to write .torrent file: " + target, e);
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
