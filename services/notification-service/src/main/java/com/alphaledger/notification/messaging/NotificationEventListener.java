package com.alphaledger.notification.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.alphaledger.common.event.EventReader;
import com.alphaledger.common.event.LimitBreachEvent;
import com.alphaledger.common.event.ReconBreakEvent;
import com.alphaledger.common.event.Topics;
import com.alphaledger.notification.service.NotificationService;

/**
 * Consumes reconciliation and limit events. Retries, dead-lettering and the correlation ID in the logs come from
 * common's {@code KafkaResilienceAutoConfiguration}.
 */
@Component
public class NotificationEventListener {

    private final EventReader reader;
    private final NotificationService notifications;

    public NotificationEventListener(EventReader reader, NotificationService notifications) {
        this.reader = reader;
        this.notifications = notifications;
    }

    @KafkaListener(id = "notification-recon", topics = {Topics.RECON_BREAK_CREATED, Topics.RECON_BREAK_RESOLVED},
            groupId = "notification-service")
    public void onReconBreak(ConsumerRecord<String, String> record) {
        if (Topics.RECON_BREAK_CREATED.equals(record.topic())) {
            notifications.onBreakCreated(reader.read(record.value(), ReconBreakEvent.class));
        } else {
            notifications.onBreakResolved(reader.read(record.value(), ReconBreakEvent.class));
        }
    }

    @KafkaListener(id = "notification-limits", topics = Topics.LIMIT_BREACHED, groupId = "notification-service")
    public void onLimitBreached(ConsumerRecord<String, String> record) {
        notifications.onLimitBreached(reader.read(record.value(), LimitBreachEvent.class));
    }

    /** Daily open-breaks digest for Ops (P2). */
    @Scheduled(cron = "${alphaledger.notification.digest-cron:0 0 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void dailyDigest() {
        notifications.openBreaksDigest();
    }
}
