package ru.itmo.nemat.vkconnector.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.itmo.nemat.vkconnector.dto.SendVkMessageCommand;
import ru.itmo.nemat.vkconnector.model.VkUserProfile;
import ru.itmo.nemat.vkconnector.repository.VkGroupCredentialsRepository;

import java.util.Optional;
import java.util.OptionalLong;
import java.time.Duration;
import java.net.http.HttpClient;

@Service
@Slf4j
public class VkApiService {

    private final RestClient restClient;
    private final VkGroupCredentialsRepository repository;
    private final String callbackUrl;
    private final String apiVersion;

    public VkApiService(
            VkGroupCredentialsRepository repository,
            @Value("${vk.callback.url}") String callbackUrl,
            @Value("${vk.api-version:5.199}") String apiVersion,
            @Value("${vk.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${vk.http.read-timeout:30s}") Duration readTimeout) {
        this.repository = repository;
        this.callbackUrl = callbackUrl;
        this.apiVersion = apiVersion;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl("https://api.vk.com/method")
                .requestFactory(requestFactory)
                .build();
    }

    public long sendMessage(SendVkMessageCommand command) {
        var credentials = repository.findById(command.vkGroupId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "VK credentials not found for group " + command.vkGroupId()
                ));

        MultiValueMap<String, String> parameters = baseParameters(credentials.getVkToken());
        parameters.add("peer_id", command.vkChatId());
        parameters.add("message", command.text());
        parameters.add("random_id", String.valueOf(command.requestId().hashCode()));

        JsonNode response = execute("messages.send", parameters).path("response");
        long messageId = extractMessageId(response);
        log.info(
                "[{}] Message {} sent to VK chat {}",
                command.requestId(),
                messageId,
                command.vkChatId()
        );
        return messageId;
    }

    public void validateGroupToken(String groupId, String token) {
        MultiValueMap<String, String> parameters = baseParameters(token);
        parameters.add("group_id", groupId);
        execute("groups.getById", parameters);
    }

    public Optional<VkUserProfile> getUserProfile(
            String groupId,
            String userId
    ) {
        var credentials = repository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "VK credentials not found for group " + groupId
                ));
        MultiValueMap<String, String> parameters =
                baseParameters(credentials.getVkToken());
        parameters.add("user_ids", userId);

        JsonNode users = execute("users.get", parameters).path("response");
        if (!users.isArray() || users.isEmpty()) {
            return Optional.empty();
        }
        JsonNode user = users.get(0);
        String firstName = normalizedName(user.path("first_name").asText(null));
        String lastName = normalizedName(user.path("last_name").asText(null));
        if (firstName == null && lastName == null) {
            return Optional.empty();
        }
        return Optional.of(new VkUserProfile(
                firstName,
                lastName,
                displayName(firstName, lastName)
        ));
    }

    public long registerCallbackServer(String groupId, String token, String secret) {
        long serverId = findCallbackServerId(groupId, token)
                .orElseGet(() -> addCallbackServer(groupId, token, secret));

        MultiValueMap<String, String> settingsParameters = baseParameters(token);
        settingsParameters.add("group_id", groupId);
        settingsParameters.add("server_id", String.valueOf(serverId));
        settingsParameters.add("api_version", apiVersion);
        settingsParameters.add("message_new", "1");
        settingsParameters.add("message_reply", "1");
        execute("groups.setCallbackSettings", settingsParameters);

        return serverId;
    }

    public OptionalLong findCallbackServerId(String groupId, String token) {
        MultiValueMap<String, String> parameters = baseParameters(token);
        parameters.add("group_id", groupId);
        JsonNode response = execute("groups.getCallbackServers", parameters).path("response");

        JsonNode items = response.path("items");
        if (!items.isArray() && response.isArray()) {
            items = response;
        }
        if (!items.isArray()) {
            return OptionalLong.empty();
        }

        for (JsonNode item : items) {
            if (callbackUrl.equals(item.path("url").asText())) {
                long serverId = item.path("id").asLong(0);
                if (serverId != 0) {
                    return OptionalLong.of(serverId);
                }
            }
        }
        return OptionalLong.empty();
    }

    public void deleteCallbackServer(String groupId, String token, long serverId) {
        MultiValueMap<String, String> parameters = baseParameters(token);
        parameters.add("group_id", groupId);
        parameters.add("server_id", String.valueOf(serverId));
        execute("groups.deleteCallbackServer", parameters);
    }

    private long addCallbackServer(String groupId, String token, String secret) {
        MultiValueMap<String, String> parameters = baseParameters(token);
        parameters.add("group_id", groupId);
        parameters.add("url", callbackUrl);
        parameters.add("title", "Curator AI Assistant");
        parameters.add("secret_key", secret);

        JsonNode response = execute("groups.addCallbackServer", parameters);
        long serverId = response.path("response").path("server_id").asLong(0);
        if (serverId == 0) {
            throw new VkApiException("VK did not return callback server id");
        }
        return serverId;
    }

    private MultiValueMap<String, String> baseParameters(String token) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("access_token", token);
        parameters.add("v", apiVersion);
        return parameters;
    }

    private long extractMessageId(JsonNode response) {
        long messageId;
        if (response.isIntegralNumber()) {
            messageId = response.asLong();
        } else {
            messageId = response.path("message_id").asLong(0);
        }
        if (messageId == 0) {
            throw new VkApiException("VK did not return sent message id");
        }
        return messageId;
    }

    private String normalizedName(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String displayName(String firstName, String lastName) {
        return String.join(
                " ",
                java.util.stream.Stream.of(firstName, lastName)
                        .filter(value -> value != null && !value.isBlank())
                        .toList()
        );
    }

    private JsonNode execute(String method, MultiValueMap<String, String> parameters) {
        JsonNode response = restClient.post()
                .uri("/" + method)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(parameters)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new VkApiException("VK returned an empty response for " + method);
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode()) {
            int errorCode = error.path("error_code").asInt();
            String errorMessage = error.path("error_msg").asText("Unknown VK API error");
            throw new VkApiException(errorCode, errorMessage);
        }
        if (response.path("response").isMissingNode()) {
            throw new VkApiException("VK returned an invalid response for " + method);
        }
        return response;
    }
}
