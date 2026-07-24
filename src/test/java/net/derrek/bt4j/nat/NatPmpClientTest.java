package net.derrek.bt4j.nat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/** NAT-PMP (RFC 6886) wire codecs and the default-gateway heuristic. */
class NatPmpClientTest {

    @Test
    void externalAddressRequestIsTwoZeroBytes() {
        assertArrayEquals(new byte[]{0, 0}, NatPmpClient.externalAddressRequest());
    }

    @Test
    void mapRequestEncodesPortsAndLifetime() {
        byte[] req = NatPmpClient.mapRequest(true, 6881, 6881, 3600);
        assertEquals(12, req.length);
        assertEquals(0, req[0]);              // version
        assertEquals(2, req[1]);              // TCP opcode
        assertEquals(0, req[2]);              // reserved
        assertEquals(0, req[3]);
        assertEquals(6881, ((req[4] & 0xFF) << 8) | (req[5] & 0xFF));   // internal port
        assertEquals(6881, ((req[6] & 0xFF) << 8) | (req[7] & 0xFF));   // suggested external port
        long lifetime = ((long) (req[8] & 0xFF) << 24) | ((req[9] & 0xFF) << 16) | ((req[10] & 0xFF) << 8) | (req[11] & 0xFF);
        assertEquals(3600, lifetime);
    }

    @Test
    void deleteMappingIsLifetimeZeroExternalZero() {
        byte[] req = NatPmpClient.mapRequest(true, 6881, 0, 0);
        assertEquals(0, ((req[6] & 0xFF) << 8) | (req[7] & 0xFF)); // external port 0
        assertEquals(0, req[8] | req[9] | req[10] | req[11]);      // lifetime 0
    }

    @Test
    void parsesAMappingResponse() throws Exception {
        // version 0, op 130 (TCP response), result 0, epoch, internal 6881, external 40000, lifetime 3600
        byte[] resp = new byte[16];
        resp[0] = 0;
        resp[1] = (byte) 130;
        resp[4] = 0; resp[5] = 0; resp[6] = 0; resp[7] = 10; // epoch
        resp[8] = (byte) (6881 >> 8); resp[9] = (byte) 6881;
        resp[10] = (byte) (40000 >> 8); resp[11] = (byte) 40000;
        resp[12] = 0; resp[13] = 0; resp[14] = (byte) (3600 >> 8); resp[15] = (byte) 3600;

        NatPmpClient.MapResult result = NatPmpClient.parseMapResponse(resp, resp.length, true);
        assertTrue(result.ok());
        assertEquals(6881, result.internalPort());
        assertEquals(40000, result.externalPort());
        assertEquals(3600, result.lifetimeSeconds());
    }

    @Test
    void mappingResponseForWrongProtocolIsRejected() {
        byte[] udpResponse = new byte[16];
        udpResponse[0] = 0;
        udpResponse[1] = (byte) 129; // UDP response
        assertThrows(IllegalArgumentException.class,
                () -> NatPmpClient.parseMapResponse(udpResponse, udpResponse.length, true));
    }

    @Test
    void parsesAnExternalAddressResponse() {
        byte[] resp = new byte[12];
        resp[0] = 0;
        resp[1] = (byte) 128; // external-address response
        resp[8] = (byte) 203; resp[9] = 0; resp[10] = (byte) 113; resp[11] = 7; // 203.0.113.7
        NatPmpClient.ExternalAddress ext = NatPmpClient.parseExternalResponse(resp, resp.length);
        assertTrue(ext.ok());
        assertEquals("203.0.113.7", ext.address().getHostAddress());
    }

    @Test
    void nonSuccessResultIsNotOk() {
        byte[] resp = new byte[12];
        resp[0] = 0;
        resp[1] = (byte) 128;
        resp[2] = 0; resp[3] = 2; // result code 2 (not authorized)
        assertFalse(NatPmpClient.parseExternalResponse(resp, resp.length).ok());
    }

    @Test
    void truncatedResponseIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> NatPmpClient.parseMapResponse(new byte[8], 8, true));
    }

    @Test
    void gatewayForPrefixIsNetworkPlusOne() throws Exception {
        assertEquals("192.168.1.1", NatPmpClient.gatewayForPrefix(
                InetAddress.getByName("192.168.1.57").getAddress(), 24).getHostAddress());
        assertEquals("10.0.0.1", NatPmpClient.gatewayForPrefix(
                InetAddress.getByName("10.0.5.9").getAddress(), 8).getHostAddress());
        assertEquals("172.20.16.1", NatPmpClient.gatewayForPrefix(
                InetAddress.getByName("172.20.30.4").getAddress(), 20).getHostAddress());
    }

    @Test
    void gatewayForPrefixRejectsNoHostRoom() {
        try {
            assertNull(NatPmpClient.gatewayForPrefix(InetAddress.getByName("192.168.1.1").getAddress(), 31));
            assertNull(NatPmpClient.gatewayForPrefix(InetAddress.getByName("192.168.1.1").getAddress(), 32));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
