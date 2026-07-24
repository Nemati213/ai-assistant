package ru.itmo.nemat.tgconnector.service;

import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.Test;
import ru.itmo.nemat.tgconnector.config.TelegramRateLimitProperties;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramRateLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    void consumesOneTokenFromSharedBucket() {
        ProxyManager<String> proxyManager = mock(ProxyManager.class);
        BucketProxy bucket = mock(BucketProxy.class);
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        TelegramRateLimitProperties properties = new TelegramRateLimitProperties();
        properties.setBucketKey("telegram:test");

        when(proxyManager.getProxy(eq("telegram:test"), any(Supplier.class)))
                .thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
        when(probe.isConsumed()).thenReturn(true);

        new TelegramRateLimiter(proxyManager, properties).acquire();

        verify(bucket).tryConsumeAndReturnRemaining(1);
    }
}
