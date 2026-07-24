package ru.itmo.nemat.vkconnector.services;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;

@Component
public class VkDeliveryFailureClassifier {

    private static final Set<Integer> RETRYABLE_VK_CODES =
            Set.of(1, 6, 9, 10, 29);

    public Classification classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof VkApiException vkError) {
                Integer code = vkError.getErrorCode();
                return new Classification(
                        code == null || RETRYABLE_VK_CODES.contains(code),
                        code == null ? "VK_PROTOCOL" : "VK_API_" + code
                );
            }
            if (current instanceof RestClientResponseException responseError) {
                HttpStatusCode status = responseError.getStatusCode();
                boolean retryable = status.value() == 408
                        || status.value() == 429
                        || status.is5xxServerError();
                return new Classification(
                        retryable,
                        "HTTP_" + status.value()
                );
            }
            if (current instanceof ResourceAccessException
                    || current instanceof SocketTimeoutException
                    || current instanceof IOException) {
                return new Classification(true, "NETWORK");
            }
            if (current instanceof RestClientException) {
                return new Classification(true, "HTTP_CLIENT");
            }
            current = current.getCause();
        }
        return new Classification(false, "UNEXPECTED");
    }

    public record Classification(boolean retryable, String category) {
    }
}
