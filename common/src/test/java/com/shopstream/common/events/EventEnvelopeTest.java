package com.shopstream.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void ofGeneratesIdTimestampAndDefaultVersion() {
        var event = EventEnvelope.of(EventTypes.ORDER_CREATED, "order-1", "payload");

        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo("order-1");
    }

    @Test
    void twoEventsNeverShareAnId() {
        var a = EventEnvelope.of(EventTypes.ORDER_CREATED, "order-1", "x");
        var b = EventEnvelope.of(EventTypes.ORDER_CREATED, "order-1", "x");

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> EventEnvelope.of(EventTypes.ORDER_CREATED, "order-1", null))
                .isInstanceOf(NullPointerException.class);
    }
}
