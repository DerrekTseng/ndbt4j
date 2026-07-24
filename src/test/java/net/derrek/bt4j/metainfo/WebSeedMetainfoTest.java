package net.derrek.bt4j.metainfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import org.junit.jupiter.api.Test;

/** BEP 19 url-list parsing on Metainfo. */
class WebSeedMetainfoTest {

    /** A minimal single-file torrent with an optional top-level url-list value. */
    private static Metainfo torrent(BValue urlList) {
        SequencedMap<BValue.BString, BValue> info = new LinkedHashMap<>();
        info.put(BValue.BString.of("length"), new BValue.BInteger(10));
        info.put(BValue.BString.of("name"), BValue.BString.of("f.bin"));
        info.put(BValue.BString.of("piece length"), new BValue.BInteger(16384));
        info.put(BValue.BString.of("pieces"), new BValue.BString(new byte[20])); // one piece, hash irrelevant here

        SequencedMap<BValue.BString, BValue> top = new LinkedHashMap<>();
        top.put(BValue.BString.of("announce"), BValue.BString.of("http://tr/"));
        top.put(BValue.BString.of("info"), new BValue.BDictionary(info));
        if (urlList != null) {
            top.put(BValue.BString.of("url-list"), urlList);
        }
        return Metainfo.parse(Bencode.encode(new BValue.BDictionary(top)));
    }

    @Test
    void parsesASingleUrlListString() {
        Metainfo meta = torrent(BValue.BString.of("http://mirror.example.com/f.bin"));
        assertEquals(List.of("http://mirror.example.com/f.bin"),
                meta.webSeeds().stream().map(Object::toString).toList());
    }

    @Test
    void parsesAUrlListOfSeveralStringsAndFiltersNonHttp() {
        BValue.BList list = new BValue.BList(List.of(
                BValue.BString.of("http://a.example.com/f"),
                BValue.BString.of("https://b.example.com/f"),
                BValue.BString.of("ftp://c.example.com/f"),   // dropped: not http(s)
                BValue.BString.of("::: not a uri")));         // dropped: unparseable
        Metainfo meta = torrent(list);
        assertEquals(List.of("http://a.example.com/f", "https://b.example.com/f"),
                meta.webSeeds().stream().map(Object::toString).toList());
    }

    @Test
    void absentUrlListIsEmpty() {
        assertTrue(torrent(null).webSeeds().isEmpty());
    }

    @Test
    void webSeedsSurviveToTorrentBytesRoundTrip() {
        Metainfo meta = torrent(BValue.BString.of("http://mirror.example.com/f.bin"));
        Metainfo reparsed = Metainfo.parse(meta.toTorrentBytes());
        assertEquals(meta.webSeeds(), reparsed.webSeeds());
        assertEquals(meta.infoHash(), reparsed.infoHash()); // info-hash unaffected by the top-level url-list
    }
}
