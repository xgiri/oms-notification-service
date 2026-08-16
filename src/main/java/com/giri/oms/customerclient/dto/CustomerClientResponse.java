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
 */
public record CustomerClientResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone
) {
}
