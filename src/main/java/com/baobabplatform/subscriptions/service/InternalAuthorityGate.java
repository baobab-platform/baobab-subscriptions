package com.baobabplatform.subscriptions.service;

/**
 * Consuming PEP for a Control Plane INTERNAL classification.
 *
 * The projection, lifecycle event and corporate relationship are never
 * authority. A future adapter must authenticate to Control Plane and obtain
 * a CURRENT, tenant/product-bound, positive founding eligibility decision.
 * Failure, expired authority and unavailable CP always deny.
 */
@FunctionalInterface
public interface InternalAuthorityGate {
    boolean currentlyAuthorised(String tenantId, String productSubscriptionId, String classificationReference);

    /** Safe operational default until authenticated, current CP PDP exists. */
    static InternalAuthorityGate unavailable() {
        return (tenant, subscription, reference) -> false;
    }
}
