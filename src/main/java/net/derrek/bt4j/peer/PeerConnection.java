package net.derrek.bt4j.peer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.derrek.bt4j.metainfo.InfoHash;
import net.derrek.bt4j.piece.Bitfield;

/**
 * 一條 peer TCP 連線的生命週期：handshake、訊息編解碼、四態旗標
 * （am_choking / am_interested / peer_choking / peer_interested）。
 *
 * 執行緒模型：每條連線兩條 virtual thread——讀迴圈（依序處理訊息並回呼 listener）
 * 與寫佇列（閒置 {@value #KEEP_ALIVE_IDLE_SECONDS} 秒自動送 keep-alive）。
 * {@link Listener} 回呼一律在讀迴圈 thread 上執行。
 */
public final class PeerConnection implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 150_000;
    private static final int KEEP_ALIVE_IDLE_SECONDS = 110;

    /** 連線事件回呼。由讀迴圈 thread 呼叫，實作不可長時間阻塞。 */
    public interface Listener {

        void onHandshakeCompleted(PeerConnection connection, Handshake theirs);

        void onMessage(PeerConnection connection, PeerMessage message);

        /** 連線關閉（正常或錯誤）。error 為 null 表示正常關閉。 */
        void onClosed(PeerConnection connection, IOException error);
    }

    private final PeerAddress address;
    private final InfoHash infoHash;
    private final PeerId localId;
    private final int pieceCount;
    private final boolean advertiseDht;
    private final Listener listener;
    private final BlockingQueue<PeerMessage> sendQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Socket socket;
    private volatile Thread writeThread;
    private volatile boolean amChoking = true;
    private volatile boolean amInterested = false;
    private volatile boolean peerChoking = true;
    private volatile boolean peerInterested = false;
    private volatile Handshake theirHandshake;
    private Bitfield peerBitfield; // 只在讀迴圈 thread 寫入

    private PeerConnection(PeerAddress address, InfoHash infoHash, PeerId localId, int pieceCount,
                           boolean advertiseDht, Listener listener) {
        this.address = address;
        this.infoHash = infoHash;
        this.localId = localId;
        this.pieceCount = pieceCount;
        this.advertiseDht = advertiseDht;
        this.listener = listener;
        // pieceCount <= 0：magnet 情境 metadata 未知，先給最小 bitfield（bitfield 訊息到達時取代）
        this.peerBitfield = new Bitfield(Math.max(1, pieceCount));
    }

    /** 主動連出。建構後呼叫 {@link #start()} 才開始 IO。 */
    public static PeerConnection outgoing(PeerAddress address, InfoHash infoHash, PeerId localId,
                                          int pieceCount, boolean advertiseDht, Listener listener) {
        return new PeerConnection(address, infoHash, localId, pieceCount, advertiseDht, listener);
    }

    /** 被動連入（M9 實作：先讀對方 handshake 以決定所屬 torrent）。 */
    public static PeerConnection incoming(Socket socket, Listener listener) {
        throw new UnsupportedOperationException("尚未實作（M9）");
    }

    /** 啟動讀寫 virtual thread、進行 handshake。立即回傳。 */
    public void start() {
        Thread.ofVirtual().name("bt4j-peer-" + address).start(this::runRead);
    }

    private void runRead() {
        try {
            Socket s = new Socket();
            this.socket = s;
            if (closed.get()) {
                s.close();
                return;
            }
            s.connect(resolve(address.socketAddress()), CONNECT_TIMEOUT_MILLIS);
            s.setSoTimeout(READ_TIMEOUT_MILLIS);
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            out.write(Handshake.outgoing(infoHash, localId, advertiseDht, true, false).encode());
            out.flush();
            byte[] response = in.readNBytes(Handshake.LENGTH);
            if (response.length != Handshake.LENGTH) {
                throw new IOException("handshake 未完成即斷線");
            }
            Handshake theirs;
            try {
                theirs = Handshake.decode(response);
            } catch (IllegalArgumentException e) {
                throw new IOException("handshake 格式錯誤", e);
            }
            if (!theirs.infoHash().equals(infoHash)) {
                throw new IOException("對方 info-hash 不符");
            }
            this.theirHandshake = theirs;
            this.writeThread = Thread.ofVirtual().name("bt4j-peer-write-" + address).start(() -> runWrite(out));
            listener.onHandshakeCompleted(this, theirs);

            while (!closed.get()) {
                PeerMessage message = PeerMessage.read(in, pieceCount);
                handleInternal(message);
                listener.onMessage(this, message);
            }
        } catch (IOException e) {
            closeInternal(closed.get() ? null : e);
        }
    }

    /** 讀端內部狀態更新（listener 回呼前先套用，讓回呼看到最新狀態）。 */
    private void handleInternal(PeerMessage message) {
        switch (message) {
            case PeerMessage.Choke() -> peerChoking = true;
            case PeerMessage.Unchoke() -> peerChoking = false;
            case PeerMessage.Interested() -> peerInterested = true;
            case PeerMessage.NotInterested() -> peerInterested = false;
            case PeerMessage.Have(int piece) -> {
                if (piece >= 0 && piece < peerBitfield.pieceCount()) {
                    peerBitfield.set(piece);
                }
                // 超出範圍：metadata 未知階段的 bitfield 尚未到達，忽略
            }
            case PeerMessage.BitfieldMessage(Bitfield bf) -> peerBitfield = bf.copy();
            case PeerMessage.HaveAll() -> peerBitfield.setAll();
            case PeerMessage.HaveNone() -> peerBitfield = new Bitfield(Math.max(1, pieceCount));
            default -> {
            }
        }
    }

    private void runWrite(DataOutputStream out) {
        try {
            while (!closed.get()) {
                PeerMessage message = sendQueue.poll(KEEP_ALIVE_IDLE_SECONDS, TimeUnit.SECONDS);
                if (closed.get()) {
                    return;
                }
                if (message == null) {
                    message = new PeerMessage.KeepAlive();
                }
                switch (message) {
                    case PeerMessage.Choke() -> amChoking = true;
                    case PeerMessage.Unchoke() -> amChoking = false;
                    case PeerMessage.Interested() -> amInterested = true;
                    case PeerMessage.NotInterested() -> amInterested = false;
                    default -> {
                    }
                }
                PeerMessage.write(out, message);
            }
        } catch (IOException e) {
            closeInternal(closed.get() ? null : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static InetSocketAddress resolve(InetSocketAddress address) {
        return address.isUnresolved()
                ? new InetSocketAddress(address.getHostString(), address.getPort())
                : address;
    }

    /** 非同步送出訊息（進寫佇列）。連線已關閉時靜默忽略。 */
    public void send(PeerMessage message) {
        if (!closed.get()) {
            sendQueue.offer(message);
        }
    }

    public PeerAddress address() {
        return address;
    }

    /** 對方的 handshake（完成前為 null）。 */
    public Handshake theirHandshake() {
        return theirHandshake;
    }

    /**
     * 對方宣告的 piece 持有狀態（bitfield + have 累積）。
     * 只保證在 listener 回呼（讀迴圈 thread）內讀取的一致性。
     */
    public Bitfield peerBitfield() {
        return peerBitfield;
    }

    public boolean amChoking() {
        return amChoking;
    }

    public boolean amInterested() {
        return amInterested;
    }

    public boolean peerChoking() {
        return peerChoking;
    }

    public boolean peerInterested() {
        return peerInterested;
    }

    @Override
    public void close() {
        closeInternal(null);
    }

    private void closeInternal(IOException error) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        Thread w = writeThread;
        if (w != null) {
            w.interrupt();
        }
        listener.onClosed(this, error);
    }

    @Override
    public String toString() {
        return "PeerConnection[" + address + "]";
    }
}
