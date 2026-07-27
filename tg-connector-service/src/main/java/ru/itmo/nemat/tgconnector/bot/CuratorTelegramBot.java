package ru.itmo.nemat.tgconnector.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import ru.itmo.nemat.tgconnector.config.TelegramRateLimitProperties;
import ru.itmo.nemat.tgconnector.config.TelegramStarsProperties;
import ru.itmo.nemat.tgconnector.dto.CuratorApprovalRequest;
import ru.itmo.nemat.tgconnector.dto.CuratorIntakeRequest;
import ru.itmo.nemat.tgconnector.dto.CuratorSystemNotificationCommand;
import ru.itmo.nemat.tgconnector.model.Curator;
import ru.itmo.nemat.tgconnector.model.CuratorVkGroup;
import ru.itmo.nemat.tgconnector.model.RegistrationContext;
import ru.itmo.nemat.tgconnector.model.RegistrationState;
import ru.itmo.nemat.tgconnector.model.Subject;
import ru.itmo.nemat.tgconnector.model.VkGroupStatus;
import ru.itmo.nemat.tgconnector.repository.CuratorRepository;
import ru.itmo.nemat.tgconnector.repository.CuratorVkGroupRepository;
import ru.itmo.nemat.tgconnector.repository.SubjectRepository;
import ru.itmo.nemat.tgconnector.service.BalanceService;
import ru.itmo.nemat.tgconnector.service.BalanceCreditService;
import ru.itmo.nemat.tgconnector.service.BroadcastService;
import ru.itmo.nemat.tgconnector.service.CuratorDecisionService;
import ru.itmo.nemat.tgconnector.service.CuratorIntakeService;
import ru.itmo.nemat.tgconnector.service.CuratorRoutingService;
import ru.itmo.nemat.tgconnector.service.RegistrationStateManager;
import ru.itmo.nemat.tgconnector.service.StudentDirectoryService;
import ru.itmo.nemat.tgconnector.service.TelegramRateLimiter;
import ru.itmo.nemat.tgconnector.service.TelegramStarsInvoiceClient;
import ru.itmo.nemat.tgconnector.service.VkGroupManagementService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class CuratorTelegramBot extends TelegramLongPollingBot {

    private static final String MENU_BALANCE = "Баланс";
    private static final String MENU_BUY = "Купить токены";
    private static final String MENU_GROUPS = "Мои сообщества";
    private static final String MENU_ADD_GROUP = "Добавить сообщество";
    private static final String MENU_STUDENTS = "Ученики";
    private static final String MENU_BROADCAST = "Рассылка";
    private static final String MENU_APP = "Открыть кабинет";
    private static final int STUDENT_PAGE_SIZE = 8;

    private final String botUsername;
    private final CuratorDecisionService curatorDecisionService;
    private final CuratorIntakeService curatorIntakeService;
    private final CuratorRepository curatorRepository;
    private final CuratorVkGroupRepository curatorVkGroupRepository;
    private final SubjectRepository subjectRepository;
    private final RegistrationStateManager stateManager;
    private final CuratorRoutingService curatorRoutingService;
    private final VkGroupManagementService groupManagementService;
    private final StudentDirectoryService studentDirectoryService;
    private final BroadcastService broadcastService;
    private final TelegramRateLimiter telegramRateLimiter;
    private final TelegramRateLimitProperties rateLimitProperties;
    private final BalanceService balanceService;
    private final BalanceCreditService balanceCreditService;
    private final TelegramStarsInvoiceClient starsInvoiceClient;
    private final TelegramStarsProperties starsProperties;
    private final TaskScheduler taskScheduler;
    private final Duration transientMessageTtl;
    private final String miniAppUrl;

    public CuratorTelegramBot(
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.bot-name}") String botUsername,
            CuratorDecisionService curatorDecisionService,
            CuratorIntakeService curatorIntakeService,
            CuratorRepository curatorRepository,
            CuratorVkGroupRepository curatorVkGroupRepository,
            SubjectRepository subjectRepository,
            RegistrationStateManager stateManager,
            CuratorRoutingService curatorRoutingService,
            VkGroupManagementService groupManagementService,
            StudentDirectoryService studentDirectoryService,
            BroadcastService broadcastService,
            TelegramRateLimiter telegramRateLimiter,
            TelegramRateLimitProperties rateLimitProperties,
            BalanceService balanceService,
            BalanceCreditService balanceCreditService,
            TelegramStarsInvoiceClient starsInvoiceClient,
            TelegramStarsProperties starsProperties,
            TaskScheduler taskScheduler,
            @Value("${telegram.ui.transient-message-ttl:5s}")
            Duration transientMessageTtl,
            @Value("${telegram.mini-app.url:}") String miniAppUrl) {
        super(botToken);
        this.botUsername = botUsername;
        this.curatorDecisionService = curatorDecisionService;
        this.curatorIntakeService = curatorIntakeService;
        this.curatorRepository = curatorRepository;
        this.curatorVkGroupRepository = curatorVkGroupRepository;
        this.subjectRepository = subjectRepository;
        this.stateManager = stateManager;
        this.curatorRoutingService = curatorRoutingService;
        this.groupManagementService = groupManagementService;
        this.studentDirectoryService = studentDirectoryService;
        this.broadcastService = broadcastService;
        this.telegramRateLimiter = telegramRateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.balanceService = balanceService;
        this.balanceCreditService = balanceCreditService;
        this.starsInvoiceClient = starsInvoiceClient;
        this.starsProperties = starsProperties;
        this.taskScheduler = taskScheduler;
        this.transientMessageTtl = transientMessageTtl;
        this.miniAppUrl = miniAppUrl;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasPreCheckoutQuery()) {
            handlePreCheckoutQuery(update.getPreCheckoutQuery());
            return;
        }

        if (update.hasMessage() && update.getMessage().hasSuccessfulPayment()) {
            handleSuccessfulPayment(
                    update.getMessage().getChatId(),
                    update.getMessage().getSuccessfulPayment()
            );
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            Long tgChatId = update.getMessage().getChatId();

            if (handleManualIntakeAnswer(update) || handleEditedAnswer(update)) {
                return;
            }

            if (handleGroupCommand(tgChatId, messageText, update.getMessage().getFrom().getUserName())) {
                return;
            }

            if (handleBroadcastText(update)) {
                return;
            }

            if ("/start".equals(messageText)) {
                handleStartCommand(tgChatId, update.getMessage().getFrom().getUserName());
                return;
            }

            RegistrationContext context = stateManager.getContext(tgChatId);
            if (context.getState() != RegistrationState.NONE) {
                if (isSensitiveRegistrationState(context.getState())) {
                    deleteSensitiveMessage(tgChatId, update.getMessage().getMessageId());
                }
                handleRegistrationStep(tgChatId, messageText, context);
            }
            return;
        }

        if (!update.hasCallbackQuery() || update.getCallbackQuery().getData() == null) {
            return;
        }

        String callbackData = update.getCallbackQuery().getData();
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        answerCallback(update.getCallbackQuery().getId());
        if (callbackData.startsWith("subject_")) {
            handleSubjectSelection(tgChatId, callbackData);
        } else if (callbackData.startsWith("intake_ai_")) {
            handleIntakeAiAction(update);
        } else if (callbackData.startsWith("intake_reply_")) {
            handleIntakeManualAction(update);
        } else if (callbackData.startsWith("manual_retry_")) {
            handleManualDeliveryAction(update, false);
        } else if (callbackData.startsWith("manual_cancel_")) {
            handleManualDeliveryAction(update, true);
        } else if (callbackData.startsWith("approve_")) {
            handleDecisionAction(update, "APPROVED", "approve_");
        } else if (callbackData.startsWith("edit_")) {
            handleEditAction(update);
        } else if (callbackData.startsWith("reject_")) {
            handleDecisionAction(update, "REJECTED", "reject_");
        } else if (callbackData.startsWith("students_group_")) {
            handleStudentGroupSelection(update);
        } else if (callbackData.startsWith("students_page_")) {
            handleStudentPage(update);
        } else if (callbackData.startsWith("broadcast_group_")) {
            handleBroadcastGroupSelection(update);
        } else if (callbackData.startsWith("broadcast_toggle_")) {
            handleBroadcastToggle(update);
        } else if (callbackData.startsWith("broadcast_page_")) {
            handleBroadcastPage(update);
        } else if ("broadcast_text".equals(callbackData)) {
            handleBroadcastTextRequest(update);
        } else if ("broadcast_confirm".equals(callbackData)) {
            handleBroadcastConfirmation(update);
        } else if ("broadcast_cancel".equals(callbackData)) {
            handleBroadcastCancellation(update);
        } else if (callbackData.startsWith("remove_group_")) {
            requestGroupRemoval(tgChatId, callbackData.substring("remove_group_".length()));
        }
    }

    private boolean handleGroupCommand(Long tgChatId, String messageText, String username) {
        String command = messageText == null ? "" : messageText.strip();
        if ("/buy".equals(command) || MENU_BUY.equals(command)) {
            sendProInvoice(tgChatId);
            return true;
        }
        if ("/paysupport".equals(command)) {
            sendText(
                    tgChatId,
                    "По вопросам оплаты напишите администратору бота и приложите ID платежа."
            );
            return true;
        }
        if ("/balance".equals(command) || MENU_BALANCE.equals(command)) {
            showBalance(tgChatId);
            return true;
        }
        if ("/groups".equals(command) || MENU_GROUPS.equals(command)) {
            showGroups(tgChatId);
            return true;
        }
        if ("/students".equals(command) || MENU_STUDENTS.equals(command)) {
            openStudentDirectory(tgChatId);
            return true;
        }
        if ("/broadcast".equals(command) || MENU_BROADCAST.equals(command)) {
            openBroadcast(tgChatId);
            return true;
        }
        if ("/app".equals(command) || MENU_APP.equals(command)) {
            sendMiniAppButton(tgChatId);
            return true;
        }
        if ("/cancel".equals(command)) {
            if (broadcastService.cancel(tgChatId)) {
                sendText(tgChatId, "Черновик рассылки отменён.");
            } else {
                sendText(tgChatId, "Нет черновика, который можно отменить.");
            }
            return true;
        }
        if ("/add_group".equals(command) || MENU_ADD_GROUP.equals(command)) {
            startAddingGroup(tgChatId, username);
            return true;
        }
        if (command.startsWith("/remove_group")) {
            String[] parts = command.split("\\s+", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                sendText(tgChatId, "Использование: /remove_group <ID группы>. Список: /groups");
            } else {
                requestGroupRemoval(tgChatId, parts[1].trim());
            }
            return true;
        }
        return false;
    }

    private void sendProInvoice(Long tgChatId) {
        if (curatorRepository.findByTgChatId(tgChatId).isEmpty()) {
            sendText(tgChatId, "Сначала зарегистрируйтесь командой /start.");
            return;
        }

        try {
            starsInvoiceClient.sendProInvoice(tgChatId);
        } catch (RuntimeException exception) {
            log.error("Failed to send Telegram Stars invoice to chat {}", tgChatId, exception);
            sendText(tgChatId, "Не удалось создать счёт. Попробуйте немного позже.");
        }
    }

    private void handlePreCheckoutQuery(PreCheckoutQuery query) {
        Long tgChatId = query.getFrom().getId();
        String validationError = validatePreCheckout(tgChatId, query);
        AnswerPreCheckoutQuery answer = new AnswerPreCheckoutQuery(
                query.getId(),
                validationError == null
        );
        if (validationError != null) {
            answer.setErrorMessage(validationError);
        }

        try {
            executeRateLimited(() -> execute(answer));
        } catch (TelegramApiException exception) {
            log.error("Failed to answer pre-checkout query {}", query.getId(), exception);
        }
    }

    private String validatePreCheckout(Long tgChatId, PreCheckoutQuery query) {
        if (curatorRepository.findByTgChatId(tgChatId).isEmpty()) {
            return "Куратор не зарегистрирован.";
        }
        if (!"XTR".equals(query.getCurrency())) {
            return "Неподдерживаемая валюта платежа.";
        }
        if (!starsProperties.getProductPayload().equals(query.getInvoicePayload())) {
            return "Неизвестный тариф.";
        }
        if (query.getTotalAmount() == null
                || query.getTotalAmount() != starsProperties.getPrice()) {
            return "Стоимость тарифа изменилась. Создайте новый счёт.";
        }
        return null;
    }

    private void handleSuccessfulPayment(Long tgChatId, SuccessfulPayment payment) {
        try {
            BalanceCreditService.CreditResult result =
                    balanceCreditService.creditTelegramStars(
                            tgChatId,
                            payment.getCurrency(),
                            payment.getTotalAmount(),
                            payment.getInvoicePayload(),
                            payment.getTelegramPaymentChargeId()
                    );

            if (result.newlyCredited()) {
                sendText(
                        tgChatId,
                        "Оплата получена. Начислено "
                                + result.credited().stripTrailingZeros().toPlainString()
                                + " credits. Баланс: "
                                + result.balanceAfter().stripTrailingZeros().toPlainString()
                                + "."
                );
            } else {
                log.info(
                        "Duplicate successful payment {} ignored",
                        payment.getTelegramPaymentChargeId()
                );
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to apply Telegram Stars payment {}",
                    payment.getTelegramPaymentChargeId(),
                    exception
            );
            sendText(
                    tgChatId,
                    "Платёж получен, но баланс пока не обновился. "
                            + "Обратитесь в /paysupport и укажите ID: "
                            + payment.getTelegramPaymentChargeId()
            );
        }
    }

    private void showBalance(Long tgChatId) {
        balanceService.getBalance(tgChatId).ifPresentOrElse(
                balance -> sendText(
                        tgChatId,
                        "Остаток: " + balance.stripTrailingZeros().toPlainString() + " токенов."
                ),
                () -> sendText(tgChatId, "Сначала зарегистрируйтесь командой /start.")
        );
    }

    private void startAddingGroup(Long tgChatId, String username) {
        Optional<Curator> optionalCurator = curatorRepository.findByTgChatId(tgChatId);
        if (optionalCurator.isEmpty()) {
            sendText(tgChatId, "Сначала зарегистрируйтесь командой /start.");
            return;
        }

        Curator curator = optionalCurator.get();
        RegistrationContext context = stateManager.getContext(tgChatId);
        context.setUsername(username);
        context.setSubjectId(curator.getSubject().getId());
        context.setVkGroupId(null);
        context.setVkToken(null);
        context.setVkSecret(null);
        context.setVkConfirmationCode(null);
        context.setState(RegistrationState.AWAITING_VK_GROUP_ID);
        stateManager.saveContext(context);
        sendText(tgChatId, "Введите ID новой группы VK, используя только цифры:");
    }

    private void showGroups(Long tgChatId) {
        List<CuratorVkGroup> groups = groupManagementService.findCuratorGroups(tgChatId);
        if (groups.isEmpty()) {
            sendText(tgChatId, "Подключённых групп пока нет. Добавить: /add_group");
            return;
        }

        StringBuilder text = new StringBuilder("<b>Ваши VK-группы:</b>\n");
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        for (CuratorVkGroup group : groups) {
            text.append("\n<code>")
                    .append(HtmlUtils.htmlEscape(group.getVkGroupId()))
                    .append("</code> — ")
                    .append(groupStatusLabel(group.getStatus()));
            if (group.getLastError() != null && !group.getLastError().isBlank()) {
                text.append("\n").append(HtmlUtils.htmlEscape(group.getLastError()));
            }

            InlineKeyboardButton removeButton = new InlineKeyboardButton();
            removeButton.setText("Удалить " + group.getVkGroupId());
            removeButton.setCallbackData("remove_group_" + group.getVkGroupId());
            keyboard.add(List.of(removeButton));
        }

        SendMessage message = new SendMessage(tgChatId.toString(), text.toString());
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        message.setReplyMarkup(markup);
        executeSafely(message, tgChatId);
    }

    private void openStudentDirectory(Long tgChatId) {
        List<CuratorVkGroup> groups = activeGroups(tgChatId);
        if (groups.isEmpty()) {
            sendText(tgChatId, "Сначала подключите активную VK-группу.");
            return;
        }
        if (groups.size() == 1) {
            showStudentPage(tgChatId, null, groups.get(0).getVkGroupId(), 0);
            return;
        }
        sendGroupChoice(
                tgChatId,
                "Выберите VK-группу, ученики которой нужны:",
                groups,
                "students_group_"
        );
    }

    private void openBroadcast(Long tgChatId) {
        List<CuratorVkGroup> groups = activeGroups(tgChatId);
        if (groups.isEmpty()) {
            sendText(tgChatId, "Сначала подключите активную VK-группу.");
            return;
        }
        if (groups.size() == 1) {
            beginBroadcast(tgChatId, null, groups.get(0).getVkGroupId());
            return;
        }
        sendGroupChoice(
                tgChatId,
                "Выберите VK-группу для рассылки:",
                groups,
                "broadcast_group_"
        );
    }

    private List<CuratorVkGroup> activeGroups(Long tgChatId) {
        return groupManagementService.findCuratorGroups(tgChatId).stream()
                .filter(group -> group.getStatus() == VkGroupStatus.ACTIVE)
                .toList();
    }

    private void sendGroupChoice(
            Long tgChatId,
            String text,
            List<CuratorVkGroup> groups,
            String callbackPrefix
    ) {
        List<List<InlineKeyboardButton>> rows = groups.stream()
                .map(group -> {
                    InlineKeyboardButton button = new InlineKeyboardButton();
                    button.setText("VK " + group.getVkGroupId());
                    button.setCallbackData(
                            callbackPrefix + group.getVkGroupId()
                    );
                    return List.of(button);
                })
                .toList();
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        sendInlineText(tgChatId, text, keyboard, false);
    }

    private void handleStudentGroupSelection(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        String vkGroupId = callbackData.substring("students_group_".length());
        showStudentPage(
                update.getCallbackQuery().getMessage().getChatId(),
                update.getCallbackQuery().getMessage().getMessageId(),
                vkGroupId,
                0
        );
    }

    private void handleStudentPage(Update update) {
        String value = update.getCallbackQuery().getData()
                .substring("students_page_".length());
        int separator = value.lastIndexOf('_');
        if (separator <= 0) {
            return;
        }
        try {
            showStudentPage(
                    update.getCallbackQuery().getMessage().getChatId(),
                    update.getCallbackQuery().getMessage().getMessageId(),
                    value.substring(0, separator),
                    Integer.parseInt(value.substring(separator + 1))
            );
        } catch (NumberFormatException exception) {
            log.debug("Invalid student page callback {}", value);
        }
    }

    private void showStudentPage(
            Long tgChatId,
            Integer messageId,
            String vkGroupId,
            int requestedPage
    ) {
        StudentDirectoryService.StudentPage page = studentDirectoryService.page(
                tgChatId,
                vkGroupId,
                requestedPage,
                STUDENT_PAGE_SIZE
        );
        StringBuilder text = new StringBuilder()
                .append("Ученики VK ")
                .append(vkGroupId)
                .append(": ")
                .append(page.total());
        if (page.students().isEmpty()) {
            text.append("\n\nПока нет учеников с личным диалогом.");
        } else {
            int offset = page.page() * STUDENT_PAGE_SIZE;
            for (int index = 0; index < page.students().size(); index++) {
                StudentDirectoryService.StudentView student =
                        page.students().get(index);
                text.append("\n")
                        .append(offset + index + 1)
                        .append(". ")
                        .append(student.label())
                        .append(" (VK ")
                        .append(student.vkUserId())
                        .append(")");
            }
        }

        InlineKeyboardMarkup keyboard = studentPageKeyboard(page);
        if (messageId == null) {
            sendInlineText(tgChatId, text.toString(), keyboard, false);
        } else {
            editMessageText(
                    tgChatId,
                    messageId,
                    text.toString(),
                    keyboard,
                    null
            );
        }
    }

    private InlineKeyboardMarkup studentPageKeyboard(
            StudentDirectoryService.StudentPage page
    ) {
        List<InlineKeyboardButton> navigation = new ArrayList<>();
        if (page.page() > 0) {
            navigation.add(inlineButton(
                    "<",
                    "students_page_"
                            + page.vkGroupId()
                            + "_"
                            + (page.page() - 1)
            ));
        }
        navigation.add(inlineButton(
                (page.page() + 1) + "/" + page.pageCount(),
                "students_noop"
        ));
        if (page.page() + 1 < page.pageCount()) {
            navigation.add(inlineButton(
                    ">",
                    "students_page_"
                            + page.vkGroupId()
                            + "_"
                            + (page.page() + 1)
            ));
        }
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(navigation));
        return keyboard;
    }

    private void handleBroadcastGroupSelection(Update update) {
        String vkGroupId = update.getCallbackQuery().getData()
                .substring("broadcast_group_".length());
        beginBroadcast(
                update.getCallbackQuery().getMessage().getChatId(),
                update.getCallbackQuery().getMessage().getMessageId(),
                vkGroupId
        );
    }

    private void beginBroadcast(
            Long tgChatId,
            Integer messageId,
            String vkGroupId
    ) {
        try {
            broadcastService.begin(tgChatId, vkGroupId);
            showBroadcastSelection(tgChatId, messageId, 0);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendText(tgChatId, exception.getMessage());
        }
    }

    private void handleBroadcastToggle(Update update) {
        String value = update.getCallbackQuery().getData()
                .substring("broadcast_toggle_".length());
        int separator = value.indexOf('_');
        if (separator <= 0) {
            return;
        }
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        try {
            int page = Integer.parseInt(value.substring(0, separator));
            UUID studentId = UUID.fromString(value.substring(separator + 1));
            broadcastService.toggleRecipient(tgChatId, studentId);
            showBroadcastSelection(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    page
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendTemporaryText(tgChatId, exception.getMessage());
        }
    }

    private void handleBroadcastPage(Update update) {
        try {
            showBroadcastSelection(
                    update.getCallbackQuery().getMessage().getChatId(),
                    update.getCallbackQuery().getMessage().getMessageId(),
                    Integer.parseInt(
                            update.getCallbackQuery().getData()
                                    .substring("broadcast_page_".length())
                    )
            );
        } catch (NumberFormatException exception) {
            log.debug(
                    "Invalid broadcast page callback {}",
                    update.getCallbackQuery().getData()
            );
        }
    }

    private void showBroadcastSelection(
            Long tgChatId,
            Integer messageId,
            int requestedPage
    ) {
        try {
            BroadcastService.SelectionPage page =
                    broadcastService.selectionPage(
                            tgChatId,
                            requestedPage,
                            STUDENT_PAGE_SIZE
                    );
            String text = "Рассылка для VK "
                    + page.vkGroupId()
                    + "\nВыбрано: "
                    + page.selected()
                    + "/"
                    + page.maxRecipients()
                    + "\n\nНажмите на ученика, чтобы включить или исключить его.";
            InlineKeyboardMarkup keyboard = broadcastSelectionKeyboard(page);
            if (messageId == null) {
                sendInlineText(tgChatId, text, keyboard, false);
            } else {
                editMessageText(
                        tgChatId,
                        messageId,
                        text,
                        keyboard,
                        page.campaignId()
                );
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendText(tgChatId, exception.getMessage());
        }
    }

    private InlineKeyboardMarkup broadcastSelectionKeyboard(
            BroadcastService.SelectionPage page
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (BroadcastService.SelectableStudent student : page.students()) {
            String marker = student.selected() ? "[x] " : "[ ] ";
            rows.add(List.of(inlineButton(
                    marker + truncate(student.label(), 42),
                    "broadcast_toggle_"
                            + page.page()
                            + "_"
                            + student.id()
            )));
        }

        List<InlineKeyboardButton> navigation = new ArrayList<>();
        if (page.page() > 0) {
            navigation.add(inlineButton(
                    "<",
                    "broadcast_page_" + (page.page() - 1)
            ));
        }
        navigation.add(inlineButton(
                (page.page() + 1) + "/" + page.pageCount(),
                "broadcast_noop"
        ));
        if (page.page() + 1 < page.pageCount()) {
            navigation.add(inlineButton(
                    ">",
                    "broadcast_page_" + (page.page() + 1)
            ));
        }
        rows.add(navigation);
        rows.add(List.of(
                inlineButton("Продолжить", "broadcast_text"),
                inlineButton("Отмена", "broadcast_cancel")
        ));

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private void handleBroadcastTextRequest(Update update) {
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        try {
            BroadcastService.TextRequest request =
                    broadcastService.requestText(tgChatId);
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(List.of(List.of(
                    inlineButton("Отмена", "broadcast_cancel")
            )));
            editMessageText(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    "Выбрано учеников: "
                            + request.recipients()
                            + "\n\nОтправьте текст рассылки одним сообщением.\n"
                            + "Доступные подстановки: {first_name}, "
                            + "{last_name}, {name}.",
                    keyboard,
                    request.campaignId()
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendTemporaryText(tgChatId, exception.getMessage());
        }
    }

    private boolean handleBroadcastText(Update update) {
        Long tgChatId = update.getMessage().getChatId();
        if (!broadcastService.isAwaitingText(tgChatId)) {
            return false;
        }
        try {
            BroadcastService.Preview preview = broadcastService.acceptText(
                    tgChatId,
                    update.getMessage().getText()
            );
            String text = "<b>Предпросмотр рассылки</b>\n\n"
                    + HtmlUtils.htmlEscape(preview.renderedExample())
                    + "\n\nПолучатель примера: "
                    + HtmlUtils.htmlEscape(preview.exampleStudent())
                    + "\nВсего получателей: "
                    + preview.recipients();
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            keyboard.setKeyboard(List.of(List.of(
                    inlineButton("Отправить", "broadcast_confirm"),
                    inlineButton("Отмена", "broadcast_cancel")
            )));
            sendInlineText(tgChatId, text, keyboard, true);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendText(tgChatId, exception.getMessage());
        }
        return true;
    }

    private void handleBroadcastConfirmation(Update update) {
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        try {
            BroadcastService.QueueResult result =
                    broadcastService.queue(tgChatId);
            editMessageText(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    "Рассылка запущена. Получателей: "
                            + result.recipients()
                            + ". Итог придёт отдельным сообщением.",
                    null,
                    result.campaignId()
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sendTemporaryText(tgChatId, exception.getMessage());
        }
    }

    private void handleBroadcastCancellation(Update update) {
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        if (broadcastService.cancel(tgChatId)) {
            editMessageText(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    "Черновик рассылки отменён.",
                    null,
                    null
            );
        } else {
            sendTemporaryText(
                    tgChatId,
                    "Рассылку уже нельзя отменить или она завершена."
            );
        }
    }

    public void notifyBroadcastCompleted(BroadcastService.Completion completion) {
        String text = "Рассылка завершена.\n"
                + "Всего: "
                + completion.total()
                + "\nДоставлено: "
                + completion.sent()
                + "\nОшибки: "
                + completion.failed();
        sendText(completion.tgChatId(), text);
    }

    private InlineKeyboardButton inlineButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private void sendInlineText(
            Long tgChatId,
            String text,
            InlineKeyboardMarkup keyboard,
            boolean html
    ) {
        SendMessage message = new SendMessage(tgChatId.toString(), text);
        if (html) {
            message.setParseMode("HTML");
        }
        message.setReplyMarkup(keyboard);
        executeSafely(message, tgChatId);
    }

    private void requestGroupRemoval(Long tgChatId, String vkGroupId) {
        if (!vkGroupId.matches("\\d+")) {
            sendText(tgChatId, "ID группы должен содержать только цифры.");
            return;
        }
        if (!groupManagementService.requestRemoval(tgChatId, vkGroupId)) {
            sendText(tgChatId, "Группа " + vkGroupId + " не найдена среди ваших подключений.");
            return;
        }
        sendText(tgChatId, "Удаление группы " + vkGroupId + " запущено.");
    }

    public void notifyGroupStatus(VkGroupManagementService.GroupStatusUpdate update) {
        String message = switch (update.status()) {
            case "ACTIVE" -> "Группа " + update.vkGroupId() + " подключена и принимает сообщения.";
            case "REMOVED" -> "Группа " + update.vkGroupId() + " удалена.";
            case "ERROR" -> "Не удалось подключить группу " + update.vkGroupId() +
                    ". Причина: " + update.errorMessage();
            default -> "Статус группы " + update.vkGroupId() + ": " + update.status();
        };
        try {
            SendMessage sendMessage = new SendMessage(update.tgChatId().toString(), message);
            if ("ACTIVE".equals(update.status())) {
                sendMessage.setReplyMarkup(createMainMenuKeyboard());
            }
            executeRateLimited(() -> execute(sendMessage));
        } catch (TelegramApiException exception) {
            throw new IllegalStateException("Failed to notify curator about VK group status", exception);
        }
    }

    private String groupStatusLabel(VkGroupStatus status) {
        if (status == null) {
            return "активна";
        }
        return switch (status) {
            case PENDING -> "проверяется";
            case ACTIVE -> "активна";
            case ERROR -> "ошибка";
            case REMOVING -> "удаляется";
        };
    }

    private void handleStartCommand(Long tgChatId, String username) {
        Optional<Curator> optionalCurator = curatorRepository.findByTgChatId(tgChatId);
        if (optionalCurator.isPresent()) {
            Curator curator = optionalCurator.get();
            sendMainMenu(
                    tgChatId,
                    "Панель куратора\n\nБаланс: "
                            + curator.getBalanceTokens().stripTrailingZeros().toPlainString()
                            + " токенов\nПодключено сообществ: "
                            + curator.getVkGroups().size()
            );
            return;
        }

        RegistrationContext context = stateManager.getContext(tgChatId);
        context.setUsername(username);
        context.setState(RegistrationState.AWAITING_SUBJECT);
        stateManager.saveContext(context);
        sendSubjectSelectionMarkup(tgChatId);
    }

    private void sendSubjectSelectionMarkup(Long tgChatId) {
        SendMessage message = new SendMessage(tgChatId.toString(), "Выберите предмет:");
        message.setReplyMarkup(createSubjectKeyboard());
        executeSafely(message, tgChatId);
    }

    private InlineKeyboardMarkup createSubjectKeyboard() {
        List<List<InlineKeyboardButton>> keyboard = subjectRepository.findAll().stream()
                .map(subject -> {
                    InlineKeyboardButton button = new InlineKeyboardButton();
                    button.setText(subject.getName());
                    button.setCallbackData("subject_" + subject.getId());
                    return List.of(button);
                })
                .toList();

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    private void handleSubjectSelection(Long tgChatId, String callbackData) {
        RegistrationContext context = stateManager.getContext(tgChatId);
        if (context.getState() != RegistrationState.AWAITING_SUBJECT) {
            sendText(tgChatId, "Начните регистрацию командой /start.");
            return;
        }

        UUID subjectId;
        try {
            subjectId = UUID.fromString(callbackData.substring("subject_".length()));
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid subject callback for Telegram chat {}", tgChatId);
            sendText(tgChatId, "Не удалось выбрать предмет. Запустите регистрацию заново.");
            return;
        }

        if (!subjectRepository.existsById(subjectId)) {
            sendText(tgChatId, "Предмет больше недоступен. Запустите регистрацию заново.");
            return;
        }

        context.setSubjectId(subjectId);
        context.setState(RegistrationState.AWAITING_VK_GROUP_ID);
        stateManager.saveContext(context);
        sendText(tgChatId, "Введите ID группы VK, используя только цифры:");
    }

    private void handleRegistrationStep(Long tgChatId, String text, RegistrationContext context) {
        String value = text == null ? "" : text.trim();
        switch (context.getState()) {
            case AWAITING_VK_GROUP_ID -> handleVkGroupId(tgChatId, value, context);
            case AWAITING_VK_TOKEN -> handleVkToken(tgChatId, value, context);
            case AWAITING_VK_SECRET -> handleVkSecret(tgChatId, value, context);
            case AWAITING_VK_CONFIRMATION -> handleVkConfirmation(tgChatId, value, context);
            default -> log.debug("No registration action for chat {} in state {}", tgChatId, context.getState());
        }
    }

    private boolean isSensitiveRegistrationState(RegistrationState state) {
        return state == RegistrationState.AWAITING_VK_TOKEN
                || state == RegistrationState.AWAITING_VK_SECRET
                || state == RegistrationState.AWAITING_VK_CONFIRMATION;
    }

    private void deleteSensitiveMessage(Long tgChatId, Integer messageId) {
        try {
            executeRateLimited(() ->
                    execute(new DeleteMessage(tgChatId.toString(), messageId))
            );
        } catch (TelegramApiException exception) {
            log.warn(
                    "Failed to delete sensitive registration message {} in chat {}",
                    messageId,
                    tgChatId,
                    exception
            );
        }
    }

    private void handleVkGroupId(Long tgChatId, String value, RegistrationContext context) {
        if (!value.matches("\\d+")) {
            sendText(tgChatId, "ID группы VK должен содержать только цифры. Попробуйте ещё раз:");
            return;
        }
        if (curatorVkGroupRepository.existsByVkGroupId(value)) {
            sendText(tgChatId, "Эта группа VK уже зарегистрирована. Введите другой ID:");
            return;
        }

        context.setVkGroupId(value);
        context.setState(RegistrationState.AWAITING_VK_TOKEN);
        stateManager.saveContext(context);
        sendText(tgChatId, "Введите токен доступа группы VK (Access Token):");
    }

    private void handleVkToken(Long tgChatId, String value, RegistrationContext context) {
        if (value.isBlank()) {
            sendText(tgChatId, "Токен не может быть пустым. Введите Access Token:");
            return;
        }

        context.setVkToken(value);
        context.setState(RegistrationState.AWAITING_VK_SECRET);
        stateManager.saveContext(context);
        sendText(tgChatId, "Введите секретный ключ Callback API (Secret Key):");
    }

    private void handleVkSecret(Long tgChatId, String value, RegistrationContext context) {
        if (value.isBlank()) {
            sendText(tgChatId, "Секретный ключ не может быть пустым. Введите Secret Key:");
            return;
        }

        context.setVkSecret(value);
        context.setState(RegistrationState.AWAITING_VK_CONFIRMATION);
        stateManager.saveContext(context);
        sendText(tgChatId, "Введите код подтверждения сервера VK (Confirmation Code):");
    }

    private void handleVkConfirmation(Long tgChatId, String value, RegistrationContext context) {
        if (value.isBlank()) {
            sendText(tgChatId, "Код подтверждения не может быть пустым. Введите Confirmation Code:");
            return;
        }

        context.setVkConfirmationCode(value);
        saveCuratorAndSendConfig(tgChatId, context);
    }

    private void saveCuratorAndSendConfig(Long tgChatId, RegistrationContext context) {
        Subject subject = subjectRepository.findById(context.getSubjectId())
                .orElseThrow(() -> new IllegalArgumentException("Subject not found"));

        groupManagementService.registerGroup(tgChatId, context, subject);
        stateManager.clear(tgChatId);

        sendText(tgChatId, "Группа сохранена. Проверяем токен и настраиваем Callback API.");
    }

    public void sendIntakeRequest(CuratorIntakeRequest request) {
        CuratorRoutingService.CuratorRoute route = curatorRoutingService
                .resolve(request.vkGroupId())
                .orElseThrow(() -> new IllegalStateException(
                        "No curator route for VK group " + request.vkGroupId()
                ));

        Optional<CuratorIntakeService.IntakeView> optionalView =
                curatorIntakeService.prepare(request, route.tgChatId());
        if (optionalView.isEmpty()) {
            log.info("[{}] Duplicate curator intake request ignored", request.requestId());
            return;
        }

        CuratorIntakeService.IntakeView view = optionalView.get();
        try {
            sendPhotos(route.tgChatId(), request.photoUrls());
            Message sent = sendIntakeActionCard(
                    view,
                    "Новый вопрос от ученика:"
            );
            curatorIntakeService.markDelivered(
                    view.requestId(),
                    route.tgChatId(),
                    sent.getMessageId()
            );
        } catch (TelegramApiException exception) {
            log.error(
                    "[{}] Failed to send curator intake request to Telegram chat {}",
                    request.requestId(),
                    route.tgChatId(),
                    exception
            );
            throw new IllegalStateException("Telegram intake delivery failed", exception);
        }
    }

    private void handleIntakeAiAction(Update update) {
        Optional<UUID> optionalRequestId = parseIntakeCallback(
                update.getCallbackQuery().getData(),
                "intake_ai_"
        );
        if (optionalRequestId.isEmpty()) {
            log.warn("Invalid curator intake AI callback");
            return;
        }

        UUID requestId = optionalRequestId.get();
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        try {
            if (!curatorIntakeService.queueAi(requestId, tgChatId)) {
                sendText(tgChatId, "Этот вопрос уже обрабатывается.");
                return;
            }
            removeKeyboard(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    requestId
            );
            sendTemporaryText(tgChatId, "ИИ готовит ответ...");
        } catch (RuntimeException exception) {
            log.error("[{}] Failed to queue AI intake decision", requestId, exception);
            sendText(tgChatId, "Не удалось отправить вопрос в ИИ. Попробуйте ещё раз.");
        }
    }

    private void handleIntakeManualAction(Update update) {
        Optional<UUID> optionalRequestId = parseIntakeCallback(
                update.getCallbackQuery().getData(),
                "intake_reply_"
        );
        if (optionalRequestId.isEmpty()) {
            log.warn("Invalid curator intake manual callback");
            return;
        }

        UUID requestId = optionalRequestId.get();
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        Optional<CuratorIntakeService.IntakeView> optionalView;
        try {
            optionalView = curatorIntakeService.beginManualReply(requestId, tgChatId);
        } catch (RuntimeException exception) {
            log.error("[{}] Failed to start manual reply", requestId, exception);
            sendText(tgChatId, "Не удалось открыть ручной ответ. Попробуйте ещё раз.");
            return;
        }

        if (optionalView.isEmpty()) {
            sendText(tgChatId, "Этот вопрос уже обрабатывается.");
            return;
        }

        try {
            SendMessage prompt = new SendMessage(
                    tgChatId.toString(),
                    "Ответьте на это сообщение. Ваш текст будет сразу отправлен ученику в VK.\n"
                            + "Чтобы вернуться к выбору действия, ответьте /cancel."
            );
            ForceReplyKeyboard forceReply = new ForceReplyKeyboard(true);
            forceReply.setSelective(true);
            forceReply.setInputFieldPlaceholder("Ответ ученику");
            prompt.setReplyMarkup(forceReply);
            Message sentPrompt = executeRateLimited(() -> execute(prompt));

            curatorIntakeService.attachManualPrompt(
                    requestId,
                    tgChatId,
                    sentPrompt.getMessageId()
            );
            removeKeyboard(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    requestId
            );
        } catch (Exception exception) {
            curatorIntakeService.cancelManualReply(requestId, tgChatId);
            log.error("[{}] Failed to open manual reply", requestId, exception);
            sendText(tgChatId, "Не удалось открыть ручной ответ. Попробуйте ещё раз.");
        }
    }

    private boolean handleManualIntakeAnswer(Update update) {
        Message message = update.getMessage();
        if (message.getReplyToMessage() == null) {
            return false;
        }

        Long tgChatId = message.getChatId();
        Integer promptMessageId = message.getReplyToMessage().getMessageId();
        Optional<CuratorIntakeService.IntakeView> optionalView =
                curatorIntakeService.findManualReply(tgChatId, promptMessageId);
        if (optionalView.isEmpty()) {
            return false;
        }

        String answer = message.getText() == null ? "" : message.getText().strip();
        CuratorIntakeService.IntakeView view = optionalView.get();
        if ("/cancel".equalsIgnoreCase(answer)) {
            try {
                boolean reopened = curatorIntakeService.reopenAfterManualCancellation(
                        view.requestId(),
                        tgChatId,
                        promptMessageId
                );
                if (!reopened) {
                    sendTemporaryText(tgChatId, "Этот вопрос уже обрабатывается.");
                    return true;
                }
                deleteMessageSafely(tgChatId, promptMessageId, view.requestId());
                deleteMessageSafely(tgChatId, message.getMessageId(), view.requestId());
                if (view.intakeMessageId() != null) {
                    editMessageText(
                            tgChatId,
                            view.intakeMessageId(),
                            buildIntakeText(view.studentQuestion()),
                            createIntakeKeyboard(view.requestId()),
                            view.requestId()
                    );
                    curatorIntakeService.markDelivered(
                            view.requestId(),
                            tgChatId,
                            view.intakeMessageId()
                    );
                }
            } catch (Exception exception) {
                log.error(
                        "[{}] Failed to reopen intake after manual cancellation",
                        view.requestId(),
                        exception
                );
                throw new IllegalStateException(
                        "Failed to reopen curator intake",
                        exception
                );
            }
            return true;
        }
        if (answer.isBlank()) {
            sendText(tgChatId, "Ответ не может быть пустым.");
            return true;
        }
        if (answer.length() > 4096) {
            sendText(tgChatId, "Ответ слишком длинный. Максимум 4096 символов.");
            return true;
        }

        try {
            boolean queued = curatorIntakeService.completeManualReply(
                    view.requestId(),
                    tgChatId,
                    promptMessageId,
                    answer
            );
            if (!queued) {
                sendTemporaryText(tgChatId, "Этот вопрос уже обработан.");
                return true;
            }
            deleteMessageSafely(tgChatId, promptMessageId, view.requestId());
            deleteMessageSafely(tgChatId, message.getMessageId(), view.requestId());
            if (view.intakeMessageId() != null) {
                editMessageText(
                        tgChatId,
                        view.intakeMessageId(),
                        buildManualAnswerText(view.studentQuestion(), answer),
                        null,
                        view.requestId()
                );
            }
            sendTemporaryText(tgChatId, "Ответ отправляется ученику...");
        } catch (RuntimeException exception) {
            log.error("[{}] Failed to queue manual reply", view.requestId(), exception);
            sendText(tgChatId, "Не удалось сохранить ответ. Отправьте его ещё раз.");
        }
        return true;
    }

    private Message sendIntakeActionCard(
            CuratorIntakeService.IntakeView view,
            String heading
    ) throws TelegramApiException {
        SendMessage message = new SendMessage(
                view.tgChatId().toString(),
                heading + "\n\n" + truncate(view.studentQuestion(), 3800)
        );
        message.setReplyMarkup(createIntakeKeyboard(view.requestId()));
        return executeRateLimited(() -> execute(message));
    }

    private void handleManualDeliveryAction(Update update, boolean cancel) {
        String prefix = cancel ? "manual_cancel_" : "manual_retry_";
        Optional<ManualDeliveryCallback> optionalCallback =
                parseManualDeliveryCallback(
                        update.getCallbackQuery().getData(),
                        prefix
                );
        if (optionalCallback.isEmpty()) {
            log.warn("Invalid manual delivery callback");
            return;
        }

        ManualDeliveryCallback callback = optionalCallback.get();
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        try {
            boolean queued = curatorIntakeService.queueManualDeliveryAction(
                    callback.requestId(),
                    tgChatId,
                    callback.deliveryAttempt(),
                    cancel
            );
            if (!queued) {
                sendText(tgChatId, "Эта карточка уже обработана или устарела.");
                return;
            }

            removeKeyboard(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    callback.requestId()
            );
            sendText(
                    tgChatId,
                    cancel
                            ? "Повторная отправка отменена."
                            : "Запустил повторную отправку ответа в VK."
            );
        } catch (RuntimeException exception) {
            log.error(
                    "[{}] Failed to queue manual delivery action",
                    callback.requestId(),
                    exception
            );
            sendText(tgChatId, "Не удалось сохранить действие. Попробуйте ещё раз.");
        }
    }

    private InlineKeyboardMarkup createIntakeKeyboard(UUID requestId) {
        InlineKeyboardButton aiButton = new InlineKeyboardButton();
        aiButton.setText("Отправить в ИИ");
        aiButton.setCallbackData("intake_ai_" + requestId);

        InlineKeyboardButton manualButton = new InlineKeyboardButton();
        manualButton.setText("Ответить самому");
        manualButton.setCallbackData("intake_reply_" + requestId);

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(List.of(aiButton, manualButton)));
        return keyboard;
    }

    private Optional<UUID> parseIntakeCallback(String callbackData, String prefix) {
        if (callbackData == null || !callbackData.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(callbackData.substring(prefix.length())));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<ManualDeliveryCallback> parseManualDeliveryCallback(
            String callbackData,
            String prefix
    ) {
        if (callbackData == null || !callbackData.startsWith(prefix)) {
            return Optional.empty();
        }
        String value = callbackData.substring(prefix.length());
        int separator = value.lastIndexOf('_');
        if (separator <= 0 || separator == value.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ManualDeliveryCallback(
                    UUID.fromString(value.substring(0, separator)),
                    Integer.parseInt(value.substring(separator + 1))
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void handleDecisionAction(
            Update update,
            String status,
            String callbackPrefix
    ) {
        String callbackData = update.getCallbackQuery().getData();
        Optional<DecisionCallback> parsed =
                parseDecisionCallback(callbackData, callbackPrefix);
        if (parsed.isEmpty()) {
            log.warn("Invalid curator decision callback: {}", callbackData);
            return;
        }

        DecisionCallback callback = parsed.get();
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        boolean queued;
        try {
            queued = curatorDecisionService.queueDecision(
                    callback.requestId(),
                    tgChatId,
                    callback.revision(),
                    status
            );
        } catch (RuntimeException exception) {
            log.error(
                    "[{}] Failed to store curator decision",
                    callback.requestId(),
                    exception
            );
            sendText(tgChatId, "Не удалось сохранить решение. Попробуйте ещё раз.");
            return;
        }

        if (!queued) {
            sendText(tgChatId, "Эта карточка уже обработана или устарела.");
            return;
        }

        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        removeKeyboard(tgChatId, messageId, callback.requestId());
        String notificationText = "APPROVED".equals(status)
                ? "Ответ отправляется ученику..."
                : "Ответ отклонён, резерв освобождён.";
        sendTemporaryText(tgChatId, notificationText);
    }

    private void handleEditAction(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        Optional<DecisionCallback> parsed =
                parseDecisionCallback(callbackData, "edit_");
        if (parsed.isEmpty()) {
            log.warn("Invalid edit callback: {}", callbackData);
            return;
        }

        DecisionCallback callback = parsed.get();
        Long tgChatId = update.getCallbackQuery().getMessage().getChatId();
        Optional<CuratorDecisionService.DecisionView> optionalView;
        try {
            optionalView = curatorDecisionService.beginEditing(
                    callback.requestId(),
                    tgChatId,
                    callback.revision()
            );
        } catch (RuntimeException exception) {
            log.error("[{}] Failed to start answer editing", callback.requestId(), exception);
            sendText(tgChatId, "Не удалось начать редактирование. Попробуйте ещё раз.");
            return;
        }

        if (optionalView.isEmpty()) {
            sendText(tgChatId, "Эта карточка уже обработана или устарела.");
            return;
        }

        CuratorDecisionService.DecisionView view = optionalView.get();
        try {
            SendMessage prompt = new SendMessage(
                    tgChatId.toString(),
                    buildEditPromptText(view.currentAnswer())
            );
            ForceReplyKeyboard forceReply = new ForceReplyKeyboard(true);
            forceReply.setSelective(true);
            forceReply.setInputFieldPlaceholder("Исправленный ответ ученику");
            prompt.setReplyMarkup(forceReply);
            Message sentPrompt = executeRateLimited(() -> execute(prompt));

            curatorDecisionService.attachEditPrompt(
                    view.requestId(),
                    tgChatId,
                    view.revision(),
                    sentPrompt.getMessageId()
            );
            removeKeyboard(
                    tgChatId,
                    update.getCallbackQuery().getMessage().getMessageId(),
                    view.requestId()
            );
        } catch (Exception exception) {
            curatorDecisionService.cancelEditing(
                    view.requestId(),
                    tgChatId,
                    view.revision()
            );
            log.error("[{}] Failed to open answer editor", view.requestId(), exception);
            sendText(tgChatId, "Не удалось открыть редактирование. Попробуйте ещё раз.");
        }
    }

    private boolean handleEditedAnswer(Update update) {
        Message message = update.getMessage();
        if (message.getReplyToMessage() == null) {
            return false;
        }

        Long tgChatId = message.getChatId();
        Integer replyToMessageId = message.getReplyToMessage().getMessageId();
        Optional<CuratorDecisionService.DecisionView> optionalView =
                curatorDecisionService.findEditingReply(tgChatId, replyToMessageId);
        if (optionalView.isEmpty()) {
            return false;
        }

        String editedAnswer = message.getText() == null
                ? ""
                : message.getText().strip();
        if (editedAnswer.isBlank()) {
            sendText(tgChatId, "Исправленный ответ не может быть пустым.");
            return true;
        }
        if (editedAnswer.length() > 4096) {
            sendText(tgChatId, "Ответ слишком длинный. Максимум 4096 символов.");
            return true;
        }

        CuratorDecisionService.DecisionView view = optionalView.get();
        int nextRevision = view.revision() + 1;
        try {
            Message preview = sendApprovalMessage(
                    new CuratorDecisionService.DecisionView(
                            view.requestId(),
                            view.tgChatId(),
                            view.studentQuestion(),
                            editedAnswer,
                            view.creditsToCharge(),
                            nextRevision,
                            null,
                            view.editPromptMessageId()
                    )
            );
            boolean completed = curatorDecisionService.completeEditing(
                    view.requestId(),
                    tgChatId,
                    view.revision(),
                    replyToMessageId,
                    editedAnswer,
                    preview.getMessageId()
            );
            if (!completed) {
                removeKeyboard(tgChatId, preview.getMessageId(), view.requestId());
                sendTemporaryText(
                        tgChatId,
                        "Редактирование уже завершено в другой карточке."
                );
            } else {
                deleteMessageSafely(tgChatId, replyToMessageId, view.requestId());
                deleteMessageSafely(
                        tgChatId,
                        message.getMessageId(),
                        view.requestId()
                );
                deleteMessageSafely(
                        tgChatId,
                        view.approvalMessageId(),
                        view.requestId()
                );
            }
        } catch (Exception exception) {
            log.error("[{}] Failed to send edited answer preview", view.requestId(), exception);
            sendText(tgChatId, "Не удалось показать исправленный ответ. Отправьте его ещё раз.");
        }
        return true;
    }

    public void sendApprovalRequest(CuratorApprovalRequest request) {
        CuratorRoutingService.CuratorRoute route = curatorRoutingService.resolve(request.vkGroupId())
                .orElseThrow(() -> new IllegalStateException(
                        "No curator route for VK group " + request.vkGroupId()
                ));

        Optional<CuratorDecisionService.DecisionView> optionalView =
                curatorDecisionService.prepareApproval(request, route.tgChatId());
        if (optionalView.isEmpty()) {
            log.info("[{}] Duplicate approval request ignored", request.requestId());
            return;
        }
        CuratorDecisionService.DecisionView view = optionalView.get();

        try {
            Message sent = sendApprovalMessage(view);
            curatorDecisionService.markApprovalDelivered(
                    view.requestId(),
                    route.tgChatId(),
                    view.revision(),
                    sent.getMessageId()
            );
            curatorIntakeService.findView(view.requestId(), route.tgChatId())
                    .map(CuratorIntakeService.IntakeView::intakeMessageId)
                    .ifPresent(messageId -> deleteMessageSafely(
                            route.tgChatId(),
                            messageId,
                            view.requestId()
                    ));
        } catch (TelegramApiException exception) {
            log.error(
                    "[{}] Failed to send approval request to Telegram chat {}",
                    request.requestId(),
                    route.tgChatId(),
                    exception
            );
            throw new IllegalStateException("Telegram delivery failed", exception);
        }
    }

    public void sendInsufficientBalanceNotice(Long tgChatId, BigDecimal balance) {
        sendText(
                tgChatId,
                "Недостаточно токенов. Остаток: "
                        + balance.stripTrailingZeros().toPlainString()
                        + ". Ответ ученику не отправлен."
        );
    }

    public void sendRefundNotice(
            UUID curatorId,
            BigDecimal credits,
            BigDecimal balanceAfter
    ) {
        curatorRepository.findById(curatorId).ifPresent(curator -> sendText(
                curator.getTgChatId(),
                "VK не доставил ответ. Возвращено "
                        + credits.stripTrailingZeros().toPlainString()
                        + " credits. Баланс: "
                        + balanceAfter.stripTrailingZeros().toPlainString()
                        + "."
        ));
    }

    public void sendSystemNotification(
            Long tgChatId,
            CuratorSystemNotificationCommand command
    ) {
        if ("MANUAL_DELIVERY_FAILED".equals(command.type())) {
            sendManualDeliveryFailure(tgChatId, command);
            return;
        }
        if ("MANUAL_DELIVERY_SUCCEEDED".equals(command.type())) {
            sendManualDeliverySuccess(tgChatId, command);
            return;
        }

        String text = switch (command.type()) {
            case "RESERVATION_BLOCKED" ->
                    "Не удалось начать обработку вопроса: недостаточно доступных токенов "
                            + "или группа временно недоступна.";
            case "AI_FAILED" ->
                    "ИИ не смог подготовить ответ. Резерв токенов освобождён.";
            case "BILLING_FAILED" ->
                    "Ответ одобрен, но списание не прошло. Ответ ученику не отправлен.";
            case "REFUND_FAILED" ->
                    "VK не доставил ответ, а автоматический возврат токенов не завершился. "
                            + "Сохраните ID запроса и обратитесь в поддержку.";
            default -> command.type().startsWith("RECOVERY_EXHAUSTED_")
                    ? recoveryExhaustedText(command.workflowStatus())
                    : "Обработка вопроса остановилась на этапе "
                            + command.workflowStatus() + ".";
        };

        String message = text
                + "\n\nГруппа VK: " + command.vkGroupId()
                + "\nID запроса: " + command.requestId();
        if (command.details() != null && !command.details().isBlank()) {
            message += "\nПричина: " + truncate(command.details(), 500);
        }
        String finalMessage = message;

        try {
            executeRateLimited(() -> execute(new SendMessage(tgChatId.toString(), finalMessage)));
        } catch (TelegramApiException exception) {
            throw new IllegalStateException("Failed to send system notification", exception);
        }
    }

    private void sendManualDeliveryFailure(
            Long tgChatId,
            CuratorSystemNotificationCommand command
    ) {
        int deliveryAttempt = requireDeliveryAttempt(command);
        Optional<CuratorIntakeService.IntakeView> optionalView =
                curatorIntakeService.prepareManualDeliveryFailure(
                        command.requestId(),
                        tgChatId,
                        deliveryAttempt,
                        command.details()
                );
        if (optionalView.isEmpty()) {
            log.info(
                    "[{}] Duplicate manual delivery failure notification ignored",
                    command.requestId()
            );
            return;
        }

        CuratorIntakeService.IntakeView view = optionalView.get();
        String text = "Не удалось доставить ручной ответ ученику в VK."
                + "\n\nВопрос:\n" + truncate(view.studentQuestion(), 1200)
                + "\n\nПричина: " + truncate(view.lastDeliveryError(), 600)
                + "\n\nМожно повторить отправку или отменить её."
                + "\nID запроса: " + command.requestId();

        InlineKeyboardButton retryButton = new InlineKeyboardButton();
        retryButton.setText("Повторить отправку");
        retryButton.setCallbackData(
                "manual_retry_" + command.requestId() + "_" + deliveryAttempt
        );

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("Отменить");
        cancelButton.setCallbackData(
                "manual_cancel_" + command.requestId() + "_" + deliveryAttempt
        );

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(List.of(retryButton, cancelButton)));
        SendMessage message = new SendMessage(tgChatId.toString(), text);
        message.setReplyMarkup(keyboard);

        try {
            Message sent = executeRateLimited(() -> execute(message));
            curatorIntakeService.markManualDeliveryFailureDelivered(
                    command.requestId(),
                    tgChatId,
                    deliveryAttempt,
                    sent.getMessageId()
            );
        } catch (TelegramApiException exception) {
            throw new IllegalStateException(
                    "Failed to send manual delivery failure notification",
                    exception
            );
        }
    }

    private void sendManualDeliverySuccess(
            Long tgChatId,
            CuratorSystemNotificationCommand command
    ) {
        int deliveryAttempt = requireDeliveryAttempt(command);
        Optional<CuratorIntakeService.IntakeView> optionalView =
                curatorIntakeService.prepareManualDeliverySuccess(
                        command.requestId(),
                        tgChatId,
                        deliveryAttempt
                );
        if (optionalView.isEmpty()) {
            log.info(
                    "[{}] Duplicate manual delivery success notification ignored",
                    command.requestId()
            );
            return;
        }

        String text = deliveryAttempt == 1
                ? "Ручной ответ успешно доставлен ученику в VK."
                : "Ручной ответ успешно доставлен ученику в VK после повторной отправки.";
        sendTemporaryText(tgChatId, text);
        curatorIntakeService.markManualDeliverySuccessDelivered(
                command.requestId(),
                tgChatId,
                deliveryAttempt
        );
    }

    private int requireDeliveryAttempt(CuratorSystemNotificationCommand command) {
        if (command.deliveryAttempt() == null || command.deliveryAttempt() <= 0) {
            throw new IllegalArgumentException(
                    "Manual delivery notification has no valid attempt"
            );
        }
        return command.deliveryAttempt();
    }

    private String recoveryExhaustedText(String workflowStatus) {
        if ("AWAITING_CURATOR_ACTION".equals(workflowStatus)) {
            return "Вопрос слишком долго ожидает выбора: отправить его в ИИ или ответить вручную.";
        }
        if ("AWAITING_APPROVAL".equals(workflowStatus)) {
            return "Вопрос слишком долго ожидает решения. Проверьте карточку с ответом ИИ.";
        }
        return "Автоматическое восстановление не смогло завершить обработку вопроса. "
                + "Этап: " + workflowStatus + ".";
    }

    private void sendPhotos(Long tgChatId, List<String> photoUrls) throws TelegramApiException {
        if (photoUrls == null || photoUrls.isEmpty()) {
            return;
        }
        if (photoUrls.size() == 1) {
            SendPhoto photo = new SendPhoto(tgChatId.toString(), new InputFile(photoUrls.get(0)));
            executeRateLimited(() -> execute(photo));
            return;
        }

        List<InputMedia> mediaGroup = new ArrayList<>();
        for (String url : photoUrls) {
            mediaGroup.add(new InputMediaPhoto(url));
        }
        SendMediaGroup media = new SendMediaGroup(tgChatId.toString(), mediaGroup);
        executeRateLimited(() -> execute(media));
    }

    private Message sendApprovalMessage(
            CuratorDecisionService.DecisionView view
    ) throws TelegramApiException {
        SendMessage textMessage = new SendMessage(
                view.tgChatId().toString(),
                buildApprovalText(view)
        );
        textMessage.setReplyMarkup(createApprovalKeyboard(
                view.requestId(),
                view.revision()
        ));
        return executeRateLimited(() -> execute(textMessage));
    }

    private String buildIntakeText(String question) {
        return "Новый вопрос от ученика:\n\n" + truncate(question, 3800);
    }

    private String buildManualAnswerText(String question, String answer) {
        String prefix = "Вопрос ученика:\n"
                + truncate(question, 1400)
                + "\n\nВаш ответ:\n";
        return prefix + truncate(answer, Math.max(0, 4000 - prefix.length()));
    }

    private String buildEditPromptText(String answer) {
        String prefix = "Текущий ответ ИИ:\n\n";
        String suffix = "\n\nОтветьте на это сообщение исправленным вариантом.";
        int answerLimit = Math.max(0, 4000 - prefix.length() - suffix.length());
        return prefix + truncate(answer, answerLimit) + suffix;
    }

    private String buildApprovalText(CuratorDecisionService.DecisionView view) {
        String question = truncate(view.studentQuestion(), 1000);
        String cost = view.creditsToCharge().stripTrailingZeros().toPlainString();
        String prefix = "Вопрос от ученика:\n" + question + "\n\nОтвет ИИ:\n";
        String suffix = "\n\nСтоимость: " + cost + " credits";
        int answerLimit = Math.max(0, 4000 - prefix.length() - suffix.length());
        return prefix + truncate(view.currentAnswer(), answerLimit) + suffix;
    }

    private InlineKeyboardMarkup createApprovalKeyboard(UUID requestId, int revision) {
        InlineKeyboardButton approveButton = new InlineKeyboardButton();
        approveButton.setText("Одобрить и отправить");
        approveButton.setCallbackData("approve_" + requestId + "_" + revision);

        InlineKeyboardButton editButton = new InlineKeyboardButton();
        editButton.setText("Изменить");
        editButton.setCallbackData("edit_" + requestId + "_" + revision);

        InlineKeyboardButton rejectButton = new InlineKeyboardButton();
        rejectButton.setText("Отклонить");
        rejectButton.setCallbackData("reject_" + requestId + "_" + revision);

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(
                List.of(approveButton),
                List.of(editButton, rejectButton)
        ));
        return keyboard;
    }

    private Optional<DecisionCallback> parseDecisionCallback(
            String callbackData,
            String prefix
    ) {
        if (callbackData == null || !callbackData.startsWith(prefix)) {
            return Optional.empty();
        }
        String value = callbackData.substring(prefix.length());
        int revisionSeparator = value.lastIndexOf('_');
        if (revisionSeparator <= 0 || revisionSeparator == value.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DecisionCallback(
                    UUID.fromString(value.substring(0, revisionSeparator)),
                    Integer.parseInt(value.substring(revisionSeparator + 1))
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void removeKeyboard(Long tgChatId, Integer messageId, UUID requestId) {
        EditMessageReplyMarkup removeKeyboard = new EditMessageReplyMarkup();
        removeKeyboard.setChatId(tgChatId.toString());
        removeKeyboard.setMessageId(messageId);
        removeKeyboard.setReplyMarkup(null);
        try {
            executeRateLimited(() -> execute(removeKeyboard));
        } catch (TelegramApiException exception) {
            log.warn("[{}] Failed to remove stale Telegram keyboard", requestId, exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private record DecisionCallback(UUID requestId, int revision) {
    }

    private record ManualDeliveryCallback(UUID requestId, int deliveryAttempt) {
    }

    private void sendMainMenu(Long tgChatId, String text) {
        SendMessage message = new SendMessage(tgChatId.toString(), text);
        message.setReplyMarkup(createMainMenuKeyboard());
        executeSafely(message, tgChatId);
    }

    private void answerCallback(String callbackQueryId) {
        try {
            executeRateLimited(() ->
                    execute(new AnswerCallbackQuery(callbackQueryId))
            );
        } catch (TelegramApiException exception) {
            log.debug(
                    "Failed to answer Telegram callback query {}",
                    callbackQueryId,
                    exception
            );
        }
    }

    private ReplyKeyboardMarkup createMainMenuKeyboard() {
        KeyboardRow finance = new KeyboardRow();
        finance.add(MENU_BALANCE);
        finance.add(MENU_BUY);

        KeyboardRow communities = new KeyboardRow();
        communities.add(MENU_GROUPS);
        communities.add(MENU_ADD_GROUP);

        KeyboardRow audience = new KeyboardRow();
        audience.add(MENU_STUDENTS);
        audience.add(MENU_BROADCAST);

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>(
                List.of(finance, communities, audience)
        );
        if (miniAppUrl != null && !miniAppUrl.isBlank()) {
            KeyboardButton appButton = new KeyboardButton(MENU_APP);
            appButton.setWebApp(new WebAppInfo(miniAppUrl));
            KeyboardRow app = new KeyboardRow();
            app.add(appButton);
            rows.add(app);
        }
        keyboard.setKeyboard(rows);
        keyboard.setResizeKeyboard(true);
        keyboard.setIsPersistent(true);
        keyboard.setOneTimeKeyboard(false);
        return keyboard;
    }

    private void sendMiniAppButton(Long tgChatId) {
        if (miniAppUrl == null || miniAppUrl.isBlank()) {
            sendText(tgChatId, "Mini App пока не опубликован.");
            return;
        }

        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(MENU_APP);
        button.setWebApp(new WebAppInfo(miniAppUrl));
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(List.of(List.of(button)));

        SendMessage message = new SendMessage(
                tgChatId.toString(),
                "Откройте кабинет куратора:"
        );
        message.setReplyMarkup(keyboard);
        executeSafely(message, tgChatId);
    }

    private void sendTemporaryText(Long tgChatId, String text) {
        try {
            Message sent = executeRateLimited(() ->
                    execute(new SendMessage(tgChatId.toString(), text))
            );
            taskScheduler.schedule(
                    () -> deleteMessageSafely(
                            tgChatId,
                            sent.getMessageId(),
                            null
                    ),
                    Instant.now().plus(transientMessageTtl)
            );
        } catch (TelegramApiException exception) {
            log.error(
                    "Failed to send temporary Telegram message to chat {}",
                    tgChatId,
                    exception
            );
        }
    }

    private void deleteMessageSafely(
            Long tgChatId,
            Integer messageId,
            UUID requestId
    ) {
        if (messageId == null) {
            return;
        }
        try {
            executeRateLimited(() ->
                    execute(new DeleteMessage(tgChatId.toString(), messageId))
            );
        } catch (TelegramApiException exception) {
            if (requestId == null) {
                log.debug(
                        "Failed to delete Telegram message {} in chat {}",
                        messageId,
                        tgChatId,
                        exception
                );
            } else {
                log.warn(
                        "[{}] Failed to delete Telegram message {} in chat {}",
                        requestId,
                        messageId,
                        tgChatId,
                        exception
                );
            }
        }
    }

    private void editMessageText(
            Long tgChatId,
            Integer messageId,
            String text,
            InlineKeyboardMarkup keyboard,
            UUID requestId
    ) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(tgChatId.toString());
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setReplyMarkup(keyboard);
        try {
            executeRateLimited(() -> execute(edit));
        } catch (TelegramApiException exception) {
            log.warn(
                    "[{}] Failed to update Telegram message {}",
                    requestId,
                    messageId,
                    exception
            );
        }
    }

    private void sendText(Long tgChatId, String text) {
        executeSafely(new SendMessage(tgChatId.toString(), text), tgChatId);
    }

    private void executeSafely(SendMessage message, Long tgChatId) {
        try {
            executeRateLimited(() -> execute(message));
        } catch (TelegramApiException exception) {
            log.error("Failed to send Telegram message to chat {}", tgChatId, exception);
        }
    }

    private <T> T executeRateLimited(TelegramCall<T> call) throws TelegramApiException {
        int retryAttempt = 0;
        while (true) {
            telegramRateLimiter.acquire();
            try {
                return call.execute();
            } catch (TelegramApiRequestException exception) {
                Integer retryAfter = exception.getParameters() == null
                        ? null
                        : exception.getParameters().getRetryAfter();
                if (exception.getErrorCode() == null
                        || exception.getErrorCode() != 429
                        || retryAfter == null
                        || retryAttempt >= rateLimitProperties.getMaxTelegramRetries()) {
                    throw exception;
                }

                retryAttempt++;
                log.warn(
                        "Telegram rate limit response received, retrying after {} seconds, attempt {}",
                        retryAfter,
                        retryAttempt
                );
                sleepForTelegramRetry(retryAfter);
            }
        }
    }

    private void sleepForTelegramRetry(int retryAfterSeconds) {
        try {
            Thread.sleep(Math.max(1, retryAfterSeconds) * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Telegram retry", exception);
        }
    }

    @FunctionalInterface
    private interface TelegramCall<T> {
        T execute() throws TelegramApiException;
    }
}
