package org.example.demo.common.util;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUID version 7 (time-ordered UUID) generator.
 * <p>
 * Layout (RFC 4122 variant):
 * - 48 bits: Unix epoch milliseconds
 * - 4 bits : version (7)
 * - 12 bits: random/sequence (monotonic within same millisecond)
 * - 2 bits : variant (10)
 * - 62 bits: random
 */
public final class Uuid7 {
    private static final SecureRandom RNG = new SecureRandom();
    private static final AtomicLong LAST_TS_MS = new AtomicLong(-1L);
    private static final AtomicInteger LAST_SEQ = new AtomicInteger(0);

    private Uuid7() {
    }

    public static UUID newUuid() {
        long tsMs = System.currentTimeMillis();

        int randA;
        long randB;
        synchronized (Uuid7.class) {
            long last = LAST_TS_MS.get();
            if (tsMs == last) {
                randA = (LAST_SEQ.incrementAndGet()) & 0x0FFF;
            } else {
                LAST_TS_MS.set(tsMs);
                randA = RNG.nextInt(1 << 12);
                LAST_SEQ.set(randA);
            }
            randB = RNG.nextLong() & ((1L << 62) - 1);
        }

        long msb = ((tsMs & 0xFFFFFFFFFFFFL) << 16) | (0x7L << 12) | (randA & 0x0FFFL);
        long lsb = (1L << 63) | randB; // variant 10xx...
        return new UUID(msb, lsb);
    }

    public static String newUuidString() {
        return newUuid().toString();
    }
}

