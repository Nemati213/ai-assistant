package ru.itmo.nemat.tgconnector.bot;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.itmo.nemat.tgconnector.config.AdminTelegramProperties;
import ru.itmo.nemat.tgconnector.service.AdminCuratorService;
import ru.itmo.nemat.tgconnector.service.TelegramRateLimiter;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public class AdminTelegramBot extends TelegramLongPollingBot {

    private static final String HELP_TEXT = """
            Админ-панель Curator AI

            /curators [страница] - список кураторов и балансы
            /user <telegram_id> - карточка куратора
            /add_tokens <telegram_id> <количество> [причина] - начислить токены
            /stats - общая статистика
            /help - список команд
            """;

    private final AdminTelegramProperties properties;
    private final AdminCuratorService adminService;
    private final TelegramRateLimiter rateLimiter;

    public AdminTelegramBot(
            AdminTelegramProperties properties,
            AdminCuratorService adminService,
            TelegramRateLimiter rateLimiter
    ) {
        super(properties.getBotToken());
        this.properties = properties;
        this.adminService = adminService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String getBotUsername() {
        return properties.getBotName();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        User actor = message.getFrom();
        Long actorId = actor == null ? null : actor.getId();

        if (!properties.isAllowed(actorId)) {
            log.warn("Denied admin bot access for Telegram user {}", actorId);
            return;
        }
        if (message.getChat() == null || !message.getChat().isUserChat()) {
            sendText(message.getChatId(), "Админские команды доступны только в личном чате.");
            return;
        }
        if (!message.hasText()) {
            sendText(message.getChatId(), HELP_TEXT);
            return;
        }

        String commandText = message.getText().strip();
        String[] parts = commandText.split("\\s+");
        String command = normalizeCommand(parts[0]);

        try {
            switch (command) {
                case "/start", "/help" -> sendText(message.getChatId(), HELP_TEXT);
                case "/curators", "/users" -> handleCurators(message.getChatId(), parts);
                case "/user" -> handleUser(message.getChatId(), parts);
                case "/add_tokens" -> handleAddTokens(update, message, actor, parts);
                case "/stats" -> handleStats(message.getChatId());
                default -> sendText(
                        message.getChatId(),
                        "Неизвестная команда.\n\n" + HELP_TEXT
                );
            }
        } catch (IllegalArgumentException exception) {
            sendText(message.getChatId(), exception.getMessage());
        } catch (Exception exception) {
            log.error("Admin command failed for update {}", update.getUpdateId(), exception);
            sendText(
                    message.getChatId(),
                    "Операция не выполнена. Проверьте данные или журнал сервиса."
            );
        }
    }

    private void handleCurators(Long chatId, String[] parts) {
        if (parts.length > 2) {
            throw new IllegalArgumentException("Использование: /curators [страница]");
        }

        int page = parts.length == 1 ? 1 : parsePage(parts[1]);
        AdminCuratorService.CuratorListView list = adminService.listCurators(page);
        if (list.curators().isEmpty()) {
            sendText(chatId, "Кураторы не найдены.");
            return;
        }

        String rows = list.curators().stream()
                .map(curator -> """
                        Telegram ID: %s
                        Username: %s
                        Предмет: %s
                        Баланс: %s
                        Доступно: %s
                        Сообщества: %d, активных: %d
                        """.formatted(
                        curator.tgChatId(),
                        displayUsername(curator.username()),
                        curator.subject(),
                        format(curator.balance()),
                        format(curator.available()),
                        curator.groups(),
                        curator.activeGroups()
                ))
                .collect(Collectors.joining("\n"));

        sendText(chatId, """
                Кураторы, страница %d/%d
                Всего: %d

                %s
                """.formatted(
                list.page(),
                list.totalPages(),
                list.total(),
                rows
        ));
    }

    private void handleUser(Long chatId, String[] parts) {
        if (parts.length != 2) {
            throw new IllegalArgumentException("Использование: /user <telegram_id>");
        }
        Long targetId = parseTelegramId(parts[1]);
        AdminCuratorService.CuratorView curator = adminService.findCurator(targetId);

        String groups = curator.groups().isEmpty()
                ? "нет"
                : curator.groups().stream()
                .map(group -> {
                    String suffix = group.lastError() == null
                            || group.lastError().isBlank()
                            ? ""
                            : " (" + group.lastError() + ")";
                    return group.vkGroupId() + ": " + group.status() + suffix;
                })
                .collect(Collectors.joining("\n"));

        sendText(chatId, """
                Куратор
                Telegram ID: %s
                Username: %s
                Предмет: %s
                Баланс: %s
                В резерве: %s
                Доступно: %s

                Сообщества:
                %s
                """.formatted(
                curator.tgChatId(),
                displayUsername(curator.username()),
                curator.subject(),
                format(curator.balance()),
                format(curator.reserved()),
                format(curator.available()),
                groups
        ));
    }

    private void handleAddTokens(
            Update update,
            Message message,
            User actor,
            String[] parts
    ) {
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "Использование: /add_tokens <telegram_id> <количество> [причина]"
            );
        }

        Long targetId = parseTelegramId(parts[1]);
        BigDecimal amount = parseAmount(parts[2]);
        String reason = parts.length > 3
                ? String.join(" ", Arrays.copyOfRange(parts, 3, parts.length))
                : null;
        String operationKey =
                "admin:" + actor.getId() + ":telegram-update:" + update.getUpdateId();

        AdminCuratorService.ManualCreditResult result = adminService.addTokens(
                actor.getId(),
                actor.getUserName(),
                targetId,
                amount,
                reason,
                operationKey
        );

        String prefix = result.newlyCredited()
                ? "Начисление выполнено."
                : "Эта команда уже была обработана.";
        sendText(
                message.getChatId(),
                prefix
                        + "\nНачислено: " + format(result.credited())
                        + "\nНовый баланс: " + format(result.balanceAfter())
        );
    }

    private void handleStats(Long chatId) {
        AdminCuratorService.AdminStats stats = adminService.stats();
        sendText(chatId, """
                Статистика
                Кураторов: %d
                Сообществ: %d
                Активных сообществ: %d
                """.formatted(
                stats.curators(),
                stats.groups(),
                stats.activeGroups()
        ));
    }

    private String normalizeCommand(String command) {
        int botNameSeparator = command.indexOf('@');
        return botNameSeparator < 0
                ? command.toLowerCase()
                : command.substring(0, botNameSeparator).toLowerCase();
    }

    private int parsePage(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Номер страницы должен быть положительным целым числом."
            );
        }
    }

    private Long parseTelegramId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Telegram ID должен быть положительным целым числом."
            );
        }
    }

    private BigDecimal parseAmount(String value) {
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Количество токенов должно быть числом."
            );
        }
    }

    private String displayUsername(String username) {
        return username == null || username.isBlank()
                ? "не указан"
                : "@" + username;
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void sendText(Long chatId, String text) {
        try {
            rateLimiter.acquire();
            execute(new SendMessage(chatId.toString(), text));
        } catch (TelegramApiException exception) {
            log.error("Failed to send admin bot message to chat {}", chatId, exception);
        }
    }
}
