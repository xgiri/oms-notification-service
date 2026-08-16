package com.giri.oms.notification.repository;

import com.giri.oms.notification.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByEventIdAndNotificationType(UUID eventId, String notificationType);
}
