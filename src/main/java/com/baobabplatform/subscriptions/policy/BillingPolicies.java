package com.baobabplatform.subscriptions.policy;

import com.baobabplatform.subscriptions.domain.SubscriptionType;
import com.baobabplatform.subscriptions.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * The billing policy per subscription type, exactly as Shared publishes it in
 * contracts/product/v1/billing-policy.yaml. The engine applies it and never
 * infers a classification.
 */
public final class BillingPolicies {
    static final String RESOURCE = "contracts/product/v1/billing-policy.yaml";

    private final Map<SubscriptionType, BillingPolicy> policies;

    private BillingPolicies(Map<SubscriptionType, BillingPolicy> policies) {
        this.policies = Map.copyOf(policies);
    }

    /** Loads the pinned policy and checks every type has an entry that keeps the always-on controls on. */
    public static BillingPolicies load() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = BillingPolicies.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            JsonNode doc = yaml.readTree(in);
            if (doc.path("version").asInt() != 1) {
                throw new IllegalStateException(RESOURCE + ": unsupported version " + doc.path("version"));
            }
            Map<SubscriptionType, BillingPolicy> out = new EnumMap<>(SubscriptionType.class);
            for (SubscriptionType type : SubscriptionType.values()) {
                JsonNode entry = doc.path("policies").path(type.name());
                if (entry.isMissingNode()) {
                    throw new IllegalStateException(RESOURCE + ": no policy for " + type);
                }
                BillingPolicy policy = Json.mapper().treeToValue(entry, BillingPolicy.class);
                if (!(policy.usageMetering() && policy.entitlementControl() && policy.audit()
                        && policy.readinessControl() && policy.isolationControl())) {
                    throw new IllegalStateException(RESOURCE + ": " + type + " switches off an always-on control");
                }
                if (!policy.chargesMoney() && (policy.billingRequired() || policy.mayRequirePayment())) {
                    throw new IllegalStateException(RESOURCE + ": " + type + " charges nothing yet requires billing or payment");
                }
                out.put(type, policy);
            }
            return new BillingPolicies(out);
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " could not be read", e);
        }
    }

    public BillingPolicy forType(SubscriptionType type) {
        return policies.get(type);
    }
}
