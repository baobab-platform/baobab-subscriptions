package com.baobabplatform.subscriptions.store;

/** A concurrent writer won; the unit of work may be retried from the start. */
public final class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
