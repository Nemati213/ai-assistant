package ru.itmo.nemat.tgconnector.miniapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Component
public class TelegramMiniAppAuthenticator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] WEB_APP_DATA_KEY =
            "WebAppData".getBytes(StandardCharsets.UTF_8);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(30);

    private final String botToken;
    private final Duration maxAge;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TelegramMiniAppAuthenticator(
            @Value("${telegram.bot-token}") String botToken,
            @Value("${app.mini-app.init-data-max-age:10m}") Duration maxAge,
            ObjectMapper objectMapper
    ) {
        this(botToken, maxAge, objectMapper, Clock.systemUTC());
    }

    TelegramMiniAppAuthenticator(
            String botToken,
            Duration maxAge,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.botToken = botToken;
        this.maxAge = maxAge;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public TelegramMiniAppPrincipal authenticate(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new MiniAppAuthenticationException(
                    "Telegram initData header is required"
            );
        }

        Map<String, String> fields = parseQuery(initData);
        String receivedHash = fields.remove("hash");
        if (receivedHash == null || receivedHash.length() != 64) {
            throw new MiniAppAuthenticationException(
                    "Telegram initData hash is missing or invalid"
            );
        }

        byte[] expectedHash = hmac(
                hmac(WEB_APP_DATA_KEY, botToken.getBytes(StandardCharsets.UTF_8)),
                dataCheckString(fields).getBytes(StandardCharsets.UTF_8)
        );
        byte[] actualHash;
        try {
            actualHash = HexFormat.of().parseHex(receivedHash);
        } catch (IllegalArgumentException exception) {
            throw new MiniAppAuthenticationException(
                    "Telegram initData hash is invalid"
            );
        }
        if (!MessageDigest.isEqual(expectedHash, actualHash)) {
            throw new MiniAppAuthenticationException(
                    "Telegram initData signature is invalid"
            );
        }

        Instant authenticatedAt = parseAuthDate(fields.get("auth_date"));
        Instant now = clock.instant();
        if (authenticatedAt.isAfter(now.plus(MAX_FUTURE_SKEW))
                || authenticatedAt.isBefore(now.minus(maxAge))) {
            throw new MiniAppAuthenticationException(
                    "Telegram initData has expired"
            );
        }

        return parseUser(fields.get("user"));
    }

    private Map<String, String> parseQuery(String initData) {
        Map<String, String> fields = new TreeMap<>();
        Arrays.stream(initData.split("&"))
                .forEach(parameter -> {
                    int separator = parameter.indexOf('=');
                    if (separator <= 0) {
                        throw new MiniAppAuthenticationException(
                                "Telegram initData is malformed"
                        );
                    }
                    String key = decode(parameter.substring(0, separator));
                    String value = decode(parameter.substring(separator + 1));
                    if (fields.putIfAbsent(key, value) != null) {
                        throw new MiniAppAuthenticationException(
                                "Telegram initData contains duplicate fields"
                        );
                    }
                });
        return fields;
    }

    private String dataCheckString(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow(() -> new MiniAppAuthenticationException(
                        "Telegram initData is empty"
                ));
    }

    private Instant parseAuthDate(String rawValue) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(rawValue));
        } catch (RuntimeException exception) {
            throw new MiniAppAuthenticationException(
                    "Telegram auth_date is missing or invalid"
            );
        }
    }

    private TelegramMiniAppPrincipal parseUser(String rawUser) {
        if (rawUser == null || rawUser.isBlank()) {
            throw new MiniAppAuthenticationException(
                    "Telegram user is missing"
            );
        }
        try {
            JsonNode user = objectMapper.readTree(rawUser);
            long userId = user.path("id").asLong(0);
            if (userId <= 0) {
                throw new MiniAppAuthenticationException(
                        "Telegram user id is invalid"
                );
            }
            return new TelegramMiniAppPrincipal(
                    userId,
                    textOrNull(user, "username"),
                    textOrNull(user, "first_name"),
                    textOrNull(user, "last_name")
            );
        } catch (MiniAppAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MiniAppAuthenticationException(
                    "Telegram user payload is invalid"
            );
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new MiniAppAuthenticationException(
                    "Telegram initData contains invalid encoding"
            );
        }
    }

    private byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to verify Telegram initData",
                    exception
            );
        }
    }

    public record TelegramMiniAppPrincipal(
            long userId,
            String username,
            String firstName,
            String lastName
    ) {
    }
}
