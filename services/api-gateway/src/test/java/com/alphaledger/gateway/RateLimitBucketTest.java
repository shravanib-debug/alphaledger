package com.alphaledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.alphaledger.gateway.RateLimitGlobalFilter.Bucket;

class RateLimitBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void burstUpToCapacityThenRefillsAtTheRate() {
        Bucket bucket = new Bucket(3);
        long t0 = System.nanoTime();
        assertThat(bucket.tryTake(3, 2, t0)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0)).isFalse();

        // 2 tokens a second: after half a second exactly one more request fits.
        assertThat(bucket.tryTake(3, 2, t0 + SECOND / 2)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0 + SECOND / 2)).isFalse();

        // Never refills past capacity.
        assertThat(bucket.isFull(3, 2, t0 + 60 * SECOND)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0 + 60 * SECOND)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0 + 60 * SECOND)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0 + 60 * SECOND)).isTrue();
        assertThat(bucket.tryTake(3, 2, t0 + 60 * SECOND)).isFalse();
    }
}
