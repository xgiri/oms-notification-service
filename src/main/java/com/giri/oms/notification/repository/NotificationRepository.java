package com.giri.oms.notification.repository;

import com.giri.oms.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * Claims up to {@code limit} FAILED rows for this instance to retry,
     * oldest first — same {@code FOR UPDATE SKIP LOCKED} shape as
     * {@code OutboxEventRepository.findAndLockPendingBatch} in
     * customer-service/oms-main, for the identical reason: it's what makes
     * running more than one instance of this service safe. Two instances
     * polling at the same moment claim two disjoint sets of rows rather than
     * both retrying (and potentially double-sending) the same notification.
     * <p>
     * The lock only protects these rows for as long as the transaction that
     * acquired it stays open — see {@code NotificationRetryScheduler
     * #retryFailedNotifications}, which wraps the whole batch in one
     * {@code @Transactional} method for exactly that reason, same trade-off
     * as {@code OutboxPublisher}'s own equivalent method.
     * <p>
     * Native query for the same reason as the outbox equivalent: neither
     * Spring Data's derived-query naming nor its {@code @Lock} annotation
     * can express {@code SKIP LOCKED} — it isn't part of standard JPA, only
     * certain dialects (Postgres among them) support it.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'FAILED'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findAndLockFailedBatch(@Param("limit") int limit);
}
