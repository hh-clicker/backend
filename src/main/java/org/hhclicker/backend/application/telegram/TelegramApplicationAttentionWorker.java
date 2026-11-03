package org.hhclicker.backend.application.telegram;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.hhclicker.backend.application.model.ApplicationModels.Attention;
import org.hhclicker.backend.application.model.ApplicationModels.RunMode;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;
import org.hhclicker.backend.settings.telegram.TelegramMessageNotFoundException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TelegramApplicationAttentionWorker implements SmartLifecycle {
    private static final long REFRESH_DELAY_MILLIS = 2_000;

    private final ApplicationService applications;
    private final TelegramMessageService telegram;
    private final TelegramAttentionMessageRepository messages;
    private final Set<UUID> observed = new HashSet<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    public TelegramApplicationAttentionWorker(ApplicationService applications, TelegramMessageService telegram,
                                              TelegramAttentionMessageRepository messages) {
        this.applications = applications;
        this.telegram = telegram;
        this.messages = messages;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.ofVirtual().name("telegram-application-attention").start(this::run);
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
        Map<UUID, TelegramAttentionMessageRepository.Message> unresolved = new HashMap<>(messages.findOpen(target));
        for (Attention attention : applications.openAttention()) {
            if (attention.mode() == RunMode.DRY_RUN) continue;
            try {
                synchronize(target, attention, unresolved.remove(attention.id()));
            } catch (RuntimeException ignored) {
                // One unavailable message must not block the remaining tasks; retry next iteration.
            }
        }
        for (Map.Entry<UUID, TelegramAttentionMessageRepository.Message> entry : unresolved.entrySet()) {
            try {
                synchronize(target, applications.attentionTask(entry.getKey()), entry.getValue());
            } catch (RuntimeException ignored) {
                // Failed cleanup is retried without starving other messages.
            }
        }
    }

    private void synchronize(TelegramMessageService.Target target, Attention attention,
                             TelegramAttentionMessageRepository.Message message) {
        TelegramAttentionPresentation.Presentation presentation = TelegramAttentionPresentation.from(attention);
        if (presentation.closed()) {
            if (message == null) return;
            telegram.delete(target, message.messageId());
            messages.update(attention.id(), target, presentation.fingerprint(), true);
        } else if (message == null) {
            long messageId = telegram.send(target, presentation.text(), presentation.buttons());
            messages.save(attention.id(), target, messageId, presentation.fingerprint());
            observed.add(attention.id());
        } else {
            if (!observed.contains(attention.id())) {
                replace(target, attention, message.messageId(), presentation);
                observed.add(attention.id());
                return;
            }
            boolean changed = !message.fingerprint().equals(presentation.fingerprint());
            if (!changed) return;
            try {
                telegram.edit(target, message.messageId(), presentation.text(), presentation.buttons());
                messages.update(attention.id(), target, presentation.fingerprint(), false);
            } catch (TelegramMessageNotFoundException exception) {
                long messageId = telegram.send(target, presentation.text(), presentation.buttons());
                messages.save(attention.id(), target, messageId, presentation.fingerprint());
            }
        }
    }

    private void replace(TelegramMessageService.Target target, Attention attention, long oldMessageId,
                         TelegramAttentionPresentation.Presentation presentation) {
        try {
            telegram.delete(target, oldMessageId);
        } catch (RuntimeException ignored) {
            // Новое сообщение важнее возможного дубликата; следующая обработка задачи очистит сохранённый новый ID.
        }
        long messageId = telegram.send(target, presentation.text(), presentation.buttons());
        messages.save(attention.id(), target, messageId, presentation.fingerprint());
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
