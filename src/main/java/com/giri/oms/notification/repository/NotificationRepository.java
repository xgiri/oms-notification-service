package com.giri.oms.notification.repository;

import com.giri.oms.notification.entity.Notification;
import com.giri.oms.notification.entity.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByCustomerId(Long customerId, Pageable pageable);

    // Backs NotificationRetryScheduler — see that class's Javadoc for the
    // retry-budget/backoff logic built on top of this.
    List<Notification> findByStatus(NotificationStatus status);
}
