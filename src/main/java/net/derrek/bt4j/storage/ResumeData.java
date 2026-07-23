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
 * Resume data: the complete, self-contained state needed to restore a session after a server restart.
 * The .torrent bytes are embedded (metadata obtained from a magnet link is also converted to .torrent form),
 * so a single resume file can fully restore a session without a separate .torrent.
 * Serialized with bencoding (reusing our own encoder, without introducing another format).
 *
 * @param torrentBytes        the embedded metainfo (standard .torrent bytes)
 * @param completedPieces     pieces that have been verified as complete
 * @param selectedFileIndices files selected by the user (empty set = all selected)
 * @param saveTo              download destination
 * @param uploaded            cumulative upload amount (statistics carried over)
 * @param seedingStopped      whether the user has manually stopped uploading
 * @param seedAfterComplete   whether to seed after the download completes
 */
public record ResumeData(byte[] torrentBytes,
                         Bitfield completedPieces,
                         Set<Integer> selectedFileIndices,
                         Path saveTo,
                         long uploaded,
                         boolean seedingStopped,
                         boolean seedAfterComplete) {

    /** Parse the embedded metainfo. */
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

    /** Serialize to bencoded bytes. */
    public byte[] encode() {
        List<BValue> selected = selectedFileIndices.stream()
                .sorted()
                .map(i -> (BValue) new BValue.BInteger(i))
                .toList();
        // TreeMap gives the bencode dict keys their natural ordering
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
            throw new IllegalArgumentException("resume data top level must be a dictionary");
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
        throw new IllegalArgumentException("resume data missing field: " + key);
    }
}
