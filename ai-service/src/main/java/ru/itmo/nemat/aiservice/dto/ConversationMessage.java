package ru.itmo.nemat.aiservice.dto;

public record ConversationMessage(
        String role,
        String text
) {
}
