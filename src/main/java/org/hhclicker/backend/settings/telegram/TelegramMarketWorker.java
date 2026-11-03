package org.hhclicker.backend.settings.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.hhclicker.backend.market.MarketAnalysisService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TelegramMarketWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TelegramMarketWorker.class);
    private final MarketAnalysisService market;
    private final TelegramMessageService telegram;
    private final TelegramMarketMessageRepository messages;
    private final AtomicBoolean running = new AtomicBoolean();
    private TelegramMessageService.Target observedTarget;
    private Instant connectedSince;
    private volatile Thread worker;

    public TelegramMarketWorker(MarketAnalysisService market, TelegramMessageService telegram,
                                TelegramMarketMessageRepository messages) {
        this.market = market;
        this.telegram = telegram;
        this.messages = messages;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.ofVirtual().name("telegram-market-analysis").start(this::run);
    }

    private void run() {
        while (running.get()) {
            try {
                synchronize();
            } catch (RuntimeException exception) {
                log.warn("Не удалось синхронизировать Telegram-сообщения анализа рынка", exception);
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void synchronize() {
        TelegramMessageService.Target target = telegram.connectedTarget().orElse(null);
        if (target == null) {
            observedTarget = null;
            return;
        }
        if (!target.equals(observedTarget)) {
            observedTarget = target;
            connectedSince = Instant.now();
        }
        Map<UUID, TelegramMarketMessageRepository.Message> existing = messages.findAll(target);
        for (MarketAnalysisService.NotificationRun run : market.notificationRuns()) {
            try {
                synchronize(target, run, existing.get(run.id()));
            } catch (RuntimeException exception) {
                log.warn("Не удалось обновить Telegram-сообщение анализа {}", run.id(), exception);
            }
        }
    }

    private void synchronize(TelegramMessageService.Target target, MarketAnalysisService.NotificationRun run,
                             TelegramMarketMessageRepository.Message existing) {
        // Старую историю не рассылаем, но быстрый сбой между двумя опросами не должен теряться.
        if (existing == null && !TelegramMarketPresentation.active(run.status())
                && (run.updatedAt() == null || run.updatedAt().isBefore(connectedSince))) return;
        String text = TelegramMarketPresentation.text(run);
        String fingerprint = TelegramMarketPresentation.fingerprint(text);
        if (existing == null) {
            long messageId = telegram.send(target, text, List.of());
            messages.save(run.id(), target, messageId, fingerprint);
            return;
        }
        if (existing.fingerprint().equals(fingerprint)) return;
        if ("COMPLETED".equals(run.status()) || "FAILED".equals(run.status())
                || "CANCELLED".equals(run.status())) {
            // Редактирование не присылает нового уведомления: итог должен прийти новым сообщением.
            try {
                telegram.delete(target, existing.messageId());
            } catch (RuntimeException exception) {
                log.warn("Не удалось убрать прежний прогресс анализа {}", run.id(), exception);
            }
            long messageId = telegram.send(target, text, List.of());
            messages.save(run.id(), target, messageId, fingerprint);
            return;
        }
        try {
            telegram.edit(target, existing.messageId(), text, List.of());
            messages.updateFingerprint(run.id(), target, fingerprint);
        } catch (TelegramMessageNotFoundException exception) {
            // Пользователь мог удалить статусное сообщение; следующее изменение восстановит его.
            long messageId = telegram.send(target, text, List.of());
            messages.save(run.id(), target, messageId, fingerprint);
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
