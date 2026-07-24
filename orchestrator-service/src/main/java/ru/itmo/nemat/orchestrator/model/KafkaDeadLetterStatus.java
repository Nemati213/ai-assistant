package ru.itmo.nemat.orchestrator.model;

public enum KafkaDeadLetterStatus {
    PENDING,
    RETRIED,
    PUBLISH_FAILED,
    EXHAUSTED
}
