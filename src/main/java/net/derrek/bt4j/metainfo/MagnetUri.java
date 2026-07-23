package net.derrek.bt4j.metainfo;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 磁力連結（BEP 9 定義的 magnet URI 子集）。
 *
 * @param infoHash    xt=urn:btih:{hex40 | base32}（必要）
 * @param displayName dn=（選填，僅供 metadata 取得前顯示）
 * @param trackers    tr=（可多個）
 * @param peers       x.pe=（可多個，直連 peer 位址）
 */
public record MagnetUri(InfoHash infoHash,
                        Optional<String> displayName,
                        List<URI> trackers,
                        List<InetSocketAddress> peers) {

    private static final String SCHEME_PREFIX = "magnet:?";
    private static final String BTIH_PREFIX = "urn:btih:";

    /**
     * 解析磁力連結字串。btih 支援 40 字元 hex 與 32 字元 Base32 兩種形式。
     * 無法解析的 tr=/x.pe= 個別項目採寬容忽略；BitTorrent v2 的 btmh 不支援。
     *
     * @throws IllegalArgumentException 非 magnet scheme、缺 xt、或 info-hash 格式錯誤
     */
    public static MagnetUri parse(String magnetLink) {
        if (!magnetLink.startsWith(SCHEME_PREFIX)) {
            throw new IllegalArgumentException("不是 magnet 連結: " + magnetLink);
        }
        InfoHash infoHash = null;
        String displayName = null;
        List<URI> trackers = new ArrayList<>();
        List<InetSocketAddress> peers = new ArrayList<>();

        for (String param : magnetLink.substring(SCHEME_PREFIX.length()).split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = param.substring(0, eq);
            String value = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
            switch (key) {
                case "xt" -> {
                    if (value.startsWith(BTIH_PREFIX)) {
                        infoHash = parseBtih(value.substring(BTIH_PREFIX.length()));
                    }
                    // urn:btmh:（v2）目前不支援，略過；若整條連結只有 btmh 會因缺 btih 而在最後拋錯
                }
                case "dn" -> displayName = value;
                case "tr" -> {
                    try {
                        trackers.add(new URI(value));
                    } catch (URISyntaxException ignored) {
                        // 個別壞掉的 tracker URL 不應讓整條連結失敗
                    }
                }
                case "x.pe" -> parsePeer(value).ifPresent(peers::add);
                default -> {
                    // 其他參數（xl、ws、so…）目前不使用
                }
            }
        }
        if (infoHash == null) {
            throw new IllegalArgumentException("磁力連結缺少 xt=urn:btih: 參數: " + magnetLink);
        }
        return new MagnetUri(infoHash, Optional.ofNullable(displayName), List.copyOf(trackers), List.copyOf(peers));
    }

    private static InfoHash parseBtih(String hash) {
        return switch (hash.length()) {
            case 40 -> InfoHash.fromHex(hash);
            case 32 -> InfoHash.fromBase32(hash);
            default -> throw new IllegalArgumentException(
                    "btih 必須是 40 字元 hex 或 32 字元 Base32，收到 " + hash.length() + " 字元: " + hash);
        };
    }

    /** host:port（IPv6 為 [addr]:port）。格式錯誤回傳 empty（寬容）。 */
    private static Optional<InetSocketAddress> parsePeer(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return Optional.empty();
        }
        String host = value.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            if (port < 1 || port > 65535) {
                return Optional.empty();
            }
            return Optional.of(InetSocketAddress.createUnresolved(host, port));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
