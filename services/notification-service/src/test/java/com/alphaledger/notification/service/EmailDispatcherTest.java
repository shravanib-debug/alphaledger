package com.alphaledger.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.alphaledger.notification.config.NotificationProperties;
import com.alphaledger.notification.repository.NotificationRepository;
import com.alphaledger.notification.repository.NotificationRepository.PendingEmail;

/** A mail-server outage keeps the email queued and retried, then gives up after the configured attempts. */
class EmailDispatcherTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final NotificationProperties properties = new NotificationProperties(
            new NotificationProperties.Email(true, "alerts@alphaledger.test", 3),
            Map.of("OPS_ANALYST", List.of("rohan.ops@alphaledger.test")));
    private EmailDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Integer>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        dispatcher = new EmailDispatcher(repository, mail, properties, transaction,
                Clock.fixed(Instant.parse("2026-10-27T03:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void sendsToTheRoleRecipients() {
        when(repository.claimPendingEmails(50)).thenReturn(List.of(new PendingEmail(1, "OPS_ANALYST", "T", "M", 0)));

        assertThat(dispatcher.dispatch()).isEqualTo(1);
        verify(mail).send(any(SimpleMailMessage.class));
        verify(repository).emailSent(eq(1L), any());
    }

    @Test
    void aFailureIsRecordedAndRetriedLaterAndTheRestOfTheBatchWaits() {
        when(repository.claimPendingEmails(50)).thenReturn(List.of(new PendingEmail(1, "OPS_ANALYST", "T", "M", 0),
                new PendingEmail(2, "OPS_ANALYST", "T", "M", 0)));
        doThrow(new MailSendException("connection refused")).when(mail).send(any(SimpleMailMessage.class));

        assertThat(dispatcher.dispatch()).isZero();
        verify(repository).emailFailed(1L, "connection refused", false);   // still PENDING
        verify(repository, never()).emailFailed(eq(2L), anyString(), any(Boolean.class));
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        when(repository.claimPendingEmails(50)).thenReturn(List.of(new PendingEmail(1, "OPS_ANALYST", "T", "M", 2)));
        doThrow(new MailSendException("connection refused")).when(mail).send(any(SimpleMailMessage.class));

        dispatcher.dispatch();
        verify(repository).emailFailed(1L, "connection refused", true);    // FAILED
    }

    @Test
    void aRoleWithoutRecipientsIsSkipped() {
        when(repository.claimPendingEmails(50)).thenReturn(List.of(new PendingEmail(1, "PM", "T", "M", 0)));

        dispatcher.dispatch();
        verify(repository).emailSkipped(1L);
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }
}
