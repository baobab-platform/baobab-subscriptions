package com.baobabplatform.subscriptions.auth;

/** The caller is not authenticated (401) or not authorised (403). */
public final class AuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public AuthException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
