package net.derrek.bt4j.nat;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The port mapper must be fully fail-soft: starting and closing it on a machine with no cooperative gateway (the
 * CI/test environment) returns promptly and never throws.
 */
class PortMapperTest {

    @Test
    @Timeout(20)
    void startAndCloseAreSafeWithoutAGateway() {
        long start = System.nanoTime();
        PortMapper mapper = PortMapper.start(6881);
        // no gateway in the test environment: external address is simply absent, never an exception
        assertNotNull(mapper.externalAddress());
        mapper.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 15_000, "start/close should return promptly, took " + elapsedMillis + "ms");
    }
}
