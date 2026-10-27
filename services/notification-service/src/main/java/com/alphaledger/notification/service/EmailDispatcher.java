package com.alphaledger.notification.service;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.notification.config.NotificationProperties;
import com.alphaledger.notification.repository.NotificationRepository;
import com.alphaledger.notification.repository.NotificationRepository.PendingEmail;

/**
 * Sends queued notification emails (MailHog locally). A mail-server failure is recorded on the row and retried on
 * the next poll, up to {@code max-attempts}, then marked FAILED; the notification itself is never lost.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final int BATCH = 50;

    private final NotificationRepository repository;
    private final JavaMailSender mail;
    private final NotificationProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public EmailDispatcher(NotificationRepository repository, JavaMailSender mail, NotificationProperties properties,
                           TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.mail = mail;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${alphaledger.notification.email.poll-interval-ms:5000}")
    public void poll() {
        try {
            dispatch();
        } catch (RuntimeException ex) {
            log.warn("Email dispatch failed: {}", ex.getMessage());
        }
    }

    /** Sends one batch; returns how many emails were sent. */
    public int dispatch() {
        Integer sent = transaction.execute(status -> {
            int count = 0;
            for (PendingEmail email : repository.claimPendingEmails(BATCH)) {
                List<String> to = properties.recipientsFor(email.audienceRole());
                if (to.isEmpty()) {
                    repository.emailSkipped(email.id());
                    continue;
                }
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(properties.email().from());
                message.setTo(to.toArray(String[]::new));
                message.setSubject("[AlphaLedger] " + email.title());
                message.setText(email.message() + "\n\nNotification " + email.id() + ". Open AlphaLedger to see it.");
                try {
                    mail.send(message);
                    repository.emailSent(email.id(), clock.instant());
                    count++;
                } catch (MailException ex) {
                    boolean giveUp = email.attempts() + 1 >= properties.email().maxAttempts();
                    repository.emailFailed(email.id(), String.valueOf(ex.getMessage()), giveUp);
                    log.warn("Email for notification {} failed (attempt {}{}): {}", email.id(), email.attempts() + 1,
                            giveUp ? ", giving up" : "", ex.getMessage());
                    break;   // the mail server is likely down; try the rest on the next poll
                }
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }
}
