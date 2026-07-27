package ru.itmo.nemat.tgconnector.staging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.tgconnector.dto.CuratorApprovalRequest;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeRequest;
import ru.itmo.nemat.tgconnector.service.CuratorDecisionService;
import ru.itmo.nemat.tgconnector.service.CuratorIntakeService;
import ru.itmo.nemat.tgconnector.service.CuratorRoutingService;

@Component
@Profile("staging")
@ConditionalOnProperty(
        name = "curator.workflow.mode",
        havingValue = "automatic"
)
@Slf4j
public class StagingAutoCuratorConsumer {

    private static final String REQUIRED_CONFIRMATION = "isolated-staging-only";

    private final ObjectMapper objectMapper;
    private final CuratorRoutingService routingService;
    private final CuratorIntakeService intakeService;
    private final CuratorDecisionService decisionService;

    public StagingAutoCuratorConsumer(
            ObjectMapper objectMapper,
            CuratorRoutingService routingService,
            CuratorIntakeService intakeService,
            CuratorDecisionService decisionService,
            @Value("${staging.auto-curator.confirm:}") String confirmation
    ) {
        if (!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "Automatic curator requires explicit isolated environment confirmation"
            );
        }
        this.objectMapper = objectMapper;
        this.routingService = routingService;
        this.intakeService = intakeService;
        this.decisionService = decisionService;
    }

    @KafkaListener(
            topics = "curator-intake-requests",
            groupId = "staging-auto-curator-intake"
    )
    public void acceptIntake(String messageJson) {
        try {
            CuratorIntakeRequest request =
                    objectMapper.readValue(messageJson, CuratorIntakeRequest.class);
            long tgChatId = routingService.resolve(request.vkGroupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No staging curator route for VK group "
                                    + request.vkGroupId()
                    ))
                    .tgChatId();
            intakeService.prepare(request, tgChatId);
            if (intakeService.queueAi(request.requestId(), tgChatId)) {
                log.debug(
                        "[{}] Staging curator sent intake request to AI",
                        request.requestId()
                );
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to process staging curator intake request",
                    exception
            );
        }
    }

    @KafkaListener(
            topics = "curator-approval-requests",
            groupId = "staging-auto-curator-approval"
    )
    public void approveAnswer(String messageJson) {
        try {
            CuratorApprovalRequest request =
                    objectMapper.readValue(messageJson, CuratorApprovalRequest.class);
            long tgChatId = routingService.resolveRegistered(request.vkGroupId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No staging curator route for VK group "
                                    + request.vkGroupId()
                    ))
                    .tgChatId();
            decisionService.prepareApproval(request, tgChatId)
                    .ifPresent(view -> {
                        boolean queued = decisionService.queueDecision(
                                request.requestId(),
                                tgChatId,
                                view.revision(),
                                "APPROVED"
                        );
                        if (!queued) {
                            throw new IllegalStateException(
                                    "Staging approval could not be queued"
                            );
                        }
                    });
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to process staging curator approval request",
                    exception
            );
        }
    }
}
