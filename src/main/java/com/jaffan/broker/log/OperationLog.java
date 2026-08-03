package com.jaffan.broker.log;

import org.slf4j.Logger;

/**
 * Emits one structured, single-line, key=value record per broker operation to stdout — the format CF
 * log drains and humans both cope with. Deliberately tiny and allocation-light.
 *
 * <p>It intentionally has no field for a password or credential blob: there is simply no method that
 * would let a caller log a secret through here. Callers pass GUIDs, plan, backend, outcome and timing.
 */
public final class OperationLog {

    private OperationLog() {
    }

    /** Log a successful operation with its wall-clock duration. */
    public static void success(Logger log, String operation, String instanceGuid, String bindingGuid,
            String planId, String backend, long startNanos) {
        log.info("op={} outcome=success instance={} binding={} plan={} backend={} duration_ms={}",
                operation, nullToDash(instanceGuid), nullToDash(bindingGuid), nullToDash(planId),
                nullToDash(backend), millisSince(startNanos));
    }

    /** Log an operation that ended in an expected/handled outcome (e.g. idempotent no-op, conflict). */
    public static void outcome(Logger log, String operation, String outcome, String instanceGuid,
            String bindingGuid, String planId, String backend, long startNanos) {
        log.info("op={} outcome={} instance={} binding={} plan={} backend={} duration_ms={}",
                operation, outcome, nullToDash(instanceGuid), nullToDash(bindingGuid),
                nullToDash(planId), nullToDash(backend), millisSince(startNanos));
    }

    /** Log a failed operation. The throwable message is logged, never any credential material. */
    public static void failure(Logger log, String operation, String instanceGuid, String bindingGuid,
            String planId, String backend, long startNanos, Throwable error) {
        log.error("op={} outcome=failure instance={} binding={} plan={} backend={} duration_ms={} error=\"{}\"",
                operation, nullToDash(instanceGuid), nullToDash(bindingGuid), nullToDash(planId),
                nullToDash(backend), millisSince(startNanos), safeMessage(error));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "-";
        }
        String message = error.getMessage();
        return (message == null ? error.getClass().getSimpleName() : message).replace('"', '\'');
    }
}
