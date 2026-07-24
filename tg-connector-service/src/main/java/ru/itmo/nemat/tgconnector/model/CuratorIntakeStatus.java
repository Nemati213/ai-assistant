package ru.itmo.nemat.tgconnector.model;

public enum CuratorIntakeStatus {
    AWAITING_ACTION,
    AWAITING_MANUAL_REPLY,
    DECISION_QUEUED,
    MANUAL_DELIVERY_FAILED,
    RECOVERY_ACTION_QUEUED,
    COMPLETED,
    CANCELLED
}
