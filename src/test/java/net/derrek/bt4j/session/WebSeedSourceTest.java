package net.derrek.bt4j.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import net.derrek.bt4j.TorrentFixtures;
import net.derrek.bt4j.metainfo.Metainfo;
import org.junit.jupiter.api.Test;

/** BEP 19 URL construction: mapping a torrent file to its mirror URL. */
class WebSeedSourceTest {

    @Test
    void singleFileWithDirectoryBaseAppendsTheName() {
        Metainfo meta = TorrentFixtures.singleFile("movie.mkv", new byte[10], 16384, "http://tr/");
        URI url = WebSeedSource.fileUrl(URI.create("http://mirror.example.com/pub/"), meta, meta.files().getFirst());
        assertEquals("http://mirror.example.com/pub/movie.mkv", url.toString());
    }

    @Test
    void singleFileWithDirectFileUrlIsUsedAsIs() {
        Metainfo meta = TorrentFixtures.singleFile("movie.mkv", new byte[10], 16384, "http://tr/");
        URI url = WebSeedSource.fileUrl(URI.create("http://mirror.example.com/pub/movie.mkv"), meta, meta.files().getFirst());
        assertEquals("http://mirror.example.com/pub/movie.mkv", url.toString());
    }

    @Test
    void multiFileAppendsTheFullPathIncludingTorrentName() {
        Metainfo meta = TorrentFixtures.multiFile("dist", List.of(
                new TorrentFixtures.TestFile(List.of("a.bin"), new byte[10]),
                new TorrentFixtures.TestFile(List.of("sub", "b.bin"), new byte[10])), 16384, "http://tr/");
        URI base = URI.create("http://mirror.example.com/files"); // no trailing slash: added automatically
        assertEquals("http://mirror.example.com/files/dist/a.bin",
                WebSeedSource.fileUrl(base, meta, meta.files().get(0)).toString());
        assertEquals("http://mirror.example.com/files/dist/sub/b.bin",
                WebSeedSource.fileUrl(base, meta, meta.files().get(1)).toString());
    }

    @Test
    void spacesInPathArePercentEncoded() {
        Metainfo meta = TorrentFixtures.singleFile("my movie.mkv", new byte[10], 16384, "http://tr/");
        URI url = WebSeedSource.fileUrl(URI.create("http://mirror.example.com/pub/"), meta, meta.files().getFirst());
        assertEquals("http://mirror.example.com/pub/my%20movie.mkv", url.toString());
    }
}
