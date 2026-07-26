package ru.itmo.nemat.aiservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.EnumMap;
import java.util.Map;

@Component
public class OpenRouterMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<Result, Counter> requestCounters =
            new EnumMap<>(Result.class);
    private final Map<Result, Timer> requestTimers = new EnumMap<>(Result.class);

    public OpenRouterMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (Result result : Result.values()) {
            requestCounters.put(
                    result,
                    Counter.builder("curator.openrouter.requests")
                            .description("OpenRouter requests by outcome")
                            .tags(
                                    "outcome", result.outcome,
                                    "failure", result.failure
                            )
                            .register(meterRegistry)
            );
            requestTimers.put(
                    result,
                    Timer.builder("curator.openrouter.request.duration")
                            .description("OpenRouter request duration by outcome")
                            .tags(
                                    "outcome", result.outcome,
                                    "failure", result.failure
                            )
                            .register(meterRegistry)
            );
        }
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void recordSuccess(Timer.Sample sample) {
        record(sample, Result.SUCCESS);
    }

    public void recordFailure(Timer.Sample sample, Exception exception) {
        record(sample, classify(exception));
    }

    private void record(Timer.Sample sample, Result result) {
        requestCounters.get(result).increment();
        sample.stop(requestTimers.get(result));
    }

    private Result classify(Exception exception) {
        if (exception instanceof RestClientResponseException responseException) {
            if (responseException.getStatusCode().is4xxClientError()) {
                return Result.HTTP_4XX;
            }
            if (responseException.getStatusCode().is5xxServerError()) {
                return Result.HTTP_5XX;
            }
        }
        if (exception instanceof ResourceAccessException) {
            return Result.NETWORK;
        }
        if (exception instanceof IllegalStateException) {
            return Result.INVALID_RESPONSE;
        }
        return Result.OTHER;
    }

    private enum Result {
        SUCCESS("success", "none"),
        HTTP_4XX("failure", "http_4xx"),
        HTTP_5XX("failure", "http_5xx"),
        NETWORK("failure", "network"),
        INVALID_RESPONSE("failure", "invalid_response"),
        OTHER("failure", "other");

        private final String outcome;
        private final String failure;

        Result(String outcome, String failure) {
            this.outcome = outcome;
            this.failure = failure;
        }
    }
}
