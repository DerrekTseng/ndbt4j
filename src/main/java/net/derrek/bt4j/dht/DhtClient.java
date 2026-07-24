package net.derrek.bt4j.dht;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.derrek.bt4j.bencode.BValue;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * DHT client (BEP 5): KRPC over a single DatagramSocket,
 * one global instance shared by all torrents.
 * Outbound capabilities: find peers (iterative get_peers), announce self as a peer (announce_peer);
 * also responds to others' ping / find_node / get_peers / announce_peer (good citizen).
 */
public final class DhtClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DhtClient.class.getName());

    /**
     * Default bootstrap nodes (used only on a cold start; the routing table should be persisted
     * with resume data so a restart can warm up from existing nodes, reducing reliance on public nodes).
     * All created as unresolved; actual resolution is deferred to startup (individual DNS failures ignored).
     */
    public static final List<InetSocketAddress> DEFAULT_BOOTSTRAP_NODES = List.of(
            InetSocketAddress.createUnresolved("router.bittorrent.com", 6881),
            InetSocketAddress.createUnresolved("router.utorrent.com", 6881),
            InetSocketAddress.createUnresolved("dht.transmissionbt.com", 6881),
            InetSocketAddress.createUnresolved("dht.libtorrent.org", 25401),
            InetSocketAddress.createUnresolved("router.bitcomet.com", 6881));

    private static final int ALPHA = 3;                       // parallel queries per round
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_LOOKUP_ROUNDS = 16;
    private static final int ENOUGH_PEERS = 100;              // collection cap for a single lookup
    private static final int MAX_STORED_HASHES = 1000;        // server-side peer store flood protection
    private static final int MAX_STORED_PEERS_PER_HASH = 300;
    private static final long TOKEN_EPOCH_MILLIS = 5 * 60_000;

    private final NodeId self = NodeId.random();
    private final int requestedUdpPort;
    private final List<InetSocketAddress> bootstrapNodes;
    private final RoutingTable table = new RoutingTable(self);
    private final Map<String, CompletableFuture<BValue.BDictionary>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger transactionCounter = new AtomicInteger();
    private final Map<InfoHash, Set<PeerAddress>> peerStore = new ConcurrentHashMap<>();
    private final CountDownLatch bootstrapped = new CountDownLatch(1);
    private final byte[] tokenSecret = NodeId.random().bytes();

    private volatile DatagramSocket socket;
    private volatile boolean closed;

    /** Sends a KRPC datagram. In shared mode this is a {@code UdpMux}; standalone it is the owned socket. */
    public interface Sender {
        void send(byte[] data, java.net.SocketAddress to) throws IOException;
    }

    private final Sender externalSender; // non-null in shared mode: the socket is owned by a UdpMux
    private final int externalPort;

    /** @param udpPort 0 = assigned by the system */
    public DhtClient(int udpPort, List<InetSocketAddress> bootstrapNodes) {
        this.requestedUdpPort = udpPort;
        this.bootstrapNodes = List.copyOf(bootstrapNodes);
        this.externalSender = null;
        this.externalPort = 0;
    }

    /**
     * Shared-socket mode: KRPC is sent through {@code sender} and inbound packets are fed via {@link #onDatagram}
     * (by a {@code UdpMux} that also serves uTP on the same UDP port). No socket of its own is bound.
     */
    public DhtClient(int boundPort, List<InetSocketAddress> bootstrapNodes, Sender sender) {
        this.requestedUdpPort = boundPort;
        this.bootstrapNodes = List.copyOf(bootstrapNodes);
        this.externalSender = sender;
        this.externalPort = boundPort;
    }

    /** Start: bind the socket, begin receiving packets, and populate the routing table from bootstrap nodes in the background. */
    public void start() {
        if (externalSender == null) {
            try {
                this.socket = new DatagramSocket(requestedUdpPort);
            } catch (SocketException e) {
                throw new UncheckedIOException(new IOException("failed to bind DHT UDP port: " + requestedUdpPort, e));
            }
            Thread.ofVirtual().name("bt4j-dht-recv").start(this::receiveLoop);
        }
        Thread.ofVirtual().name("bt4j-dht-bootstrap").start(this::bootstrap);
    }

    /** The actually bound UDP port (valid after start). */
    public int port() {
        return externalSender != null ? externalPort : socket.getLocalPort();
    }

    /** Transmits a datagram through the owned socket or the shared sender. */
    private void transmit(byte[] data, java.net.SocketAddress to) throws IOException {
        if (externalSender != null) {
            externalSender.send(data, to);
        } else {
            socket.send(new DatagramPacket(data, data.length, to));
        }
    }

    /** Feeds an inbound KRPC datagram (called by a {@code UdpMux} in shared mode). */
    public void onDatagram(byte[] data, int length, InetSocketAddress from) {
        handleDatagram(Arrays.copyOf(data, length), from);
    }

    public NodeId nodeId() {
        return self;
    }

    /** Current number of nodes in the routing table (for diagnostics). */
    public int routingTableSize() {
        return table.size();
    }

    /** Wait for bootstrap to finish (end of the first fill round; does not guarantee a non-empty table). */
    public boolean awaitBootstrap(Duration timeout) throws InterruptedException {
        return bootstrapped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void bootstrap() {
        try {
            // Do one lookup on our own id: get_peers against bootstrap nodes, responders fill the routing table in order
            lookup(new InfoHash(self.bytes()), null);
        } catch (RuntimeException ignored) {
            // Offline environment: the table stays empty; later lookups still try the bootstrap addresses
        } finally {
            bootstrapped.countDown();
            LOG.log(System.Logger.Level.DEBUG, () -> "DHT bootstrap done, routing table has " + table.size() + " nodes");
        }
    }

    /**
     * Iterative get_peers query. The future completes when the query converges; if none are found it completes
     * with an empty list (a dead torrent is not signaled by an exception).
     */
    public CompletableFuture<List<PeerAddress>> findPeers(InfoHash infoHash) {
        CompletableFuture<List<PeerAddress>> future = new CompletableFuture<>();
        Thread.ofVirtual().name("bt4j-dht-lookup").start(() -> {
            try {
                future.complete(lookup(infoHash, null));
            } catch (RuntimeException e) {
                future.complete(List.of());
            }
        });
        return future;
    }

    /** announce_peer to the responsible nodes, declaring that this host serves this torrent on tcpPort. */
    public CompletableFuture<Void> announce(InfoHash infoHash, int tcpPort) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.ofVirtual().name("bt4j-dht-announce").start(() -> {
            try {
                lookup(infoHash, tcpPort);
            } catch (RuntimeException ignored) {
            }
            future.complete(null);
        });
        return future;
    }

    /** ping a candidate node (e.g. from a peer's PORT message) into the routing table. */
    public void addNode(InetSocketAddress address) {
        query(address, "ping", new LinkedHashMap<>()); // on reply, receiveLoop inserts it into the table automatically
    }

    // ---- iterative lookup ----

    private record Candidate(InetSocketAddress address, NodeId id) {
    }

    /** Synchronous iterative lookup. When announceTcpPort is non-null, after convergence announce_peer to the closest token-holding nodes. */
    private List<PeerAddress> lookup(InfoHash target, Integer announceTcpPort) {
        NodeId targetId = NodeId.of(target);
        Comparator<Candidate> byDistance = (a, b) -> {
            if (a.id() == null || b.id() == null) {
                return a.id() == null ? (b.id() == null ? 0 : 1) : -1;
            }
            return targetId.compareDistance(a.id(), b.id());
        };

        Map<InetSocketAddress, Candidate> candidates = new LinkedHashMap<>();
        for (DhtNode node : table.closest(targetId, RoutingTable.K * 2)) {
            candidates.put(node.address(), new Candidate(node.address(), node.id()));
        }
        for (InetSocketAddress node : bootstrapNodes) {
            InetSocketAddress resolved = node.isUnresolved()
                    ? new InetSocketAddress(node.getHostString(), node.getPort())
                    : node;
            if (!resolved.isUnresolved()) {
                candidates.putIfAbsent(resolved, new Candidate(resolved, null));
            }
        }

        Set<InetSocketAddress> queried = new HashSet<>();
        LinkedHashSet<PeerAddress> peers = new LinkedHashSet<>();
        Map<InetSocketAddress, byte[]> tokens = new HashMap<>();
        Map<InetSocketAddress, NodeId> responded = new LinkedHashMap<>();

        for (int round = 0; round < MAX_LOOKUP_ROUNDS && peers.size() < ENOUGH_PEERS; round++) {
            List<Candidate> batch = candidates.values().stream()
                    .filter(c -> !queried.contains(c.address()))
                    .sorted(byDistance)
                    .limit(ALPHA)
                    .toList();
            if (batch.isEmpty()) {
                break;
            }
            List<CompletableFuture<BValue.BDictionary>> futures = new ArrayList<>();
            for (Candidate candidate : batch) {
                queried.add(candidate.address());
                SequencedMap<BValue.BString, BValue> args = new LinkedHashMap<>();
                args.put(BValue.BString.of("info_hash"), new BValue.BString(target.bytes()));
                futures.add(query(candidate.address(), "get_peers", args));
            }
            for (int i = 0; i < futures.size(); i++) {
                BValue.BDictionary r;
                try {
                    r = futures.get(i).join();
                } catch (RuntimeException e) {
                    continue; // timeout or error: skip this node
                }
                InetSocketAddress from = batch.get(i).address();
                if (r.get("id").orElse(null) instanceof BValue.BString(byte[] id) && id.length == 20) {
                    responded.put(from, new NodeId(id));
                }
                if (r.get("token").orElse(null) instanceof BValue.BString(byte[] token)) {
                    tokens.put(from, token);
                }
                if (r.get("values").orElse(null) instanceof BValue.BList values) {
                    for (BValue value : values.values()) {
                        if (value instanceof BValue.BString(byte[] compact)) {
                            try {
                                peers.addAll(PeerAddress.fromCompact(compact));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
                if (r.get("nodes").orElse(null) instanceof BValue.BString(byte[] compactNodes)) {
                    for (DhtNode node : DhtNode.fromCompact(compactNodes)) {
                        candidates.putIfAbsent(node.address(), new Candidate(node.address(), node.id()));
                    }
                }
            }
        }

        if (announceTcpPort != null) {
            announceToClosest(target, targetId, announceTcpPort, responded, tokens);
        }
        LOG.log(System.Logger.Level.DEBUG, () -> "DHT lookup " + target.hex() + " found " + peers.size() + " peers"
                + (announceTcpPort != null ? " (and announced port=" + announceTcpPort + ")" : ""));
        return List.copyOf(peers);
    }

    private void announceToClosest(InfoHash target, NodeId targetId, int tcpPort,
                                   Map<InetSocketAddress, NodeId> responded,
                                   Map<InetSocketAddress, byte[]> tokens) {
        List<Map.Entry<InetSocketAddress, NodeId>> closest = responded.entrySet().stream()
                .filter(e -> tokens.containsKey(e.getKey()))
                .sorted((a, b) -> targetId.compareDistance(a.getValue(), b.getValue()))
                .limit(RoutingTable.K)
                .toList();
        List<CompletableFuture<BValue.BDictionary>> futures = new ArrayList<>();
        for (Map.Entry<InetSocketAddress, NodeId> entry : closest) {
            SequencedMap<BValue.BString, BValue> args = new LinkedHashMap<>();
            args.put(BValue.BString.of("implied_port"), new BValue.BInteger(0));
            args.put(BValue.BString.of("info_hash"), new BValue.BString(target.bytes()));
            args.put(BValue.BString.of("port"), new BValue.BInteger(tcpPort));
            args.put(BValue.BString.of("token"), new BValue.BString(tokens.get(entry.getKey())));
            futures.add(query(entry.getKey(), "announce_peer", args));
        }
        for (CompletableFuture<BValue.BDictionary> future : futures) {
            try {
                future.join();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- KRPC send/receive ----

    /** Send a query (our own id attached automatically); the returned future completes on response/error/timeout. */
    private CompletableFuture<BValue.BDictionary> query(InetSocketAddress to, String method,
                                                        SequencedMap<BValue.BString, BValue> args) {
        int n = transactionCounter.incrementAndGet();
        byte[] transactionId = {(byte) (n >> 8), (byte) n};
        String key = HexFormat.of().formatHex(transactionId);
        args.put(BValue.BString.of("id"), new BValue.BString(self.bytes()));

        CompletableFuture<BValue.BDictionary> future = new CompletableFuture<>();
        pending.put(key, future);
        byte[] packet = KrpcMessage.encode(new KrpcMessage.Query(
                transactionId, method, new BValue.BDictionary(args)));
        try {
            transmit(packet, to);
        } catch (IOException | IllegalArgumentException e) {
            pending.remove(key);
            future.completeExceptionally(e);
            return future;
        }
        return future.orTimeout(QUERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> pending.remove(key));
    }

    private void receiveLoop() {
        byte[] buffer = new byte[4096];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                continue;
            }
            handleDatagram(Arrays.copyOf(buffer, packet.getLength()), (InetSocketAddress) packet.getSocketAddress());
        }
    }

    private void handleDatagram(byte[] data, InetSocketAddress from) {
        if (closed) {
            return;
        }
        try {
            KrpcMessage message = KrpcMessage.decode(data);
            switch (message) {
                case KrpcMessage.Response(byte[] t, BValue.BDictionary r) -> {
                    if (r.get("id").orElse(null) instanceof BValue.BString(byte[] id) && id.length == 20) {
                        table.insert(new DhtNode(new NodeId(id), from)); // has responded = live node
                    }
                    CompletableFuture<BValue.BDictionary> future = pending.remove(HexFormat.of().formatHex(t));
                    if (future != null) {
                        future.complete(r);
                    }
                }
                case KrpcMessage.Error(byte[] t, int code, String errorMessage) -> {
                    CompletableFuture<BValue.BDictionary> future = pending.remove(HexFormat.of().formatHex(t));
                    if (future != null) {
                        future.completeExceptionally(new IOException("KRPC error " + code + ": " + errorMessage));
                    }
                }
                case KrpcMessage.Query query -> handleQuery(query, from);
            }
        } catch (RuntimeException ignored) {
            // malformed packet: ignore
        }
    }

    // ---- server side ----

    private void handleQuery(KrpcMessage.Query query, InetSocketAddress from) {
        BValue.BDictionary args = query.arguments();
        if (args.get("id").orElse(null) instanceof BValue.BString(byte[] id) && id.length == 20) {
            table.insert(new DhtNode(new NodeId(id), from));
        }
        switch (query.method()) {
            case "ping" -> respond(query, from, values());
            case "find_node" -> {
                if (args.get("target").orElse(null) instanceof BValue.BString(byte[] t) && t.length == 20) {
                    respond(query, from, values("nodes",
                            new BValue.BString(DhtNode.toCompact(table.closest(new NodeId(t), RoutingTable.K)))));
                } else {
                    respondError(query, from, 203, "missing target");
                }
            }
            case "get_peers" -> {
                if (!(args.get("info_hash").orElse(null) instanceof BValue.BString(byte[] h) && h.length == 20)) {
                    respondError(query, from, 203, "missing info_hash");
                    return;
                }
                InfoHash infoHash = new InfoHash(h);
                SequencedMap<BValue.BString, BValue> r = values("token", new BValue.BString(makeToken(from, 0)));
                Set<PeerAddress> stored = peerStore.get(infoHash);
                if (stored != null && !stored.isEmpty()) {
                    List<BValue> compactPeers = new ArrayList<>();
                    for (PeerAddress peer : stored) {
                        byte[] compact = toCompact(peer);
                        if (compact != null) {
                            compactPeers.add(new BValue.BString(compact));
                        }
                    }
                    r.put(BValue.BString.of("values"), new BValue.BList(List.copyOf(compactPeers)));
                } else {
                    r.put(BValue.BString.of("nodes"),
                            new BValue.BString(DhtNode.toCompact(table.closest(NodeId.of(infoHash), RoutingTable.K))));
                }
                respond(query, from, r);
            }
            case "announce_peer" -> {
                boolean tokenOk = args.get("token").orElse(null) instanceof BValue.BString(byte[] token)
                        && (Arrays.equals(token, makeToken(from, 0)) || Arrays.equals(token, makeToken(from, -1)));
                if (!tokenOk) {
                    respondError(query, from, 203, "bad token");
                    return;
                }
                if (args.get("info_hash").orElse(null) instanceof BValue.BString(byte[] h) && h.length == 20
                        && args.get("port").orElse(null) instanceof BValue.BInteger(long port)) {
                    boolean impliedPort = args.get("implied_port").orElse(null) instanceof BValue.BInteger(long ip) && ip == 1;
                    int peerPort = impliedPort ? from.getPort() : (int) port;
                    if (peerPort >= 1 && peerPort <= 65535) {
                        storePeer(new InfoHash(h), new PeerAddress(new InetSocketAddress(from.getAddress(), peerPort)));
                    }
                    respond(query, from, values());
                } else {
                    respondError(query, from, 203, "missing info_hash/port");
                }
            }
            default -> respondError(query, from, 204, "Method Unknown");
        }
    }

    private void storePeer(InfoHash infoHash, PeerAddress peer) {
        if (!peerStore.containsKey(infoHash) && peerStore.size() >= MAX_STORED_HASHES) {
            return;
        }
        Set<PeerAddress> set = peerStore.computeIfAbsent(infoHash, h -> ConcurrentHashMap.newKeySet());
        if (set.size() < MAX_STORED_PEERS_PER_HASH) {
            set.add(peer);
        }
    }

    private void respond(KrpcMessage.Query query, InetSocketAddress to, SequencedMap<BValue.BString, BValue> r) {
        r.putFirst(BValue.BString.of("id"), new BValue.BString(self.bytes()));
        sendRaw(KrpcMessage.encode(new KrpcMessage.Response(query.transactionId(), new BValue.BDictionary(r))), to);
    }

    private void respondError(KrpcMessage.Query query, InetSocketAddress to, int code, String message) {
        sendRaw(KrpcMessage.encode(new KrpcMessage.Error(query.transactionId(), code, message)), to);
    }

    private void sendRaw(byte[] data, InetSocketAddress to) {
        try {
            transmit(data, to);
        } catch (IOException ignored) {
        }
    }

    private static SequencedMap<BValue.BString, BValue> values(Object... keyValues) {
        SequencedMap<BValue.BString, BValue> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(BValue.BString.of((String) keyValues[i]), (BValue) keyValues[i + 1]);
        }
        return map;
    }

    /** token = SHA1(secret || 5-minute epoch || source IP); epochOffset -1 = previous epoch (still valid during rollover). */
    private byte[] makeToken(InetSocketAddress from, int epochOffset) {
        long epoch = System.currentTimeMillis() / TOKEN_EPOCH_MILLIS + epochOffset;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(tokenSecret);
            for (int i = 7; i >= 0; i--) {
                sha1.update((byte) (epoch >> (i * 8)));
            }
            sha1.update(from.getAddress().getAddress());
            return sha1.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 is always built into the JDK", e);
        }
    }

    private static byte[] toCompact(PeerAddress peer) {
        java.net.InetAddress address = peer.socketAddress().getAddress();
        if (address == null || address.getAddress().length != 4) {
            return null;
        }
        byte[] out = new byte[6];
        System.arraycopy(address.getAddress(), 0, out, 0, 4);
        out[4] = (byte) (peer.socketAddress().getPort() >> 8);
        out[5] = (byte) peer.socketAddress().getPort();
        return out;
    }

    @Override
    public void close() {
        closed = true;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
    }
}
