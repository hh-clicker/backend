package org.hhclicker.backend.application.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.service.ApplicationService;
import org.hhclicker.backend.settings.telegram.TelegramMessageService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TelegramApplicationCompletionWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TelegramApplicationCompletionWorker.class);
    private static final long WARNING_INTERVAL_NANOS = java.time.Duration.ofMinutes(1).toNanos();
    private static final long REFRESH_DELAY_MILLIS = 2_000;

    private final ApplicationService applications;
    private final TelegramMessageService telegram;
    private final TelegramRunMessageRepository runMessages;
    private final TelegramAttentionMessageRepository attentionMessages;
    private final TelegramRunCompletionRepository completions;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;
    private boolean warningReported;
    private long lastWarningNanos;

    public TelegramApplicationCompletionWorker(ApplicationService applications, TelegramMessageService telegram,
                                               TelegramRunMessageRepository runMessages,
                                               TelegramAttentionMessageRepository attentionMessages,
                                               TelegramRunCompletionRepository completions) {
        this.applications = applications;
        this.telegram = telegram;
        this.runMessages = runMessages;
        this.attentionMessages = attentionMessages;
        this.completions = completions;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.ofVirtual().name("telegram-application-completions").start(this::run);
    }

    private void run() {
        while (running.get()) {
            try {
                synchronize();
            } catch (RuntimeException failure) {
                warn("synchronize", null, failure);
                // Следующая итерация повторит синхронизацию; очередь откликов от Telegram не зависит.
            }
            pause();
        }
    }

    void synchronize() {
        TelegramMessageService.Target target = telegram.connectedTarget().orElse(null);
        if (target == null) return;
        for (UUID runId : runMessages.completionCandidates(target)) {
            try {
                create(target, applications.find(runId));
            } catch (RuntimeException failure) {
                warn("create", runId, failure);
                // Retry this run later, but keep delivering other completion messages.
            }
        }
        for (Map.Entry<UUID, TelegramRunCompletionRepository.Message> entry
                : completions.findAll(target).entrySet()) {
            try {
                Run run = applications.find(entry.getKey());
                TelegramRunCompletionRepository.Message message = entry.getValue();
                if (message.confirmed()) clean(target, run, message);
                else update(target, run, message);
            } catch (RuntimeException failure) {
                warn("refresh", entry.getKey(), failure);
                // A stale/deleted/temporarily unavailable message cannot block another run.
            }
        }
    }

    private void create(TelegramMessageService.Target target, Run run) {
        TelegramRunCompletionPresentation.Presentation presentation =
                TelegramRunCompletionPresentation.from(run, false);
        long messageId = telegram.send(target, presentation.text(), presentation.buttons());
        completions.save(run.id(), target, messageId, presentation.fingerprint());
    }

    private void update(TelegramMessageService.Target target, Run run,
                        TelegramRunCompletionRepository.Message message) {
        TelegramRunCompletionPresentation.Presentation presentation =
                TelegramRunCompletionPresentation.from(run, false);
        if (message.fingerprint().equals(presentation.fingerprint())) return;
        telegram.edit(target, message.messageId(), presentation.text(), presentation.buttons());
        completions.update(run.id(), target, presentation.fingerprint(), false);
    }

    private void clean(TelegramMessageService.Target target, Run run,
                       TelegramRunCompletionRepository.Message message) {
        boolean deleted = true;
        for (long messageId : attentionMessages.messageIds(run.id(), target)) {
            try {
                telegram.delete(target, messageId);
            } catch (RuntimeException failure) {
                warn("cleanup", run.id(), failure);
                deleted = false;
            }
        }
        var progress = runMessages.find(run.id(), target);
        if (progress.isPresent()) {
            try {
                telegram.delete(target, progress.get().messageId());
            } catch (RuntimeException failure) {
                warn("cleanup", run.id(), failure);
                deleted = false;
            }
        }
        // Keep cleanup pending until every message was deleted; missing messages are idempotent in Bot API.
        if (!deleted) return;
        attentionMessages.closeRun(run.id(), target);
        TelegramRunCompletionPresentation.Presentation presentation =
                TelegramRunCompletionPresentation.from(run, true);
        if (!message.fingerprint().equals(presentation.fingerprint())) {
            telegram.edit(target, message.messageId(), presentation.text(), presentation.buttons());
        }
        completions.update(run.id(), target, presentation.fingerprint(), true);
        runMessages.markCompletionHandled(run.id(), target);
    }

    private synchronized void warn(String operation, UUID runId, RuntimeException failure) {
        long now = System.nanoTime();
        if (warningReported && now - lastWarningNanos < WARNING_INTERVAL_NANOS) return;
        warningReported = true;
        lastWarningNanos = now;
        // Do not pass the throwable: HTTP exception messages/causes can contain the bot token.
        log.warn("Telegram completion deferred: operation={}, run={}, type={}",
                operation, runId, failure.getClass().getSimpleName());
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
