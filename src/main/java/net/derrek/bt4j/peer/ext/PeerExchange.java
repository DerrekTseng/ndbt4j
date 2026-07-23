package net.derrek.bt4j.peer.ext;

import java.util.List;
import java.util.function.Consumer;
import net.derrek.bt4j.peer.PeerAddress;

/**
 * ut_pex（BEP 11）：與已連線 peer 定期交換 peer 清單（added/dropped，compact 格式）。
 * 週期不得短於 60 秒。private torrent（BEP 27）時不得啟用。
 */
public final class PeerExchange implements Extension {

    public PeerExchange(Consumer<List<PeerAddress>> onPeersDiscovered) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public String name() {
        return "ut_pex";
    }

    @Override
    public void onExtensionHandshake(net.derrek.bt4j.peer.PeerConnection connection,
                                     net.derrek.bt4j.bencode.BValue.BDictionary handshake) {
        throw new UnsupportedOperationException("尚未實作");
    }

    @Override
    public void onMessage(net.derrek.bt4j.peer.PeerConnection connection, byte[] payload) {
        throw new UnsupportedOperationException("尚未實作");
    }
}
