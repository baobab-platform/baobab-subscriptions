package com.baobabplatform.subscriptions.config;

/** The service's configuration is invalid; the service refuses to start. */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
