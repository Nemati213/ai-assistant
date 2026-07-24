package ru.itmo.nemat.tgconnector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.nemat.tgconnector.model.RegistrationContext;
import ru.itmo.nemat.tgconnector.model.RegistrationState;
import ru.itmo.nemat.tgconnector.repository.RegistrationContextRepository;

@Component
@RequiredArgsConstructor
public class RegistrationStateManager {

    private final RegistrationContextRepository repository;

    @Transactional
    public RegistrationContext getContext(Long tgChatId) {
        return repository.findById(tgChatId)
                .orElseGet(() -> {
                    RegistrationContext newContext = RegistrationContext.builder()
                            .tgChatId(tgChatId)
                            .state(RegistrationState.NONE)
                            .build();
                    return repository.save(newContext);
                });
    }

    @Transactional
    public void updateState(Long tgChatId, RegistrationState state) {
        RegistrationContext context = getContext(tgChatId);
        context.setState(state);
        repository.save(context);
    }

    @Transactional
    public void saveContext(RegistrationContext context) {
        repository.save(context);
    }

    @Transactional
    public void clear(Long tgChatId) {
        repository.deleteById(tgChatId);
    }
}