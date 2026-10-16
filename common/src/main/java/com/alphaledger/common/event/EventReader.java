package com.alphaledger.common.event;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/** Parses a Kafka message value into a typed {@link EventEnvelope}. */
public class EventReader {

    private final JsonMapper jsonMapper;

    public EventReader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
        JavaType type = jsonMapper.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
        return jsonMapper.readValue(json, type);
    }
}
