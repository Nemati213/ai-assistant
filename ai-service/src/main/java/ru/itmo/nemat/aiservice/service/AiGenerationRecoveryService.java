package ru.itmo.nemat.aiservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.aiservice.model.AiGenerationRequest;
import ru.itmo.nemat.aiservice.repository.AiGenerationRequestRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiGenerationRecoveryService {

    private final AiGenerationRequestRepository repository;

    @Value("${app.ai.recovery.processing-timeout:2m}")
    private Duration processingTimeout;

    @Value("${app.ai.recovery.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.ai.recovery.poll-interval-ms:30000}")
    @Transactional
    public void failStaleRequests() {
        Instant cutoff = Instant.now().minus(processingTimeout);
        List<AiGenerationRequest> stale =
                repository.findStaleProcessing(cutoff, batchSize);
        for (AiGenerationRequest request : stale) {
            request.fail(
                    "AI processing timed out or was interrupted before the result was stored",
                    Instant.now()
            );
            log.error(
                    "[{}] Stale AI generation marked as failed without provider retry",
                    request.getRequestId()
            );
        }
    }
}
