package ru.itmo.nemat.aiservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.aiservice.dto.AiGenerationCommand;
import ru.itmo.nemat.aiservice.dto.AiGenerationResult;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;
import ru.itmo.nemat.aiservice.model.AiGenerationStatus;
import ru.itmo.nemat.aiservice.repository.AiGenerationRequestRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiGenerationJournalService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final AiGenerationRequestRepository repository;
    private final AiCommandFingerprint fingerprint;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(AiGenerationCommand command) {
        validate(command);
        String commandFingerprint = fingerprint.calculate(command);
        AiGenerationRequest existing = repository.findByIdForUpdate(command.requestId())
                .orElse(null);
        if (existing != null) {
            if (!existing.getCommandFingerprint().equals(commandFingerprint)) {
                throw new IllegalStateException(
                        "AI requestId was reused with different command data"
                );
            }
            return false;
        }

        Instant now = Instant.now();
        repository.saveAndFlush(AiGenerationRequest.builder()
                .requestId(command.requestId())
                .commandFingerprint(commandFingerprint)
                .vkChatId(command.vkChatId())
                .vkGroupId(command.vkGroupId())
                .status(AiGenerationStatus.PROCESSING)
                .createdAt(now)
                .startedAt(now)
                .publishAttempts(0)
                .nextPublishAttemptAt(now)
                .build());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID requestId, AiGenerationResult result) {
        AiGenerationRequest request = findForUpdate(requestId);
        if (request.getStatus() != AiGenerationStatus.PROCESSING) {
            return;
        }
        request.complete(
                result.answerText(),
                result.tokensUsed(),
                result.providerCostUsd(),
                Instant.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID requestId, Throwable error) {
        AiGenerationRequest request = findForUpdate(requestId);
        if (request.getStatus() != AiGenerationStatus.PROCESSING) {
            return;
        }
        request.fail(normalizeError(error), Instant.now());
    }

    private AiGenerationRequest findForUpdate(UUID requestId) {
        return repository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "AI generation request not found: " + requestId
                ));
    }

    private void validate(AiGenerationCommand command) {
        if (command == null || command.requestId() == null) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (command.vkChatId() == null || command.vkChatId().isBlank()) {
            throw new IllegalArgumentException("vkChatId is required");
        }
        if (command.vkGroupId() == null || command.vkGroupId().isBlank()) {
            throw new IllegalArgumentException("vkGroupId is required");
        }
        if (command.questionText() == null || command.questionText().isBlank()) {
            throw new IllegalArgumentException("questionText is required");
        }
    }

    private String normalizeError(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        if (message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
