package net.derrek.bt4j.nat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * NAT-PMP client (RFC 6886): asks the home gateway to forward an external TCP port to this host, so peers on the
 * internet can open incoming connections to a machine behind NAT.
 *
 * The wire codecs are pure static methods (unit-tested); the instance methods add the UDP exchange with the
 * gateway on port {@value #PORT}. Everything is best-effort — an unsupportive or absent gateway just yields an
 * empty result, never an exception out of {@link #queryExternalAddress}/{@link #map}.
 */
public final class NatPmpClient {

    private static final System.Logger LOG = System.getLogger(NatPmpClient.class.getName());

    /** NAT-PMP / PCP gateway UDP port. */
    public static final int PORT = 5351;
    static final int VERSION = 0;
    static final int OP_EXTERNAL = 0;
    static final int OP_MAP_UDP = 1;
    static final int OP_MAP_TCP = 2;
    static final int RESPONSE_FLAG = 128; // response opcode = request opcode + 128
    static final int RESULT_SUCCESS = 0;

    private static final int RECEIVE_TIMEOUT_MILLIS = 250;
    private static final int ATTEMPTS = 4; // RFC 6886 uses exponential backoff; a few short tries is enough here

    private final InetAddress gateway;

    public NatPmpClient(InetAddress gateway) {
        this.gateway = gateway;
    }

    // ---- wire codecs (pure) ----

    /** The 2-byte "external address request" datagram. */
    static byte[] externalAddressRequest() {
        return new byte[]{(byte) VERSION, (byte) OP_EXTERNAL};
    }

    /** The 12-byte port-mapping request datagram. A lifetime of 0 with external port 0 deletes the mapping. */
    static byte[] mapRequest(boolean tcp, int internalPort, int suggestedExternalPort, int lifetimeSeconds) {
        byte[] out = new byte[12];
        out[0] = (byte) VERSION;
        out[1] = (byte) (tcp ? OP_MAP_TCP : OP_MAP_UDP);
        // bytes 2-3 reserved (0)
        putShort(out, 4, internalPort);
        putShort(out, 6, suggestedExternalPort);
        putInt(out, 8, lifetimeSeconds);
        return out;
    }

    /** Result of an external-address query. */
    public record ExternalAddress(int resultCode, InetAddress address) {
        public boolean ok() {
            return resultCode == RESULT_SUCCESS && address != null;
        }
    }

    /** Result of a port-mapping request. */
    public record MapResult(int resultCode, int internalPort, int externalPort, int lifetimeSeconds) {
        public boolean ok() {
            return resultCode == RESULT_SUCCESS;
        }
    }

    static ExternalAddress parseExternalResponse(byte[] data, int length) {
        if (length < 12 || data[0] != VERSION || (data[1] & 0xFF) != (OP_EXTERNAL + RESPONSE_FLAG)) {
            throw new IllegalArgumentException("not a NAT-PMP external-address response");
        }
        int result = readShort(data, 2);
        byte[] ip = {data[8], data[9], data[10], data[11]};
        try {
            return new ExternalAddress(result, InetAddress.getByAddress(ip));
        } catch (IOException e) {
            return new ExternalAddress(result, null);
        }
    }

    static MapResult parseMapResponse(byte[] data, int length, boolean tcp) {
        int expectedOp = (tcp ? OP_MAP_TCP : OP_MAP_UDP) + RESPONSE_FLAG;
        if (length < 16 || data[0] != VERSION || (data[1] & 0xFF) != expectedOp) {
            throw new IllegalArgumentException("not a NAT-PMP mapping response for the requested protocol");
        }
        return new MapResult(readShort(data, 2), readShort(data, 8), readShort(data, 10), readInt(data, 12));
    }

    // ---- network exchange ----

    /** Queries the gateway's public IP address. Empty when the gateway does not answer or reports an error. */
    public Optional<InetAddress> queryExternalAddress() {
        return exchange(externalAddressRequest(), 12).map(r -> {
            try {
                ExternalAddress ext = parseExternalResponse(r, r.length);
                return ext.ok() ? ext.address() : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    /**
     * Requests a TCP mapping of {@code internalPort} to the same external port for {@code lifetimeSeconds}.
     *
     * @return the granted external port (may differ from the request), or empty if the gateway declined
     */
    public Optional<Integer> map(int internalPort, int lifetimeSeconds) {
        return exchange(mapRequest(true, internalPort, internalPort, lifetimeSeconds), 16).map(r -> {
            try {
                MapResult result = parseMapResponse(r, r.length, true);
                return result.ok() ? result.externalPort() : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    /** Removes the TCP mapping for {@code internalPort} (lifetime 0 per RFC 6886). Best-effort. */
    public void unmap(int internalPort) {
        exchange(mapRequest(true, internalPort, 0, 0), 16);
    }

    private Optional<byte[]> exchange(byte[] request, int minResponse) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
            byte[] buffer = new byte[16];
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                socket.send(new DatagramPacket(request, request.length, gateway, PORT));
                try {
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    if (response.getAddress().equals(gateway) && response.getLength() >= minResponse) {
                        byte[] out = new byte[response.getLength()];
                        System.arraycopy(buffer, 0, out, 0, out.length);
                        return Optional.of(out);
                    }
                } catch (SocketTimeoutException timeout) {
                    // retry
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "NAT-PMP exchange with " + gateway + " failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Best-effort guesses at the default gateway address: for each up, non-loopback IPv4 interface, the network
     * address with its host part set to 1 (e.g. 192.168.1.1) — the near-universal SOHO gateway convention. A
     * wrong guess simply times out, after which UPnP's SSDP discovery can still find the gateway on its own.
     */
    public static List<InetAddress> gatewayGuesses() {
        List<InetAddress> guesses = new ArrayList<>();
        try {
            for (NetworkInterface nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.isLoopback() || !nif.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof InetAddress addr) || addr.getAddress().length != 4) {
                        continue;
                    }
                    InetAddress guess = gatewayForPrefix(addr.getAddress(), ia.getNetworkPrefixLength());
                    if (guess != null && !guesses.contains(guess)) {
                        guesses.add(guess);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "could not enumerate interfaces for gateway guess: " + e.getMessage());
        }
        return guesses;
    }

    /** The ".1" host of the network containing {@code ipv4} for the given prefix length. Package-private for tests. */
    static InetAddress gatewayForPrefix(byte[] ipv4, int prefixLength) {
        if (ipv4.length != 4 || prefixLength <= 0 || prefixLength >= 31) {
            return null; // no room for a distinct host address
        }
        int addr = 0;
        for (byte b : ipv4) {
            addr = (addr << 8) | (b & 0xFF);
        }
        int mask = prefixLength == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefixLength));
        int gateway = (addr & mask) | 1; // network address + 1
        byte[] out = {(byte) (gateway >>> 24), (byte) (gateway >>> 16), (byte) (gateway >>> 8), (byte) gateway};
        try {
            return InetAddress.getByAddress(out);
        } catch (IOException e) {
            return null;
        }
    }

    private static void putShort(byte[] b, int off, int value) {
        b[off] = (byte) (value >>> 8);
        b[off + 1] = (byte) value;
    }

    private static void putInt(byte[] b, int off, int value) {
        b[off] = (byte) (value >>> 24);
        b[off + 1] = (byte) (value >>> 16);
        b[off + 2] = (byte) (value >>> 8);
        b[off + 3] = (byte) value;
    }

    private static int readShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** Convenience: the socket address of the gateway's NAT-PMP port. */
    public InetSocketAddress endpoint() {
        return new InetSocketAddress(gateway, PORT);
    }
}
