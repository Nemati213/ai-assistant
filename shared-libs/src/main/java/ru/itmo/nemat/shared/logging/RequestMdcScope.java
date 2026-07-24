package ru.itmo.nemat.shared.logging;

import org.slf4j.MDC;

import java.util.UUID;

public final class RequestMdcScope implements AutoCloseable {

    private static final String REQUEST_ID = "requestId";

    private final String previousRequestId;

    private RequestMdcScope(String requestId) {
        this.previousRequestId = MDC.get(REQUEST_ID);
        MDC.put(REQUEST_ID, requestId);
    }

    public static RequestMdcScope open(UUID requestId) {
        return new RequestMdcScope(requestId.toString());
    }

    @Override
    public void close() {
        if (previousRequestId == null) {
            MDC.remove(REQUEST_ID);
        } else {
            MDC.put(REQUEST_ID, previousRequestId);
        }
    }
}
