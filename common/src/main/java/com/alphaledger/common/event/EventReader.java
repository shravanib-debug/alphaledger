package com.alphaledger.common.event;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses a Kafka message value into a typed {@link EventEnvelope}. Malformed JSON throws Jackson's exception and an
 * envelope without an event ID or payload throws {@link InvalidEventException}; neither is retried.
 */
public class EventReader {

    private final JsonMapper jsonMapper;

    public EventReader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
        JavaType type = jsonMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        EventEnvelope<T> event = jsonMapper.readValue(json, type);
        if (event == null || event.eventId() == null || event.payload() == null) {
            throw new InvalidEventException("Event without eventId or payload");
        }
        return event;
    }
}
