package net.derrek.bt4j.lsd;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * Local Service Discovery (BEP 14): finds peers on the same LAN by multicasting a small HTTP-like BT-SEARCH
 * datagram, and by listening for the ones other clients send.
 *
 * A LAN peer is usually far faster than anything across the internet, so this can dominate throughput when one
 * exists — and costs nothing when it does not. Announces are rate limited to {@link #ANNOUNCE_INTERVAL_NANOS}
 * (BEP 14 requires no more than one announce per torrent every 5 minutes).
 *
 * Everything here is fail-soft: if multicast is unavailable (restricted container, no interface, firewall) the
 * client logs and stays disabled rather than failing the download.
 */
public final class LocalServiceDiscovery implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LocalServiceDiscovery.class.getName());

    /** BEP 14 multicast endpoints. */
    public static final String IPV4_GROUP = "239.192.152.143";
    public static final String IPV6_GROUP = "ff15::efc0:988f";
    public static final int PORT = 6771;

    /** BEP 14: do not announce the same torrent more often than once every 5 minutes. */
    static final long ANNOUNCE_INTERVAL_NANOS = 300_000_000_000L;

    /** Guard against absurd datagrams; a BT-SEARCH is a few hundred bytes. */
    private static final int MAX_DATAGRAM = 2048;

    private final int listenPort;
    private final String cookie;
    private final MulticastSocket socket;
    private final InetAddress groupV4;
    private final List<NetworkInterface> interfaces;
    private final Map<InfoHash, Registration> registrations = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private record Registration(Consumer<List<PeerAddress>> onPeersFound, java.util.concurrent.atomic.AtomicLong lastAnnounceNanos) {
    }

    /**
     * @param listenPort the TCP port this client accepts peer connections on (advertised in announces)
     * @throws IOException if the multicast socket cannot be opened or no usable interface exists
     */
    public LocalServiceDiscovery(int listenPort) throws IOException {
        this.listenPort = listenPort;
        this.cookie = Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        this.groupV4 = InetAddress.getByName(IPV4_GROUP);
        this.interfaces = multicastInterfaces();
        if (interfaces.isEmpty()) {
            throw new IOException("no multicast-capable network interface");
        }
        MulticastSocket s = new MulticastSocket(PORT);
        s.setReuseAddress(true);
        s.setTimeToLive(1); // stay on the local link
        boolean joined = false;
        for (NetworkInterface nif : interfaces) {
            try {
                s.joinGroup(new InetSocketAddress(groupV4, PORT), nif);
                joined = true;
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "LSD: could not join group on " + nif.getName() + ": " + e.getMessage());
            }
        }
        if (!joined) {
            s.close();
            throw new IOException("could not join the LSD multicast group on any interface");
        }
        this.socket = s;
        Thread.ofVirtual().name("bt4j-lsd-recv").start(this::receiveLoop);
        LOG.log(System.Logger.Level.DEBUG, () -> "LSD listening on " + IPV4_GROUP + ":" + PORT);
    }

    private static List<NetworkInterface> multicastInterfaces() throws IOException {
        List<NetworkInterface> usable = new ArrayList<>();
        for (NetworkInterface nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                if (nif.isUp() && nif.supportsMulticast() && !nif.isLoopback()) {
                    usable.add(nif);
                }
            } catch (IOException ignored) {
                // interface disappeared mid-enumeration
            }
        }
        return usable;
    }

    /** Registers a torrent for discovery. The callback receives peers seen announcing that info-hash. */
    public void register(InfoHash infoHash, Consumer<List<PeerAddress>> onPeersFound) {
        registrations.putIfAbsent(infoHash, new Registration(onPeersFound, new java.util.concurrent.atomic.AtomicLong()));
    }

    public void unregister(InfoHash infoHash) {
        registrations.remove(infoHash);
    }

    /**
     * Multicasts a BT-SEARCH for the given torrent, unless it was announced within the last
     * {@link #ANNOUNCE_INTERVAL_NANOS}. Safe to call often; it self-throttles.
     */
    public void announce(InfoHash infoHash) {
        Registration registration = registrations.get(infoHash);
        if (registration == null || closed) {
            return;
        }
        long now = System.nanoTime();
        long last = registration.lastAnnounceNanos().get();
        if (last != 0 && now - last < ANNOUNCE_INTERVAL_NANOS) {
            return;
        }
        if (!registration.lastAnnounceNanos().compareAndSet(last, now)) {
            return; // another thread is announcing this torrent right now
        }
        byte[] payload = buildAnnounce(infoHash, listenPort, cookie).getBytes(StandardCharsets.ISO_8859_1);
        for (NetworkInterface nif : interfaces) {
            try {
                socket.setNetworkInterface(nif);
                socket.send(new DatagramPacket(payload, payload.length, groupV4, PORT));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "LSD announce failed on " + nif.getName() + ": " + e.getMessage());
            }
        }
        LOG.log(System.Logger.Level.DEBUG, () -> "LSD announced " + infoHash.hex());
    }

    /** Builds the BT-SEARCH datagram (BEP 14 wire format). */
    static String buildAnnounce(InfoHash infoHash, int port, String cookie) {
        return "BT-SEARCH * HTTP/1.1\r\n"
                + "Host: " + IPV4_GROUP + ":" + PORT + "\r\n"
                + "Port: " + port + "\r\n"
                + "Infohash: " + infoHash.hex() + "\r\n"
                + "cookie: " + cookie + "\r\n"
                + "\r\n\r\n";
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(System.Logger.Level.DEBUG, () -> "LSD receive stopped: " + e.getMessage());
                }
                return;
            }
            try {
                handle(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.ISO_8859_1),
                        packet.getSocketAddress());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.TRACE, () -> "LSD: malformed datagram ignored");
            }
        }
    }

    private void handle(String message, SocketAddress from) {
        Announce announce = parse(message);
        if (announce == null || cookie.equals(announce.cookie())) {
            return; // malformed, or our own announce echoed back to us
        }
        if (!(from instanceof InetSocketAddress sender)) {
            return;
        }
        for (String hex : announce.infoHashes()) {
            InfoHash hash;
            try {
                hash = InfoHash.fromHex(hex);
            } catch (RuntimeException e) {
                continue;
            }
            Registration registration = registrations.get(hash);
            if (registration == null) {
                continue; // not a torrent we are running
            }
            PeerAddress peer = new PeerAddress(new InetSocketAddress(sender.getAddress(), announce.port()));
            LOG.log(System.Logger.Level.DEBUG, () -> "LSD discovered LAN peer " + peer + " for " + hash.hex());
            registration.onPeersFound().accept(List.of(peer));
        }
    }

    /** A parsed BT-SEARCH. A single announce may carry several Infohash headers. */
    record Announce(int port, List<String> infoHashes, String cookie) {
    }

    /** Parses a BT-SEARCH datagram; returns null if it is not a well-formed announce. */
    static Announce parse(String message) {
        if (!message.regionMatches(true, 0, "BT-SEARCH", 0, "BT-SEARCH".length())) {
            return null;
        }
        int port = -1;
        String cookie = null;
        List<String> hashes = new ArrayList<>();
        for (String line : message.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (name) {
                case "port" -> {
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                case "infohash" -> {
                    if (value.length() == 40) {
                        hashes.add(value);
                    }
                }
                case "cookie" -> cookie = value;
                default -> {
                    // Host and anything else is not needed
                }
            }
        }
        if (port <= 0 || port > 65535 || hashes.isEmpty()) {
            return null;
        }
        return new Announce(port, List.copyOf(hashes), cookie);
    }

    @Override
    public void close() {
        closed = true;
        registrations.clear();
        socket.close();
    }
}
