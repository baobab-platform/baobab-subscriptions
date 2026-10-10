package com.baobabplatform.subscriptions;

import com.baobabplatform.subscriptions.payments.PaymentsPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared test inputs: the fictional synthetic-affiliate INTERNAL and Acme COMMERCIAL subscriptions of the Shared examples (not a real founding-group business). */
public final class Fixtures {
    public static final String SYNTHETIC_TENANT = "tn_01k4m7x9q2v6c8r3d5f1h0j4";
    public static final String ACME_TENANT = "tn_01k9acmeltd";
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T08:00:00Z"), ZoneOffset.UTC);

    private Fixtures() {
    }

    public static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] syntheticInternal() {
        return json("""
                {"tenant_id":"%s","product_subscription_id":"sub_01k9syntheticxbt","authoritative_revision":2,"product_id":"baobab-xbt",
                 "legal_entity_id":"LE-SYNTHAFFIL01","subscription_type":"INTERNAL",
                 "classification":{"classification_id":"subcls_01k9synthinternal","classification_source":"ADMISSION_DECISION",
                   "classification_reference":"adm_01k9synthetic","classified_at":"2026-09-22T10:05:00Z"}}""".formatted(SYNTHETIC_TENANT));
    }

    public static byte[] syntheticReclassifiedCommercial() {
        return json("""
                {"tenant_id":"%s","product_subscription_id":"sub_01k9syntheticxbt","authoritative_revision":5,"product_id":"baobab-xbt",
                 "legal_entity_id":"LE-SYNTHAFFIL01","subscription_type":"COMMERCIAL",
                 "classification":{"classification_id":"subcls_01k9synthcommercial","classification_source":"RECLASSIFICATION",
                   "classification_reference":"chg_01k9synthdivestiture","classified_at":"2026-10-01T09:00:00Z"}}""".formatted(SYNTHETIC_TENANT));
    }

    public static byte[] acmeCommercial() {
        return json("""
                {"tenant_id":"%s","product_subscription_id":"sub_01k9acmexbt","authoritative_revision":1,"product_id":"baobab-xbt",
                 "platform_account_id":"pacct_01k9acme","legal_entity_id":"LE-01K9ACMELTD","subscription_type":"COMMERCIAL",
                 "classification":{"classification_id":"subcls_01k9acmecommercial","classification_source":"ADMISSION_DECISION",
                   "classification_reference":"adm_01k9acme","classified_at":"2026-09-23T15:05:00Z"}}""".formatted(ACME_TENANT));
    }

    public static byte[] usage(String tenant, String source) {
        return json("""
                {"tenant_id":"%s","metric_key":"api.calls","quantity":1250,"unit":"call",
                 "occurred_at":"2026-09-24T00:00:00Z","source_reference":"%s"}""".formatted(tenant, source));
    }

    public static byte[] command(String tenant, long revision, String reason) {
        return json("{\"tenant_id\":\"%s\",\"authoritative_revision\":%d,\"reason\":\"%s\"}".formatted(tenant, revision, reason));
    }

    /** A payments port that records every call, so a test can prove it was never touched. */
    public static final class RecordingPayments implements PaymentsPort {
        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean configured() {
            calls.incrementAndGet();
            return false;
        }
    }
}
