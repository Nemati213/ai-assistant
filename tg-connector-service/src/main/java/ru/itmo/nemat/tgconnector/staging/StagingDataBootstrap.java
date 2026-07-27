package ru.itmo.nemat.tgconnector.staging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.model.RegistrationContext;
import ru.itmo.nemat.tgconnector.model.RegistrationState;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;
import ru.itmo.nemat.tgconnector.repository.SubjectRepository;
import ru.itmo.nemat.tgconnector.service.VkGroupManagementService;

import java.math.BigDecimal;

@Component
@Profile("staging")
@ConditionalOnProperty(
        name = "staging.bootstrap.enabled",
        havingValue = "true"
)
@Slf4j
public class StagingDataBootstrap implements ApplicationRunner {

    private static final String REQUIRED_CONFIRMATION = "isolated-staging-only";

    private final CuratorRepository curatorRepository;
    private final CuratorVkGroupRepository groupRepository;
    private final SubjectRepository subjectRepository;
    private final VkGroupManagementService groupManagementService;
    private final String confirmation;
    private final Long tgChatId;
    private final String vkGroupId;
    private final String vkToken;
    private final String vkSecret;
    private final String vkConfirmationCode;
    private final String subjectCode;
    private final BigDecimal balance;

    public StagingDataBootstrap(
            CuratorRepository curatorRepository,
            CuratorVkGroupRepository groupRepository,
            SubjectRepository subjectRepository,
            VkGroupManagementService groupManagementService,
            @Value("${staging.bootstrap.confirm:}") String confirmation,
            @Value("${staging.bootstrap.tg-chat-id:900000001}") Long tgChatId,
            @Value("${staging.bootstrap.vk-group-id:900001}") String vkGroupId,
            @Value("${staging.bootstrap.vk-token:staging-vk-token}") String vkToken,
            @Value("${staging.bootstrap.vk-secret:staging-vk-secret}") String vkSecret,
            @Value("${staging.bootstrap.vk-confirmation-code:staging-confirmation}")
            String vkConfirmationCode,
            @Value("${staging.bootstrap.subject-code:MATH}") String subjectCode,
            @Value("${staging.bootstrap.balance:1000000000}") BigDecimal balance
    ) {
        this.curatorRepository = curatorRepository;
        this.groupRepository = groupRepository;
        this.subjectRepository = subjectRepository;
        this.groupManagementService = groupManagementService;
        this.confirmation = confirmation;
        this.tgChatId = tgChatId;
        this.vkGroupId = vkGroupId;
        this.vkToken = vkToken;
        this.vkSecret = vkSecret;
        this.vkConfirmationCode = vkConfirmationCode;
        this.subjectCode = subjectCode;
        this.balance = balance;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        requireSafeConfiguration();

        if (!groupRepository.existsByVkGroupId(vkGroupId)) {
            var subject = subjectRepository.findByCode(subjectCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "Staging subject not found: " + subjectCode
                    ));
            var context = RegistrationContext.builder()
                    .tgChatId(tgChatId)
                    .state(RegistrationState.AWAITING_VK_CONFIRMATION)
                    .subjectId(subject.getId())
                    .username("staging-curator")
                    .vkGroupId(vkGroupId)
                    .vkToken(vkToken)
                    .vkSecret(vkSecret)
                    .vkConfirmationCode(vkConfirmationCode)
                    .build();
            groupManagementService.registerGroup(tgChatId, context, subject);
        }

        var curator = curatorRepository.findByTgChatIdForUpdate(tgChatId)
                .orElseThrow(() -> new IllegalStateException(
                        "Staging curator was not created"
                ));
        curator.setBalanceTokens(balance);
        curatorRepository.save(curator);

        log.info(
                "Isolated staging data is ready for VK group {} and curator chat {}",
                vkGroupId,
                tgChatId
        );
    }

    private void requireSafeConfiguration() {
        if (!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "Staging bootstrap requires explicit isolated environment confirmation"
            );
        }
        if (vkGroupId == null || vkGroupId.isBlank()
                || vkSecret == null || vkSecret.isBlank()) {
            throw new IllegalStateException(
                    "Staging VK group id and secret must be configured"
            );
        }
        if (balance.signum() <= 0) {
            throw new IllegalStateException(
                    "Staging curator balance must be positive"
            );
        }
    }
}
