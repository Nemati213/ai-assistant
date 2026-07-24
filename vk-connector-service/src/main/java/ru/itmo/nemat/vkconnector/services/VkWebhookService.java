package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.vkconnector.model.VkGroupCredentials;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;
import ru.itmo.nemat.vkconnector.dto.VkCallbackRequest;
import ru.itmo.nemat.vkconnector.dto.VkMessage;
import ru.itmo.nemat.vkconnector.dto.VkMessageObject;
import ru.itmo.nemat.vkconnector.model.VkMessageEvent;
import ru.itmo.nemat.vkconnector.model.StudentConversationMessageEvent;
import ru.itmo.nemat.vkconnector.model.VkUserProfile;
import ru.itmo.nemat.shared.logging.RequestMdcScope;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class VkWebhookService {

    private static final Logger log = LoggerFactory.getLogger(VkWebhookService.class);

    private final VkGroupCredentialsRepository repository;
    private final ObjectMapper objectMapper;
    private final VkRequestIdFactory requestIdFactory;
    private final VkPhotoUrlExtractor photoUrlExtractor;
    private final VkWebhookOutboxService webhookOutboxService;
    private final VkUserProfileService userProfileService;

    public VkWebhookService(
            VkGroupCredentialsRepository repository,
            ObjectMapper objectMapper,
            VkRequestIdFactory requestIdFactory,
            VkPhotoUrlExtractor photoUrlExtractor,
            VkWebhookOutboxService webhookOutboxService,
            VkUserProfileService userProfileService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requestIdFactory = requestIdFactory;
        this.photoUrlExtractor = photoUrlExtractor;
        this.webhookOutboxService = webhookOutboxService;
        this.userProfileService = userProfileService;
    }

    public String handleWebhook(VkCallbackRequest request) {
        VkGroupCredentials credentials = repository.findById(request.groupId())
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (!secretsMatch(request.secret(), credentials.getVkSecret())) {
            log.warn("Invalid secret in VK callback for group {}", request.groupId());
            throw new IllegalArgumentException("Invalid secret key");
        }

        return switch (request.type()) {
            case "confirmation" -> {
                log.info("Returning confirmation code for VK group {}", request.groupId());
                yield credentials.getVkConfirmationCode();
            }
            case "message_new" -> {
                processMessageNew(request);
                yield "ok";
            }
            case "message_reply" -> {
                processMessageReply(request);
                yield "ok";
            }
            default -> {
                log.debug("Ignoring unsupported VK event type {}", request.type());
                yield "ok";
            }
        };
    }

    private void processMessageNew(VkCallbackRequest request) {
        try {
            VkMessageObject messageObject = objectMapper.treeToValue(request.object(), VkMessageObject.class);
            VkMessage message = messageObject.message();
            UUID requestId = requestIdFactory.create(request, message);

            try (RequestMdcScope ignored = RequestMdcScope.open(requestId)) {
                List<String> photoUrls = photoUrlExtractor.extract(message.attachments());
                VkUserProfile profile = userProfileService.resolve(
                        request.groupId(),
                        message.fromId()
                ).orElse(null);

                VkMessageEvent event = new VkMessageEvent(
                        requestId,
                        message.peerId(),
                        message.fromId(),
                        message.text(),
                        request.groupId(),
                        message.date(),
                        photoUrls
                );

                StudentConversationMessageEvent conversation =
                        new StudentConversationMessageEvent(
                        requestId,
                        "USER",
                        message.peerId(),
                        message.fromId(),
                        request.groupId(),
                        firstName(profile),
                        lastName(profile),
                        displayName(profile),
                        message.text(),
                        photoUrls,
                        message.id(),
                        "VK_MESSAGE_NEW",
                        toInstant(message.date())
                );
                log.info("Accepted VK message_new event");
                webhookOutboxService.enqueueMessageNew(event, conversation);
            }

        } catch (Exception e) {
            log.error("Failed to process VK message_new event {}", request.eventId(), e);
            throw new IllegalStateException("Failed to process VK message_new event", e);
        }
    }

    private void processMessageReply(VkCallbackRequest request) {
        try {
            VkMessage message = extractMessage(request);
            String vkUserId = directDialogUserId(message.peerId());
            if (vkUserId == null) {
                log.info(
                        "Skipping VK message_reply history for non-direct peer {}",
                        message.peerId()
                );
                return;
            }

            UUID requestId = requestIdFactory.create(request, message);
            try (RequestMdcScope ignored = RequestMdcScope.open(requestId)) {
                List<String> photoUrls =
                        photoUrlExtractor.extract(message.attachments());
                VkUserProfile profile = userProfileService.resolve(
                        request.groupId(),
                        vkUserId
                ).orElse(null);
                webhookOutboxService.enqueueConversation(
                        new StudentConversationMessageEvent(
                        requestId,
                        "ASSISTANT",
                        message.peerId(),
                        vkUserId,
                        request.groupId(),
                        firstName(profile),
                        lastName(profile),
                        displayName(profile),
                        message.text(),
                        photoUrls,
                        message.id(),
                        "VK_MESSAGE_REPLY",
                        toInstant(message.date())
                ));
                log.info("Accepted VK message_reply event");
            }
        } catch (Exception exception) {
            log.error(
                    "Failed to process VK message_reply event {}",
                    request.eventId(),
                    exception
            );
            throw new IllegalStateException(
                    "Failed to process VK message_reply event",
                    exception
            );
        }
    }

    private VkMessage extractMessage(VkCallbackRequest request) throws Exception {
        if (request.object() == null || request.object().isNull()) {
            throw new IllegalArgumentException("VK callback object is required");
        }

        VkMessage message;
        if (request.object().hasNonNull("message")) {
            VkMessageObject messageObject =
                    objectMapper.treeToValue(request.object(), VkMessageObject.class);
            message = messageObject.message();
        } else {
            message = objectMapper.treeToValue(request.object(), VkMessage.class);
        }

        if (message == null || message.peerId() == null) {
            throw new IllegalArgumentException(
                    "VK callback does not contain a valid message"
            );
        }
        return message;
    }

    private String directDialogUserId(String peerId) {
        try {
            long value = Long.parseLong(peerId);
            return value > 0 && value < 2_000_000_000L
                    ? peerId
                    : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Instant toInstant(Long epochSeconds) {
        return epochSeconds == null
                ? Instant.now()
                : Instant.ofEpochSecond(epochSeconds);
    }

    private String firstName(VkUserProfile profile) {
        return profile == null ? null : profile.firstName();
    }

    private String lastName(VkUserProfile profile) {
        return profile == null ? null : profile.lastName();
    }

    private String displayName(VkUserProfile profile) {
        return profile == null ? null : profile.displayName();
    }

    private boolean secretsMatch(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

}
