package ru.itmo.nemat.tgconnector.model;

public enum RegistrationState {
    NONE,
    AWAITING_SUBJECT,
    AWAITING_VK_GROUP_ID,
    AWAITING_VK_TOKEN,
    AWAITING_VK_SECRET,
    AWAITING_VK_CONFIRMATION
}