package com.baobabplatform.subscriptions.domain;

/** Which billing provider holds the projection. TEMPORARY is always simulated. */
public record ProviderRef(String kind, boolean simulated, String providerReference) {
    public ProviderRef {
        if ("TEMPORARY".equals(kind) && !simulated) {
            throw new IllegalArgumentException("a TEMPORARY provider is always simulated");
        }
    }
}
