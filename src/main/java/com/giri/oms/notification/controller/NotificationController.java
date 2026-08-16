package com.giri.oms.notification.controller;

import com.giri.oms.common.dto.PagedResponse;
import com.giri.oms.common.openapi.ApiErrorCodes;
import com.giri.oms.common.exception.ErrorCode;
import com.giri.oms.notification.dto.NotificationResponse;
import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.exception.NotificationNotFoundException;
import com.giri.oms.notification.mapper.NotificationMapper;
import com.giri.oms.notification.repository.NotificationRepository;
import com.giri.oms.notification.service.NotificationPreferenceService;
import com.giri.oms.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately a minimal surface — see this service's README. Unlike
 * shipment-service (a full CRUD REST API, since humans create/manage
 * shipments directly), this service's primary trigger is Kafka, not REST —
 * this controller exists for support/debugging and preference/unsubscribe
 * management, not for triggering sends.
 */
@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;

    @GetMapping
    @Operation(summary = "List a customer's notification delivery history")
    public ResponseEntity<PagedResponse<NotificationResponse>> getByCustomer(
            @RequestParam Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        var results = notificationRepository.findByCustomerId(customerId, pageable)
                .map(notificationMapper::mapToNotificationResponse);
        return ResponseEntity.ok(PagedResponse.of(results));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single notification's delivery status")
    @ApiErrorCodes({ErrorCode.NOTIFICATION_NOT_FOUND})
    public ResponseEntity<NotificationResponse> getById(@PathVariable Long id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
        return ResponseEntity.ok(notificationMapper.mapToNotificationResponse(notification));
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Manually retry a FAILED/DEAD_LETTERED notification",
            description = "Restricted to ADMIN. See NotificationService.resend's Javadoc for the Phase 1 " +
                    "limitation on reconstructed template variables.")
    @ApiErrorCodes({ErrorCode.NOTIFICATION_NOT_FOUND})
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Resend attempted")
    })
    public ResponseEntity<Void> resend(@PathVariable Long id) {
        notificationService.resend(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deliberately NOT behind {@code @PreAuthorize}/JWT — see
     * security.SecurityConfig's permitAll entry for this path and its own
     * reasoning. A real deployment should replace the raw
     * customerId/type/channel query params here with a signed, single-use
     * token embedded in the original email's unsubscribe link — this
     * placeholder shape lets anyone who knows/guesses a customerId opt
     * THAT customer out, which is a real gap, not a stylistic one. Flagged
     * here rather than silently shipped as if it were the finished design.
     */
    @GetMapping("/unsubscribe")
    @Operation(summary = "Opt out of a notification type/channel (unauthenticated, token-based in a real deployment)")
    public ResponseEntity<Void> unsubscribe(
            @RequestParam Long customerId,
            @RequestParam NotificationType type,
            @RequestParam(defaultValue = "EMAIL") NotificationChannel channel) {
        preferenceService.optOut(customerId, type, channel);
        return ResponseEntity.noContent().build();
    }
}
