package com.baobabplatform.subscriptions.domain;

import java.time.Instant;

/** The Control Plane classification a projection bills: recorded, never evaluated. */
public record Classification(
        String classificationId,
        String classificationSource,
        String classificationReference,
        Instant classifiedAt) {
}
