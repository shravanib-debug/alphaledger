package com.alphaledger.notification.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.kafka.DeadLetterReplayer;
import com.alphaledger.common.kafka.DeadLetterReplayer.DeadLetter;
import com.alphaledger.common.kafka.DeadLetterTopics;

/**
 * Admin view of the dead-letter topics ({@code <topic>.DLT}) of every service, and replay once the cause is fixed.
 * Hosted here as the operations-facing service; routed by the gateway as {@code /api/v1/ops/**}.
 */
@RestController
@RequestMapping("/api/v1/ops/dead-letters")
@PreAuthorize("hasRole('ADMIN')")
public class DeadLetterController {

    private final DeadLetterReplayer replayer;

    public DeadLetterController(DeadLetterReplayer replayer) {
        this.replayer = replayer;
    }

    @GetMapping
    public List<String> topics() {
        return DeadLetterTopics.CONSUMED.stream().map(DeadLetterTopics::of).toList();
    }

    /** Records waiting to be replayed, with the error that sent each one here. */
    @GetMapping("/{topic}")
    public List<DeadLetter> pending(@PathVariable String topic,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        try {
            return replayer.pending(topic, limit);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("topic: " + ex.getMessage());
        }
    }

    @PostMapping("/{topic}/replay")
    public Map<String, Object> replay(@PathVariable String topic,
                                      @RequestParam(defaultValue = "500") @Min(1) @Max(10000) int limit) {
        try {
            return Map.of("topic", DeadLetterTopics.isDeadLetterTopic(topic) ? topic : DeadLetterTopics.of(topic),
                    "replayed", replayer.replay(topic, limit));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("topic: " + ex.getMessage());
        }
    }
}
