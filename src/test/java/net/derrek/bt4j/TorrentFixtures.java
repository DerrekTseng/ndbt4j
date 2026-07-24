package net.derrek.bt4j;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.metainfo.Metainfo;

/** Torrent generator for tests: computes piece hashes from real content. */
public final class TorrentFixtures {

    /** A single file within a multi-file torrent: path (excluding the torrent name) and content. */
    public record TestFile(List<String> path, byte[] content) {
    }

    private TorrentFixtures() {
    }

    /** Single-file torrent. */
    public static Metainfo singleFile(String name, byte[] content, int pieceLength, String announce) {
        BValue.BDictionary info = dict(
                "length", new BValue.BInteger(content.length),
                "name", BValue.BString.of(name),
                "piece length", new BValue.BInteger(pieceLength),
                "pieces", new BValue.BString(pieceHashes(content, pieceLength)));
        return Metainfo.parse(Bencode.encode(dict(
                "announce", BValue.BString.of(announce),
                "info", info)));
    }

    /** Multi-file torrent (pieces are split contiguously across files). */
    public static Metainfo multiFile(String name, List<TestFile> files, int pieceLength, String announce) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        List<BValue> fileEntries = new java.util.ArrayList<>();
        for (TestFile file : files) {
            all.writeBytes(file.content());
            fileEntries.add(dict(
                    "length", new BValue.BInteger(file.content().length),
                    "path", new BValue.BList(file.path().stream().map(BValue.BString::of).map(v -> (BValue) v).toList())));
        }
        BValue.BDictionary info = dict(
                "files", new BValue.BList(List.copyOf(fileEntries)),
                "name", BValue.BString.of(name),
                "piece length", new BValue.BInteger(pieceLength),
                "pieces", new BValue.BString(pieceHashes(all.toByteArray(), pieceLength)));
        return Metainfo.parse(Bencode.encode(dict(
                "announce", BValue.BString.of(announce),
                "info", info)));
    }

    /** Splits the content into segments of pieceLength and SHA-1s each segment. */
    public static byte[] pieceHashes(byte[] content, int pieceLength) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int start = 0; start < content.length; start += pieceLength) {
                sha1.reset();
                sha1.update(content, start, Math.min(pieceLength, content.length - start));
                out.writeBytes(sha1.digest());
            }
            return out.toByteArray();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Single-file torrent carrying a BEP 19 url-list web seed. */
    public static Metainfo singleFileWithWebSeed(String name, byte[] content, int pieceLength,
                                                 String announce, String webSeedUrl) {
        BValue.BDictionary info = dict(
                "length", new BValue.BInteger(content.length),
                "name", BValue.BString.of(name),
                "piece length", new BValue.BInteger(pieceLength),
                "pieces", new BValue.BString(pieceHashes(content, pieceLength)));
        return Metainfo.parse(Bencode.encode(dict(
                "announce", BValue.BString.of(announce),
                "info", info,
                "url-list", BValue.BString.of(webSeedUrl))));
    }

    public static byte[] randomBytes(int length, long seed) {
        byte[] data = new byte[length];
        new java.util.Random(seed).nextBytes(data);
        return data;
    }

    private static BValue.BDictionary dict(Object... keyValues) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(BValue.BString.of((String) keyValues[i]), (BValue) keyValues[i + 1]);
        }
        return new BValue.BDictionary(map);
    }
}
