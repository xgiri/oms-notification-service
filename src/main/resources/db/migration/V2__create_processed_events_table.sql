-- The idempotency guard — see notification.entity.ProcessedEvent's own
-- Javadoc for why this table matters more here than the equivalent concept
-- anywhere else in this system.

CREATE TABLE processed_events (
    id                  UUID PRIMARY KEY,
    event_id            UUID         NOT NULL,
    notification_type   VARCHAR(30)  NOT NULL,
    processed_at        TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_processed_events_event_type UNIQUE (event_id, notification_type)
);

-- The lookup this table exists to make fast — see
-- ProcessedEventRepository.existsByEventIdAndNotificationType, called on
-- every single event this service consumes, before anything else happens.
CREATE INDEX idx_processed_events_event_id_type ON processed_events (event_id, notification_type);
