package com.giri.oms.customerclient.dto;

/**
 * Deliberately NOT the same class as oms-main's own
 * {@code customerclient.dto.CustomerClientResponse} — same package name,
 * different repo, different fields, same "only what this caller actually
 * uses" philosophy that class's own Javadoc explains. oms-main's version
 * carries {@code id, firstName, lastName} only (an order-creation name
 * snapshot never needed contact info); this service's whole job is
 * contacting the customer, so it needs {@code email} (Phase 1) and
 * {@code phone} (reserved for the SMS channel — Phase 4 of the plan this
 * service was scoped from). Not customer-service's full response shape —
 * address, status, createdAt/updatedAt are still left out.
 * <p>
 * {@code pushToken} (§5's push channel) is a genuine exception to "only
 * what this caller actually uses" above: customer-service does NOT expose
 * a device push token field yet at all — this isn't a client-side gap, the
 * data doesn't exist upstream. The field is declared here anyway, ahead of
 * that schema change, so this record's shape is already correct and this
 * won't need touching again once customer-service ships it — but until
 * then it will always deserialize to {@code null} (an absent JSON field
 * simply doesn't populate a record component). See
 * {@code NotificationServiceImpl#recipientAddressFor}'s PUSH case, which
 * already treats a {@code null} here as "no address on file" and skips the
 * channel — the same path a customer with no phone on file already takes
 * for SMS — and {@code FcmPushProvider}'s own Javadoc for why the provider
 * built against this field is disabled by default regardless.
 */
public record CustomerClientResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String pushToken
) {
}
