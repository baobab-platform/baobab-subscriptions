package com.baobabplatform.subscriptions.service;

import java.util.List;

/** A request the Billing API refuses, with its RFC 9457 problem shape. */
public final class BillingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final boolean retryable;
    private final transient List<String> problems;

    public BillingException(int status, String code, String detail, boolean retryable) {
        this(status, code, detail, retryable, List.of());
    }

    public BillingException(int status, String code, String detail, boolean retryable, List<String> problems) {
        super(detail);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.problems = List.copyOf(problems);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public List<String> problems() {
        return problems;
    }

    static BillingException invalid(List<String> problems) {
        return new BillingException(400, "VALIDATION_FAILED", "the request does not satisfy the contract", false, problems);
    }

    static BillingException notFound() {
        return new BillingException(404, "BILLING_PROJECTION_NOT_FOUND", "no such billing projection for this tenant", false);
    }
}
