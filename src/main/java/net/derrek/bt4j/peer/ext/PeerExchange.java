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
 * ut_pex (BEP 11): periodically exchanges peer lists (added/dropped, compact format) with connected peers.
 * One instance per connection (state: the set last sent to that peer). The period must not be shorter than 60 seconds.
 * This extension must not be created for private torrents (BEP 27).
 */
public final class PeerExchange implements Extension {

    private static final System.Logger LOG = System.getLogger(PeerExchange.class.getName());

    /** The minimum exchange interval required by BEP 11. */
    static final long MIN_INTERVAL_NANOS = 60_000_000_000L;
    /** The maximum number of peers carried in a single message (to avoid oversized packets). */
    static final int MAX_PER_MESSAGE = 50;

    private final Supplier<Set<PeerAddress>> currentPeers;
    private final Consumer<List<PeerAddress>> onPeersDiscovered;
    private final Consumer<List<PeerAddress>> onPeersDropped;

    private Set<PeerAddress> lastSent = Set.of();
    private long lastSentAtNanos;
    private boolean everSent;

    /**
     * @param currentPeers      the addresses of peers currently connected in the session (snapshot source)
     * @param onPeersDiscovered new peers learned from the peer's PEX messages
     */
    public PeerExchange(Supplier<Set<PeerAddress>> currentPeers, Consumer<List<PeerAddress>> onPeersDiscovered) {
        this(currentPeers, onPeersDiscovered, dropped -> {
        });
    }

    /**
     * @param onPeersDropped peers the remote reports as gone from the swarm. Advisory only: the session uses these
     *                       to avoid dialing a candidate that is no longer there, never to disconnect a live peer
     *                       (PEX is untrusted input, so a hostile peer must not be able to sever our connections).
     */
    public PeerExchange(Supplier<Set<PeerAddress>> currentPeers, Consumer<List<PeerAddress>> onPeersDiscovered,
                        Consumer<List<PeerAddress>> onPeersDropped) {
        this.currentPeers = currentPeers;
        this.onPeersDiscovered = onPeersDiscovered;
        this.onPeersDropped = onPeersDropped;
    }

    @Override
    public String name() {
        return "ut_pex";
    }

    @Override
    public void onExtensionHandshake(PeerConnection connection, ExtensionRegistry registry,
                                     BValue.BDictionary handshake) {
        // the first exchange is triggered by the session's periodic tick; do not send here.
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
        List<PeerAddress> gone = new ArrayList<>();
        if (dict.get("dropped").orElse(null) instanceof BValue.BString(byte[] dropped) && dropped.length % 6 == 0) {
            try {
                gone.addAll(PeerAddress.fromCompact(dropped));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (dict.get("dropped6").orElse(null) instanceof BValue.BString(byte[] dropped6) && dropped6.length % 18 == 0) {
            try {
                gone.addAll(PeerAddress.fromCompact6(dropped6));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!gone.isEmpty()) {
            LOG.log(System.Logger.Level.TRACE, () -> "PEX from " + connection.address() + " dropped " + gone.size() + " peers");
            onPeersDropped.accept(gone); // advisory: suppresses dialing, never disconnects a live peer
        }
    }

    /**
     * Called periodically by the session (at most once per >=60 seconds): sends this peer the added/dropped
     * delta since the last time. Does nothing if the peer has not advertised support for ut_pex.
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
            return; // nothing changed, do not send (the first time is sent once even if empty, as an announcement)
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
        map.put(BValue.BString.of("added.f"), new BValue.BString(new byte[added4.size()])); // one flag byte per peer (0)
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
