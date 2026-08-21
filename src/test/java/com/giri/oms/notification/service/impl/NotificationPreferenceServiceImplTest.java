package com.giri.oms.notification.service.impl;

import com.giri.oms.notification.entity.NotificationChannel;
import com.giri.oms.notification.entity.NotificationPreference;
import com.giri.oms.notification.entity.NotificationType;
import com.giri.oms.notification.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The first real unit test of this class's actual {@code isOptedIn} logic
 * — until the CUSTOMER_WELCOME classification change (see
 * {@link NotificationType}'s own Javadoc), every type was
 * {@code transactional = true}, so the stored-preference branch was
 * provably unreachable in production and only ever exercised indirectly,
 * via a mocked {@code NotificationPreferenceService} in
 * {@code NotificationServiceImplTest}. Now that one real non-transactional
 * type exists, this class's own branching logic has a genuine behavior to
 * verify.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceImplTest {

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    private NotificationPreferenceServiceImpl preferenceService;

    private static final Long CUSTOMER_ID = 7L;

    @BeforeEach
    void setUp() {
        preferenceService = new NotificationPreferenceServiceImpl(preferenceRepository);
    }

    @Nested
    class TransactionalTypes {

        @Test
        void alwaysOptedIn_regardlessOfAnyStoredPreference() {
            // The short-circuit itself is the thing under test here — no
            // repository call should even happen for a transactional type,
            // since the answer is "yes" unconditionally. Verified by
            // asserting zero repository interactions, not just the return
            // value, so a future accidental repository call (e.g. someone
            // reordering the branches) would fail this test even if the
            // opted-in default happened to still be true.
            boolean optedIn = preferenceService.isOptedIn(CUSTOMER_ID, NotificationType.ORDER_CONFIRMED, NotificationChannel.EMAIL);

            assertThat(optedIn).isTrue();
            verifyNoInteractions(preferenceRepository);
        }
    }

    @Nested
    class NonTransactionalTypes {

        @Test
        void optedIn_whenNoPreferenceRowExists() {
            // See NotificationPreference's own Javadoc — absence of a row
            // means opted-in, so a customer who's never touched their
            // preferences still gets this by default.
            when(preferenceRepository.findByCustomerIdAndNotificationTypeAndChannel(
                    CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL))
                    .thenReturn(Optional.empty());

            boolean optedIn = preferenceService.isOptedIn(CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL);

            assertThat(optedIn).isTrue();
        }

        @Test
        void respectsAStoredOptOut() {
            NotificationPreference preference = new NotificationPreference();
            preference.setCustomerId(CUSTOMER_ID);
            preference.setNotificationType(NotificationType.CUSTOMER_WELCOME);
            preference.setChannel(NotificationChannel.EMAIL);
            preference.setOptedIn(false);
            when(preferenceRepository.findByCustomerIdAndNotificationTypeAndChannel(
                    CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL))
                    .thenReturn(Optional.of(preference));

            boolean optedIn = preferenceService.isOptedIn(CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL);

            assertThat(optedIn).isFalse();
        }

        @Test
        void respectsAStoredOptIn() {
            NotificationPreference preference = new NotificationPreference();
            preference.setCustomerId(CUSTOMER_ID);
            preference.setNotificationType(NotificationType.CUSTOMER_WELCOME);
            preference.setChannel(NotificationChannel.EMAIL);
            preference.setOptedIn(true);
            when(preferenceRepository.findByCustomerIdAndNotificationTypeAndChannel(
                    CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL))
                    .thenReturn(Optional.of(preference));

            boolean optedIn = preferenceService.isOptedIn(CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL);

            assertThat(optedIn).isTrue();
        }
    }

    @Nested
    class OptOut {

        @Test
        void createsANewPreferenceRow_whenNoneExists() {
            when(preferenceRepository.findByCustomerIdAndNotificationTypeAndChannel(
                    CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL))
                    .thenReturn(Optional.empty());

            preferenceService.optOut(CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL);

            verify(preferenceRepository).save(argThatOptedOutRowFor(CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL));
        }

        @Test
        void isIdempotent_flippingAnExistingRowRatherThanDuplicatingIt() {
            NotificationPreference existing = new NotificationPreference();
            existing.setCustomerId(CUSTOMER_ID);
            existing.setNotificationType(NotificationType.CUSTOMER_WELCOME);
            existing.setChannel(NotificationChannel.EMAIL);
            existing.setOptedIn(true);
            when(preferenceRepository.findByCustomerIdAndNotificationTypeAndChannel(
                    CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL))
                    .thenReturn(Optional.of(existing));

            preferenceService.optOut(CUSTOMER_ID, NotificationType.CUSTOMER_WELCOME, NotificationChannel.EMAIL);

            assertThat(existing.isOptedIn()).isFalse();
            verify(preferenceRepository).save(existing);
        }
    }

    private NotificationPreference argThatOptedOutRowFor(Long customerId, NotificationType type, NotificationChannel channel) {
        return org.mockito.ArgumentMatchers.argThat(pref ->
                pref.getCustomerId().equals(customerId)
                        && pref.getNotificationType() == type
                        && pref.getChannel() == channel
                        && !pref.isOptedIn());
    }
}
