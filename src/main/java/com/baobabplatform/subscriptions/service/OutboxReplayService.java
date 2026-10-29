package com.baobabplatform.subscriptions.service;

import com.baobabplatform.subscriptions.store.OutboxDeliveryQueue;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Governance boundary for explicit quarantined-event replay (ADR-SUB-0014
 * §§53–58, ADR-SUB-0016). Default denies; no HTTP/operator route is wired.
 *
 * <p>Only a current, independently evaluated CP administrative decision may
 * yield a positive replay authorization. Historical approval references and
 * JWT scopes alone do not satisfy this port.
 */
public final class OutboxReplayService {
    public record Request(String tenantId, UUID eventId, String reasonCode,
                          String evidenceReference) { }

    public record Authorization(String tenantId, UUID eventId,
                                String verifiedPrincipal, String approvalReference) { }

    @FunctionalInterface
    public interface CurrentReplayAuthority {
        Optional<Authorization> approve(Request request);

        static CurrentReplayAuthority unavailable() {
            return request -> Optional.empty();
        }
    }

    private final OutboxDeliveryQueue queue;
    private final CurrentReplayAuthority authority;

    public OutboxReplayService(OutboxDeliveryQueue queue, CurrentReplayAuthority authority) {
        this.queue = Objects.requireNonNull(queue);
        this.authority = Objects.requireNonNull(authority);
    }

    public boolean replay(Request request) {
        Objects.requireNonNull(request);
        if (request.tenantId() == null || request.tenantId().isBlank()
                || request.eventId() == null || request.reasonCode() == null
                || !request.reasonCode().matches("[A-Z][A-Z0-9_]{2,47}")
                || request.evidenceReference() == null
                || !request.evidenceReference().matches("[A-Za-z0-9._:/-]{8,256}")) {
            throw new IllegalArgumentException("a tenant, event, bounded reason and evidence are mandatory");
        }
        Authorization permit = authority.approve(request)
                .orElseThrow(() -> new SecurityException("current CP replay approval is unavailable"));
        if (!request.tenantId().equals(permit.tenantId())
                || !request.eventId().equals(permit.eventId())
                || permit.verifiedPrincipal() == null || permit.verifiedPrincipal().isBlank()
                || permit.approvalReference() == null || permit.approvalReference().isBlank()) {
            throw new SecurityException("current CP replay approval does not match the requested event");
        }
        // The approved reference and reason are committed atomically with
        // the state change, preserving original event id and envelope.
        return queue.replay(request.tenantId(), request.eventId(),
                permit.verifiedPrincipal(), permit.approvalReference(), request.reasonCode(),
                request.evidenceReference());
    }
}
