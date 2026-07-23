package net.derrek.bt4j.metainfo;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 磁力連結（BEP 9 定義的 magnet URI 子集）。
 *
 * @param infoHash    xt=urn:btih:{hex}（必要）
 * @param displayName dn=（選填，僅供 metadata 取得前顯示）
 * @param trackers    tr=（可多個）
 * @param peers       x.pe=（可多個，直連 peer 位址）
 */
public record MagnetUri(InfoHash infoHash,
                        Optional<String> displayName,
                        List<URI> trackers,
                        List<InetSocketAddress> peers) {

    /**
     * 解析磁力連結字串。
     *
     * @throws IllegalArgumentException 非 magnet scheme、缺 xt、或 info-hash 格式錯誤
     */
    public static MagnetUri parse(String magnetLink) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
