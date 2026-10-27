package com.alphaledger.common.kafka;

import java.util.List;

import com.alphaledger.common.event.Topics;

/** Dead-letter topic names: {@code <topic>.DLT} (conventions.md §3), keyed and partitioned like the source topic. */
public final class DeadLetterTopics {

    public static final String SUFFIX = ".DLT";

    /** Every topic some service consumes; each gets a dead-letter topic. */
    public static final List<String> CONSUMED = List.of(Topics.TRADE_CREATED, Topics.TRADE_SETTLED,
            Topics.TRADE_CANCELLED, Topics.POSITION_UPDATED, Topics.LIMIT_BREACHED, Topics.RECON_BREAK_CREATED,
            Topics.RECON_BREAK_RESOLVED);

    private DeadLetterTopics() {
    }

    public static String of(String topic) {
        return topic + SUFFIX;
    }

    public static boolean isDeadLetterTopic(String topic) {
        return topic != null && topic.endsWith(SUFFIX);
    }

    public static String originalOf(String deadLetterTopic) {
        return deadLetterTopic.substring(0, deadLetterTopic.length() - SUFFIX.length());
    }
}
