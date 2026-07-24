package ru.itmo.nemat.vkconnector.services;

public class VkApiException extends RuntimeException {

    private final Integer errorCode;

    public VkApiException(String message) {
        super(message);
        this.errorCode = null;
    }

    public VkApiException(int errorCode, String message) {
        super("VK API error %d: %s".formatted(errorCode, message));
        this.errorCode = errorCode;
    }

    public Integer getErrorCode() {
        return errorCode;
    }
}
