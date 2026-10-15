package com.alphaledger.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void factoryCreatesVersionOneEnvelopeWithFreshIdAndTimestamp() {
        Instant before = Instant.now();

        EventEnvelope<String> envelope = EventEnvelope.of("TRADE_CREATED", "trade-service", "corr-1", "payload");

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isAfterOrEqualTo(before);
        assertThat(envelope.correlationId()).isEqualTo("corr-1");
        assertThat(envelope.producer()).isEqualTo("trade-service");
    }

    @Test
    void rejectsMissingPayloadAndInvalidVersion() {
        assertThatThrownBy(() -> EventEnvelope.of("TRADE_CREATED", "trade-service", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), "TRADE_CREATED", 0, Instant.now(),
                null, "trade-service", "payload"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
