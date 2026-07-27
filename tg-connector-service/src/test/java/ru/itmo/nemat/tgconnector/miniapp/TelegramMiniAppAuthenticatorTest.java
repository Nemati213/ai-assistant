package ru.itmo.nemat.tgconnector.miniapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramMiniAppAuthenticatorTest {

    private static final String BOT_TOKEN = "123456:test-token";
    private static final Instant NOW = Instant.parse("2026-07-27T14:00:00Z");

    private TelegramMiniAppAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        authenticator = new TelegramMiniAppAuthenticator(
                BOT_TOKEN,
                Duration.ofMinutes(10),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void authenticatesSignedTelegramUser() {
        String initData = signedInitData(
                NOW.minusSeconds(30),
                """
                        {"id":55,"first_name":"Nemat","username":"nemati213"}
                        """
        );

        var principal = authenticator.authenticate(initData);

        assertThat(principal.userId()).isEqualTo(55L);
        assertThat(principal.firstName()).isEqualTo("Nemat");
        assertThat(principal.username()).isEqualTo("nemati213");
    }

    @Test
    void rejectsTamperedUser() {
        String initData = signedInitData(
                NOW.minusSeconds(30),
                """
                        {"id":55,"first_name":"Nemat"}
                        """
        ).replace("%22id%22%3A55", "%22id%22%3A77");

        assertThatThrownBy(() -> authenticator.authenticate(initData))
                .isInstanceOf(MiniAppAuthenticationException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rejectsExpiredInitData() {
        String initData = signedInitData(
                NOW.minus(Duration.ofMinutes(11)),
                """
                        {"id":55,"first_name":"Nemat"}
                        """
        );

        assertThatThrownBy(() -> authenticator.authenticate(initData))
                .isInstanceOf(MiniAppAuthenticationException.class)
                .hasMessageContaining("expired");
    }

    private String signedInitData(Instant authDate, String userJson) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("query_id", "AAHdF6IQAAAAAN0XohDhrOrc");
        fields.put("user", userJson.strip());
        fields.put("auth_date", Long.toString(authDate.getEpochSecond()));

        String checkString = new TreeMap<>(fields).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        byte[] secret = hmac(
                "WebAppData".getBytes(StandardCharsets.UTF_8),
                BOT_TOKEN.getBytes(StandardCharsets.UTF_8)
        );
        String hash = HexFormat.of().formatHex(hmac(
                secret,
                checkString.getBytes(StandardCharsets.UTF_8)
        ));

        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow()
                + "&hash="
                + hash;
    }

    private byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
