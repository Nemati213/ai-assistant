package ru.itmo.nemat.tgconnector.miniapp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;
import ru.itmo.nemat.tgconnector.service.BroadcastService;
import ru.itmo.nemat.tgconnector.service.CuratorDecisionService;
import ru.itmo.nemat.tgconnector.service.StudentDirectoryService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/miniapp")
@Validated
public class MiniAppController {

    public static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final TelegramMiniAppAuthenticator authenticator;
    private final CuratorRepository curatorRepository;
    private final CuratorVkGroupRepository groupRepository;
    private final CuratorDecisionService decisionService;
    private final StudentDirectoryService studentDirectoryService;
    private final BroadcastService broadcastService;

    public MiniAppController(
            TelegramMiniAppAuthenticator authenticator,
            CuratorRepository curatorRepository,
            CuratorVkGroupRepository groupRepository,
            CuratorDecisionService decisionService,
            StudentDirectoryService studentDirectoryService,
            BroadcastService broadcastService
    ) {
        this.authenticator = authenticator;
        this.curatorRepository = curatorRepository;
        this.groupRepository = groupRepository;
        this.decisionService = decisionService;
        this.studentDirectoryService = studentDirectoryService;
        this.broadcastService = broadcastService;
    }

    @GetMapping("/session")
    public SessionResponse session(
            @RequestHeader(INIT_DATA_HEADER) String initData
    ) {
        var telegramUser = authenticator.authenticate(initData);
        Curator curator = requireCurator(telegramUser.userId());
        List<GroupResponse> groups = groups(telegramUser.userId()).stream()
                .map(this::toGroup)
                .toList();
        return new SessionResponse(
                curator.getTgChatId(),
                telegramUser.username(),
                telegramUser.firstName(),
                telegramUser.lastName(),
                curator.getSubject().getCode(),
                curator.getSubject().getName(),
                curator.getBalanceTokens(),
                curator.getReservedTokens(),
                groups
        );
    }

    @GetMapping("/decisions")
    public List<CuratorDecisionService.DecisionListView> decisions(
            @RequestHeader(INIT_DATA_HEADER) String initData
    ) {
        return decisionService.pending(authenticate(initData));
    }

    @PutMapping("/decisions/{requestId}")
    public CuratorDecisionService.DecisionView editDecision(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @PathVariable UUID requestId,
            @Valid @RequestBody EditDecisionRequest request
    ) {
        return decisionService.editFromMiniApp(
                        requestId,
                        authenticate(initData),
                        request.revision(),
                        request.answer()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Decision was already changed in another client"
                ));
    }

    @PostMapping("/decisions/{requestId}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void approveDecision(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @PathVariable UUID requestId,
            @Valid @RequestBody DecisionActionRequest request
    ) {
        queueDecision(initData, requestId, request.revision(), "APPROVED");
    }

    @PostMapping("/decisions/{requestId}/reject")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rejectDecision(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @PathVariable UUID requestId,
            @Valid @RequestBody DecisionActionRequest request
    ) {
        queueDecision(initData, requestId, request.revision(), "REJECTED");
    }

    @GetMapping("/students")
    public StudentDirectoryService.StudentPage students(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam(required = false) String vkGroupId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        long tgChatId = authenticate(initData);
        return studentDirectoryService.page(
                tgChatId,
                resolveGroup(tgChatId, vkGroupId).getVkGroupId(),
                page,
                size
        );
    }

    @PostMapping("/broadcasts")
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastDraftResponse beginBroadcast(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @Valid @RequestBody BeginBroadcastRequest request
    ) {
        long tgChatId = authenticate(initData);
        UUID campaignId = broadcastService.begin(
                tgChatId,
                resolveGroup(tgChatId, request.vkGroupId()).getVkGroupId()
        );
        return new BroadcastDraftResponse(campaignId);
    }

    @GetMapping("/broadcasts/active")
    public BroadcastService.SelectionPage activeBroadcast(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(20) int size
    ) {
        return broadcastService.selectionPage(
                authenticate(initData),
                page,
                size
        );
    }

    @PutMapping("/broadcasts/active/recipients/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBroadcastRecipient(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @PathVariable UUID studentId
    ) {
        broadcastService.toggleRecipient(authenticate(initData), studentId);
    }

    @PostMapping("/broadcasts/active/recipients/complete")
    public BroadcastService.TextRequest completeBroadcastRecipients(
            @RequestHeader(INIT_DATA_HEADER) String initData
    ) {
        return broadcastService.requestText(authenticate(initData));
    }

    @PutMapping("/broadcasts/active/message")
    public BroadcastService.Preview updateBroadcastMessage(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @Valid @RequestBody BroadcastMessageRequest request
    ) {
        return broadcastService.acceptText(
                authenticate(initData),
                request.messageTemplate()
        );
    }

    @PostMapping("/broadcasts/active/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BroadcastService.QueueResult sendBroadcast(
            @RequestHeader(INIT_DATA_HEADER) String initData
    ) {
        return broadcastService.queue(authenticate(initData));
    }

    @DeleteMapping("/broadcasts/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelBroadcast(
            @RequestHeader(INIT_DATA_HEADER) String initData
    ) {
        if (!broadcastService.cancel(authenticate(initData))) {
            throw new IllegalStateException(
                    "Broadcast cannot be cancelled in its current state"
            );
        }
    }

    @GetMapping("/broadcasts")
    public List<BroadcastService.CampaignSummary> broadcastHistory(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        return broadcastService.history(authenticate(initData), limit);
    }

    private void queueDecision(
            String initData,
            UUID requestId,
            int revision,
            String status
    ) {
        if (!decisionService.queueDecision(
                requestId,
                authenticate(initData),
                revision,
                status
        )) {
            throw new IllegalStateException(
                    "Decision was already changed in another client"
            );
        }
    }

    private long authenticate(String initData) {
        long tgChatId = authenticator.authenticate(initData).userId();
        requireCurator(tgChatId);
        return tgChatId;
    }

    private Curator requireCurator(long tgChatId) {
        return curatorRepository.findByTgChatId(tgChatId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Telegram user is not registered as a curator"
                ));
    }

    private CuratorVkGroup resolveGroup(long tgChatId, String vkGroupId) {
        List<CuratorVkGroup> groups = groups(tgChatId);
        if (groups.isEmpty()) {
            throw new IllegalStateException(
                    "Curator does not have a connected VK group"
            );
        }
        if (vkGroupId == null || vkGroupId.isBlank()) {
            return groups.get(0);
        }
        return groups.stream()
                .filter(group -> group.getVkGroupId().equals(vkGroupId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "VK group is not connected to this curator"
                ));
    }

    private List<CuratorVkGroup> groups(long tgChatId) {
        return groupRepository.findAllByCuratorTgChatIdOrderByVkGroupId(tgChatId);
    }

    private GroupResponse toGroup(CuratorVkGroup group) {
        return new GroupResponse(
                group.getVkGroupId(),
                group.getStatus().name(),
                group.getLastError()
        );
    }

    public record SessionResponse(
            long tgChatId,
            String username,
            String firstName,
            String lastName,
            String subjectCode,
            String subjectName,
            BigDecimal balanceCredits,
            BigDecimal reservedCredits,
            List<GroupResponse> vkGroups
    ) {
    }

    public record GroupResponse(
            String vkGroupId,
            String status,
            String lastError
    ) {
    }

    public record EditDecisionRequest(
            @Min(0) int revision,
            @NotBlank String answer
    ) {
    }

    public record DecisionActionRequest(@Min(0) int revision) {
    }

    public record BeginBroadcastRequest(@NotBlank String vkGroupId) {
    }

    public record BroadcastDraftResponse(@NotNull UUID campaignId) {
    }

    public record BroadcastMessageRequest(
            @NotBlank String messageTemplate
    ) {
    }
}
