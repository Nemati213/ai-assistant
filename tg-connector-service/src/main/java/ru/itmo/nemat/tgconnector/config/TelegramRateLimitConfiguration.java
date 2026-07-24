package ru.itmo.nemat.tgconnector.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TelegramRateLimitProperties.class)
public class TelegramRateLimitConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedisClient telegramRateLimitRedisClient(TelegramRateLimitProperties properties) {
        return RedisClient.create(properties.getRedisUri());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> telegramRateLimitRedisConnection(
            RedisClient telegramRateLimitRedisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(
                StringCodec.UTF8,
                ByteArrayCodec.INSTANCE
        );
        return telegramRateLimitRedisClient.connect(codec);
    }

    @Bean
    public ProxyManager<String> telegramRateLimitProxyManager(
            StatefulRedisConnection<String, byte[]> telegramRateLimitRedisConnection) {
        LettuceBasedProxyManager<String> proxyManager = Bucket4jLettuce
                .casBasedBuilder(telegramRateLimitRedisConnection)
                .build();
        return proxyManager;
    }
}
