package org.hhclicker.backend.application.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.service.NotificationService;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;
import org.hhclicker.backend.settings.telegram.TelegramMessageNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TelegramApplicationRunWorker implements SmartLifecycle {
    private static final long REFRESH_DELAY_MILLIS = 2_000;
    private static final Logger log = LoggerFactory.getLogger(TelegramApplicationRunWorker.class);

    private final NotificationService notifications;
    private final TelegramMessageService telegram;
    private final TelegramRunMessageRepository messages;
    private final Map<UUID, String> observedStatuses = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    public TelegramApplicationRunWorker(NotificationService notifications, TelegramMessageService telegram,
                                        TelegramRunMessageRepository messages) {
        this.notifications = notifications;
        this.telegram = telegram;
        this.messages = messages;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.ofVirtual().name("telegram-application-runs").start(this::run);
    }

    private void run() {
        while (running.get()) {
            try {
                synchronize();
            } catch (RuntimeException ignored) {
                // Следующая итерация повторит синхронизацию; очередь откликов от Telegram не зависит.
            }
            pause();
        }
    }

    void synchronize() {
        TelegramMessageService.Target target = telegram.connectedTarget().orElse(null);
        if (target == null) return;
        Map<UUID, TelegramRunMessageRepository.Message> existing = messages.findAll(target);
        for (NotificationRun run : notifications.runs()) {
            try {
                synchronize(target, run, existing.get(run.id()));
            } catch (RuntimeException exception) {
                log.warn("Не удалось синхронизировать Telegram-сообщение запуска {}", run.id(), exception);
            }
        }
    }

    private void synchronize(TelegramMessageService.Target target, NotificationRun run,
                             TelegramRunMessageRepository.Message message) {
        if (message == null && !TelegramRunPresentation.active(run.status())) return;
        TelegramRunPresentation.Presentation presentation = TelegramRunPresentation.from(run);
        if (message == null) {
            long messageId = telegram.send(target, presentation.text(), presentation.buttons());
            messages.save(run.id(), target, messageId, presentation.fingerprint());
            observedStatuses.put(run.id(), run.status());
        } else {
            String previousStatus = observedStatuses.get(run.id());
            if (TelegramRunPresentation.active(run.status())
                    && (previousStatus == null || !previousStatus.equals(run.status()))) {
                replace(target, run, message.messageId(), presentation);
                observedStatuses.put(run.id(), run.status());
                return;
            }
            observedStatuses.put(run.id(), run.status());
            boolean changed = !message.fingerprint().equals(presentation.fingerprint());
            if (!changed) return;
            try {
                telegram.edit(target, message.messageId(), presentation.text(), presentation.buttons());
                messages.updateFingerprint(run.id(), target, presentation.fingerprint());
            } catch (TelegramMessageNotFoundException exception) {
                long messageId = telegram.send(target, presentation.text(), presentation.buttons());
                messages.save(run.id(), target, messageId, presentation.fingerprint());
            }
        }
    }

    private void replace(TelegramMessageService.Target target, NotificationRun run, long oldMessageId,
                         TelegramRunPresentation.Presentation presentation) {
        try {
            telegram.delete(target, oldMessageId);
        } catch (RuntimeException exception) {
            log.warn("Не удалось удалить прежнее Telegram-сообщение запуска {}", run.id(), exception);
        }
        long messageId = telegram.send(target, presentation.text(), presentation.buttons());
        messages.save(run.id(), target, messageId, presentation.fingerprint());
    }

    private void pause() {
        try {
            Thread.sleep(REFRESH_DELAY_MILLIS);
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
