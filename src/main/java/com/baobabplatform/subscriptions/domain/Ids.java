package com.baobabplatform.subscriptions.domain;

import java.security.SecureRandom;
import java.util.UUID;

/** Engine-minted opaque identifiers: a prefix and a time-ordered (UUIDv7) hex body. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String billingSubscriptionId() {
        return "bsub_" + hex(uuidV7());
    }

    public static String usageRecordId() {
        return "usage_" + hex(uuidV7());
    }

    public static UUID uuidV7() {
        long millis = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long msb = (millis << 16) | 0x7000L | ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);
        long lsb = 0x8000000000000000L | ((random[2] & 0x3FL) << 56);
        for (int i = 3; i < 10; i++) {
            lsb |= (random[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(msb, lsb);
    }

    private static String hex(UUID uuid) {
        return uuid.toString().replace("-", "");
    }
}
