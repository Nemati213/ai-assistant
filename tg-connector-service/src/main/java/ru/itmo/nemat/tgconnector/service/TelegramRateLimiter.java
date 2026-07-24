package ru.itmo.nemat.tgconnector.service;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.tgconnector.config.TelegramRateLimitProperties;

import java.util.concurrent.TimeUnit;

@Service
public class TelegramRateLimiter {

    private final ProxyManager<String> proxyManager;
    private final TelegramRateLimitProperties properties;
    private final BucketConfiguration bucketConfiguration;

    public TelegramRateLimiter(
            ProxyManager<String> proxyManager,
            TelegramRateLimitProperties properties) {
        this.proxyManager = proxyManager;
        this.properties = properties;
        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(limit -> limit
                        .capacity(properties.getCapacity())
                        .refillGreedy(
                                properties.getRefillTokens(),
                                properties.getRefillPeriod()
                        ))
                .build();
    }

    public void acquire() {
        Bucket bucket = proxyManager.getProxy(
                properties.getBucketKey(),
                () -> bucketConfiguration
        );

        while (true) {
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return;
            }
            sleepNanos(probe.getNanosToWaitForRefill());
        }
    }

    private void sleepNanos(long nanos) {
        try {
            long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Telegram rate limit", exception);
        }
    }
}
