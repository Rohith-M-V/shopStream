package com.shopstream.notification.web;

import com.shopstream.notification.dto.NotificationResponse;
import com.shopstream.notification.notification.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, like payment: notifications are only ever created by reacting to events. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<List<NotificationResponse>> forOrder(@PathVariable String orderId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID customerId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.ok(notificationService.getForOrder(orderId, customerId));
    }
}
