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
 * {@code pushToken} (§5's push channel) was declared here ahead of
 * customer-service's own schema change, specifically so this record's
 * shape would already be correct and wouldn't need touching again once
 * that field shipped. It has since shipped —
 * {@code V4__add_push_token_to_customers.sql}, exposed on
 * customer-service's own {@code CustomerResponse}, set via a dedicated
 * {@code PUT /customers/{id}/push-token} endpoint (kept separate from the
 * general customer-update endpoint, same single-purpose-endpoint
 * reasoning as this service's own provider classes) — so this field now
 * genuinely deserializes a real device token for any customer who has
 * registered one, the same way {@code phone} always has. A customer who
 * hasn't registered one yet still deserializes this to {@code null},
 * which remains a normal "no address on file for this channel" case, not
 * an error — see {@code NotificationServiceImpl#recipientAddressFor}'s
 * PUSH case, which already treats a {@code null} here as "no address on
 * file" and skips the channel, the same path a customer with no phone on
 * file already takes for SMS. Whether this field is ever actually SENT to
 * is separately gated by {@code FcmPushProvider} being registered at all
 * — see that class's own Javadoc for why it stays disabled by default
 * regardless of this field now being populated correctly.
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
