package com.alphaledger.notification.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.notification.service.NotificationService;

/** The caller's notifications: those for their roles (Admin and Auditor see all), with their own read state. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
                                          Authentication authentication) {
        return notifications.list(AuthenticatedUser.from(authentication), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication authentication) {
        return Map.of("unread", notifications.unreadCount(AuthenticatedUser.from(authentication)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable long id, Authentication authentication) {
        notifications.markRead(AuthenticatedUser.from(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        return Map.of("marked", notifications.markAllRead(AuthenticatedUser.from(authentication)));
    }

    /** Builds the open-breaks digest now instead of waiting for 09:00. */
    @PostMapping("/digests")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Boolean> digest() {
        return Map.of("created", notifications.openBreaksDigest());
    }
}
