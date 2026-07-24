package ru.itmo.nemat.vkconnector.services;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class VkDeliveryFailureClassifierTest {

    private final VkDeliveryFailureClassifier classifier =
            new VkDeliveryFailureClassifier();

    @Test
    void classifiesVkRateLimitAndInternalErrorsAsRetryable() {
        assertThat(classifier.classify(
                new VkApiException(6, "too many requests")
        ).retryable()).isTrue();
        assertThat(classifier.classify(
                new VkApiException(10, "internal server error")
        ).retryable()).isTrue();
    }

    @Test
    void classifiesPermissionAndMessagingErrorsAsPermanent() {
        assertThat(classifier.classify(
                new VkApiException(5, "authorization failed")
        ).retryable()).isFalse();
        assertThat(classifier.classify(
                new VkApiException(901, "cannot send messages")
        ).retryable()).isFalse();
    }

    @Test
    void classifiesNetworkTimeoutAndServerErrorAsRetryable() {
        assertThat(classifier.classify(
                new ResourceAccessException(
                        "timeout",
                        new SocketTimeoutException("read timed out")
                )
        ).retryable()).isTrue();
        assertThat(classifier.classify(
                new HttpServerErrorException(HttpStatus.BAD_GATEWAY)
        ).retryable()).isTrue();
    }
}
