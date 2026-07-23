package net.derrek.bt4j.peer.ext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.bencode.Bencode;
import net.derrek.bt4j.bencode.BencodeException;
import net.derrek.bt4j.peer.PeerAddress;
import net.derrek.bt4j.peer.PeerConnection;

/**
 * ut_pex（BEP 11）：與已連線 peer 定期交換 peer 清單（added/dropped，compact 格式）。
 * 每條連線一個實例（狀態：上次送給該 peer 的集合）。週期不得短於 60 秒。
 * private torrent（BEP 27）時不得建立此擴充。
 */
public final class PeerExchange implements Extension {

    private static final System.Logger LOG = System.getLogger(PeerExchange.class.getName());

    /** BEP 11 規定的最小交換週期。 */
    static final long MIN_INTERVAL_NANOS = 60_000_000_000L;
    /** 單次訊息最多攜帶的 peer 數（避免封包過大）。 */
    static final int MAX_PER_MESSAGE = 50;

    private final Supplier<Set<PeerAddress>> currentPeers;
    private final Consumer<List<PeerAddress>> onPeersDiscovered;

    private Set<PeerAddress> lastSent = Set.of();
    private long lastSentAtNanos;
    private boolean everSent;

    /**
     * @param currentPeers      目前 session 已連線的 peer 位址（快照來源）
     * @param onPeersDiscovered 從對方 PEX 訊息學到的新 peer
     */
    public PeerExchange(Supplier<Set<PeerAddress>> currentPeers, Consumer<List<PeerAddress>> onPeersDiscovered) {
        this.currentPeers = currentPeers;
        this.onPeersDiscovered = onPeersDiscovered;
    }

    @Override
    public String name() {
        return "ut_pex";
    }

    @Override
    public void onExtensionHandshake(PeerConnection connection, ExtensionRegistry registry,
                                     BValue.BDictionary handshake) {
        // 首次交換由 session 的週期 tick 觸發，這裡不主動送。
    }

    @Override
    public void onMessage(PeerConnection connection, ExtensionRegistry registry, byte[] payload) {
        BValue.BDictionary dict;
        try {
            if (!(Bencode.decode(payload) instanceof BValue.BDictionary d)) {
                return;
            }
            dict = d;
        } catch (BencodeException e) {
            return;
        }
        List<PeerAddress> discovered = new ArrayList<>();
        if (dict.get("added").orElse(null) instanceof BValue.BString(byte[] added) && added.length % 6 == 0) {
            try {
                discovered.addAll(PeerAddress.fromCompact(added));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (dict.get("added6").orElse(null) instanceof BValue.BString(byte[] added6) && added6.length % 18 == 0) {
            try {
                discovered.addAll(PeerAddress.fromCompact6(added6));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!discovered.isEmpty()) {
            LOG.log(System.Logger.Level.TRACE, () -> "PEX from " + connection.address() + " added " + discovered.size() + " peers");
            onPeersDiscovered.accept(discovered);
        }
        // dropped/dropped6 忽略：我們不因 PEX 主動斷線。
    }

    /**
     * 由 session 週期呼叫（≥60 秒一次）：對此 peer 送出自上次以來的 added/dropped 差異。
     * 對方未宣告支援 ut_pex 時不做事。
     */
    public void tick(PeerConnection connection, ExtensionRegistry registry) {
        if (!registry.peerSupports(name())) {
            return;
        }
        long now = System.nanoTime();
        if (everSent && now - lastSentAtNanos < MIN_INTERVAL_NANOS) {
            return;
        }
        Set<PeerAddress> current = Set.copyOf(currentPeers.get());
        List<PeerAddress> added = current.stream().filter(p -> !lastSent.contains(p)).limit(MAX_PER_MESSAGE).toList();
        List<PeerAddress> dropped = lastSent.stream().filter(p -> !current.contains(p)).limit(MAX_PER_MESSAGE).toList();
        if (everSent && added.isEmpty() && dropped.isEmpty()) {
            return; // 沒有變化就不送（首次即使為空也送一次以表態）
        }
        byte[] message = build(added, dropped);
        if (registry.send(connection, name(), message)) {
            lastSent = current;
            lastSentAtNanos = now;
            everSent = true;
            LOG.log(System.Logger.Level.TRACE, () -> "PEX to " + connection.address()
                    + " added=" + added.size() + " dropped=" + dropped.size());
        }
    }

    static byte[] build(List<PeerAddress> added, List<PeerAddress> dropped) {
        List<PeerAddress> added4 = ipv4(added);
        List<PeerAddress> added6 = ipv6(added);
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        map.put(BValue.BString.of("added"), new BValue.BString(compact4(added4)));
        map.put(BValue.BString.of("added.f"), new BValue.BString(new byte[added4.size()])); // 每 peer 一旗標位元組（0）
        if (!added6.isEmpty()) {
            map.put(BValue.BString.of("added6"), new BValue.BString(compact6(added6)));
        }
        map.put(BValue.BString.of("dropped"), new BValue.BString(compact4(ipv4(dropped))));
        return Bencode.encode(new BValue.BDictionary(map));
    }

    private static List<PeerAddress> ipv4(List<PeerAddress> peers) {
        return peers.stream().filter(p -> bytesOf(p) != null && bytesOf(p).length == 4).toList();
    }

    private static List<PeerAddress> ipv6(List<PeerAddress> peers) {
        return peers.stream().filter(p -> bytesOf(p) != null && bytesOf(p).length == 16).toList();
    }

    private static byte[] bytesOf(PeerAddress peer) {
        InetAddress a = peer.socketAddress().getAddress();
        return a == null ? null : a.getAddress();
    }

    private static byte[] compact4(List<PeerAddress> peers) {
        return compact(peers, 4);
    }

    private static byte[] compact6(List<PeerAddress> peers) {
        return compact(peers, 16);
    }

    private static byte[] compact(List<PeerAddress> peers, int addressLength) {
        byte[] out = new byte[peers.size() * (addressLength + 2)];
        int i = 0;
        for (PeerAddress peer : peers) {
            InetSocketAddress sa = peer.socketAddress();
            byte[] ip = sa.getAddress().getAddress();
            System.arraycopy(ip, 0, out, i, addressLength);
            out[i + addressLength] = (byte) (sa.getPort() >> 8);
            out[i + addressLength + 1] = (byte) sa.getPort();
            i += addressLength + 2;
        }
        return out;
    }
}
