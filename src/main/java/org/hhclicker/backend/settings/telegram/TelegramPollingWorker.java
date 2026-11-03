package org.hhclicker.backend.settings.telegram;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.hhclicker.backend.shared.exception.ApplicationException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

@Component
public class TelegramPollingWorker implements SmartLifecycle {
    private static final int POLL_TIMEOUT_SECONDS = 20;
    private static final long IDLE_DELAY_MILLIS = 1_000;
    private static final long ERROR_DELAY_MILLIS = 3_000;

    private final TelegramConnectionRepository repository;
    private final TelegramConnectionService service;
    private final TelegramBotApi api;
    private final List<TelegramCallbackHandler> callbackHandlers;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    public TelegramPollingWorker(TelegramConnectionRepository repository, TelegramConnectionService service,
                                 TelegramBotApi api, List<TelegramCallbackHandler> callbackHandlers) {
        this.repository = repository;
        this.service = service;
        this.api = api;
        this.callbackHandlers = callbackHandlers;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.ofVirtual().name("telegram-long-polling").start(this::run);
    }

    private void run() {
        while (running.get()) {
            TelegramConnection connection = null;
            try {
                connection = repository.get();
                if (!connection.configured()) {
                    pause(IDLE_DELAY_MILLIS);
                    continue;
                }
                poll(connection);
            } catch (ApplicationException exception) {
                recordError(connection, exception.safeMessage());
                pause(ERROR_DELAY_MILLIS);
            } catch (RuntimeException exception) {
                recordError(connection, "Не удалось получить обновления Telegram.");
                pause(ERROR_DELAY_MILLIS);
            }
        }
    }

    void poll(TelegramConnection connection) {
        boolean failed = false;
        for (TelegramBotApi.Update update : api.updates(connection.botToken(), connection.updateOffset(),
                POLL_TIMEOUT_SECONDS)) {
            try {
                process(connection, update);
            } catch (ApplicationException exception) {
                failed = true;
                recordError(connection, exception.safeMessage());
            } catch (RuntimeException exception) {
                failed = true;
                recordError(connection, "Не удалось обработать обновление Telegram.");
            }
        }
        if (!failed) repository.clearError(connection.botToken());
    }

    private void process(TelegramConnection connection, TelegramBotApi.Update update) {
        try {
            if (service.accept(connection, update)) {
                api.sendMessage(connection.botToken(), update.message().chatId(),
                        "✅ HH Clicker подключён к этому чату.", List.of());
            } else if (update.callback() != null) {
                processCallback(connection, update.callback());
            }
        } finally {
            repository.advanceOffset(connection.botToken(), update.id() + 1);
        }
    }

    private void processCallback(TelegramConnection connection, TelegramBotApi.Callback callback) {
        if (!connection.connected() || callback.chatId() != connection.chatId()
                || callback.userId() == null || !callback.userId().equals(connection.telegramUserId())) {
            api.answerCallback(connection.botToken(), callback.id(), "Эта кнопка недоступна для данного чата.");
            return;
        }
        try {
            String answer = callbackHandlers.stream().map(handler -> handler.handle(callback.data()))
                    .flatMap(java.util.Optional::stream).findFirst().orElse("Кнопка больше не поддерживается.");
            api.answerCallback(connection.botToken(), callback.id(), answer);
        } catch (ApplicationException exception) {
            api.answerCallback(connection.botToken(), callback.id(), exception.safeMessage());
        } catch (RuntimeException exception) {
            api.answerCallback(connection.botToken(), callback.id(), "Не удалось выполнить действие.");
        }
    }

    private void recordError(TelegramConnection connection, String message) {
        if (connection == null || !connection.configured()) return;
        try {
            repository.recordError(connection.botToken(), message);
        } catch (RuntimeException ignored) {
            // Временная ошибка БД не должна навсегда останавливать polling.
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Thread current = worker;
        if (current != null) current.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
