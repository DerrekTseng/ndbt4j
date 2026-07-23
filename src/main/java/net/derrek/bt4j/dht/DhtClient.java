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
 * DHT 客戶端（BEP 5）：單一 DatagramSocket 上的 KRPC，
 * 全域一個實例，所有 torrent 共用。
 * 對外能力：找 peer（迭代 get_peers）、宣告自己是 peer（announce_peer）；
 * 同時回應他人的 ping / find_node / get_peers / announce_peer（好公民）。
 */
public final class DhtClient implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DhtClient.class.getName());

    /**
     * 預設 bootstrap 節點（僅冷啟動時使用；路由表應隨 resume 資料持久化，
     * 重啟後以既有節點暖機，降低對公共節點的依賴）。
     * 全部以 unresolved 建立，實際解析延後到啟動時（DNS 失敗個別忽略）。
     */
    public static final List<InetSocketAddress> DEFAULT_BOOTSTRAP_NODES = List.of(
            InetSocketAddress.createUnresolved("router.bittorrent.com", 6881),
            InetSocketAddress.createUnresolved("router.utorrent.com", 6881),
            InetSocketAddress.createUnresolved("dht.transmissionbt.com", 6881),
            InetSocketAddress.createUnresolved("dht.libtorrent.org", 25401),
            InetSocketAddress.createUnresolved("router.bitcomet.com", 6881));

    private static final int ALPHA = 3;                       // 每輪平行查詢數
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_LOOKUP_ROUNDS = 16;
    private static final int ENOUGH_PEERS = 100;              // 單次 lookup 收集上限
    private static final int MAX_STORED_HASHES = 1000;        // server 端 peer store 防灌爆
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

    /** @param udpPort 0 = 由系統指派 */
    public DhtClient(int udpPort, List<InetSocketAddress> bootstrapNodes) {
        this.requestedUdpPort = udpPort;
        this.bootstrapNodes = List.copyOf(bootstrapNodes);
    }

    /** 啟動：綁定 socket、開始收包、背景對 bootstrap 節點填充路由表。 */
    public void start() {
        try {
            this.socket = new DatagramSocket(requestedUdpPort);
        } catch (SocketException e) {
            throw new UncheckedIOException(new IOException("DHT UDP port 綁定失敗: " + requestedUdpPort, e));
        }
        Thread.ofVirtual().name("bt4j-dht-recv").start(this::receiveLoop);
        Thread.ofVirtual().name("bt4j-dht-bootstrap").start(this::bootstrap);
    }

    /** 實際綁定的 UDP port（start 之後有效）。 */
    public int port() {
        return socket.getLocalPort();
    }

    public NodeId nodeId() {
        return self;
    }

    /** 路由表目前的節點數（診斷用）。 */
    public int routingTableSize() {
        return table.size();
    }

    /** 等待 bootstrap 完成（首輪填表結束；不保證表非空）。 */
    public boolean awaitBootstrap(Duration timeout) throws InterruptedException {
        return bootstrapped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void bootstrap() {
        try {
            // 以自身 id 做一次 lookup：對 bootstrap 節點 get_peers，回應者依序填入路由表
            lookup(new InfoHash(self.bytes()), null);
        } catch (RuntimeException ignored) {
            // 離線環境：表維持空，之後的 lookup 仍會嘗試 bootstrap 位址
        } finally {
            bootstrapped.countDown();
            LOG.log(System.Logger.Level.DEBUG, () -> "DHT bootstrap 完成，路由表 " + table.size() + " 個節點");
        }
    }

    /**
     * 迭代 get_peers 查詢。future 在查詢收斂時完成；找不到以空清單完成（不以例外表示死種）。
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

    /** 對負責節點 announce_peer，宣告本機在 tcpPort 提供此 torrent。 */
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

    /** 把一個候選節點（例如 peer 的 PORT 訊息）ping 進路由表。 */
    public void addNode(InetSocketAddress address) {
        query(address, "ping", new LinkedHashMap<>()); // 回應時 receiveLoop 會自動入表
    }

    // ---- 迭代查詢 ----

    private record Candidate(InetSocketAddress address, NodeId id) {
    }

    /** 同步迭代查詢。announceTcpPort 非 null 時，收斂後對最近的持 token 節點 announce_peer。 */
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
                    continue; // 逾時或錯誤：略過此節點
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
        LOG.log(System.Logger.Level.DEBUG, () -> "DHT lookup " + target.hex() + " 找到 " + peers.size() + " 個 peer"
                + (announceTcpPort != null ? "（並 announce port=" + announceTcpPort + "）" : ""));
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

    // ---- KRPC 收發 ----

    /** 送出 query（自動附上本機 id），回傳的 future 於回應/錯誤/逾時時完成。 */
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
            socket.send(new DatagramPacket(packet, packet.length, to));
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
            try {
                KrpcMessage message = KrpcMessage.decode(Arrays.copyOf(buffer, packet.getLength()));
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                switch (message) {
                    case KrpcMessage.Response(byte[] t, BValue.BDictionary r) -> {
                        if (r.get("id").orElse(null) instanceof BValue.BString(byte[] id) && id.length == 20) {
                            table.insert(new DhtNode(new NodeId(id), from)); // 回應過 = 活節點
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
                // 格式錯誤的封包：忽略
            }
        }
    }

    // ---- server 端 ----

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
            socket.send(new DatagramPacket(data, data.length, to));
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

    /** token = SHA1(secret || 5分鐘紀元 || 來源 IP)；epochOffset -1 = 上一紀元（換發期仍有效）。 */
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
            throw new AssertionError("JDK 必定內建 SHA-1", e);
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
