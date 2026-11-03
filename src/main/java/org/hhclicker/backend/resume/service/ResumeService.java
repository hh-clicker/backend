package org.hhclicker.backend.resume.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.resume.exception.InvalidResumeDataException;
import org.hhclicker.backend.resume.exception.ResumeAlreadyExistsException;
import org.hhclicker.backend.resume.exception.ResumeHasDependentDataException;
import org.hhclicker.backend.resume.exception.ResumeNotFoundException;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.repository.ResumeRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final AccountService accountService;
    private final ResumeStatisticsProvider resumeStatisticsProvider;
    private final Clock clock;

    public ResumeService(
            ResumeRepository resumeRepository,
            AccountService accountService,
            ResumeStatisticsProvider resumeStatisticsProvider,
            Clock clock
    ) {
        this.resumeRepository = resumeRepository;
        this.accountService = accountService;
        this.resumeStatisticsProvider = resumeStatisticsProvider;
        this.clock = clock;
    }

    public List<ResumeProfile> findAll(UUID accountId) {
        if (accountId != null) {
            HhAccount account = accountService.findById(accountId);
            return resumeRepository.findAllByAccountId(accountId).stream()
                    .map(resume -> toProfile(resume, account.name()))
                    .toList();
        }
        return resumeRepository.findAll().stream()
                .map(this::toProfile)
                .toList();
    }

    public ResumeProfile findById(UUID resumeId) {
        return toProfile(findResume(resumeId));
    }

    @Transactional
    public List<ResumeProfile> importResumes(UUID accountId, List<ImportResumeItem> items) {
        HhAccount account = accountService.findById(accountId);
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new InvalidResumeDataException("Выберите от 1 до 100 резюме.");
        }
        var result = new ArrayList<ResumeProfile>();
        var seen = new HashSet<String>();
        for (ImportResumeItem item : items) {
            String hash = normalizeRequired(item.hhResumeId(), 100, "Не указан идентификатор резюме HH.");
            if (!hash.matches("[a-f0-9]{20,100}")) throw new InvalidResumeDataException("Некорректный resumeHash.");
            if (!seen.add(hash)) continue;
            String name = normalizeRequired(item.name(), 120, "Название резюме не должно быть пустым.");
            String plainText = normalizeRequired(item.plainText(), 100000, "Не загружен текст резюме. Повторите добавление через браузер HH.");
            Instant now = clock.instant();
            HhResume candidate = new HhResume(UUID.randomUUID(), accountId, hash, name,
                    ResumeAutomationSettings.defaults(), now, now, plainText, now);
            resumeRepository.insertIfAbsent(candidate);
            HhResume stored = resumeRepository.findByHhResumeId(hash).orElseThrow(() -> new ResumeAlreadyExistsException(hash));
            if (!stored.accountId().equals(accountId)) {
                throw new InvalidResumeDataException("Резюме «%s» уже связано с другим локальным HH-аккаунтом. Выберите этот аккаунт или снимите галочку.".formatted(name));
            }
            result.add(toProfile(stored, account.name()));
        }
        return result;
    }

    @Transactional
    public ResumeProfile updateSettings(UUID resumeId, UpdateResumeSettingsCommand command) {
        HhResume existing = findResume(resumeId);
        ResumeAutomationSettings settings = validateSettings(command);
        HhResume updated = new HhResume(
                existing.id(), existing.accountId(), existing.hhResumeId(), existing.name(),
                settings, existing.createdAt(), clock.instant(), existing.plainText(), existing.textLoadedAt()
        );
        if (!resumeRepository.updateSettings(updated)) {
            throw new ResumeNotFoundException(resumeId);
        }
        return toProfile(updated);
    }

    @Transactional
    public void delete(UUID resumeId) {
        try {
            if (!resumeRepository.deleteById(resumeId)) {
                throw new ResumeNotFoundException(resumeId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResumeHasDependentDataException(exception);
        }
    }

    private HhResume findResume(UUID resumeId) {
        return resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResumeNotFoundException(resumeId));
    }

    private ResumeProfile toProfile(HhResume resume) {
        return toProfile(resume, accountService.findById(resume.accountId()).name());
    }

    private ResumeProfile toProfile(HhResume resume, String accountName) {
        return new ResumeProfile(resume, accountName, resumeStatisticsProvider.get(resume.id()));
    }

    private ResumeAutomationSettings validateSettings(UpdateResumeSettingsCommand command) {
        var ai = command.aiLetter() == null ? org.hhclicker.backend.resume.model.AiLetterSettings.defaults() : command.aiLetter();
        ai.validate();
        org.hhclicker.backend.resume.model.AiLetterSettings.validateFallback(command.coverLetter());
        if (command.dailyLimit() < 1 || command.dailyLimit() > 500) {
            throw new InvalidResumeDataException("Дневной лимит должен быть от 1 до 500.");
        }
        if (command.pauseFromSeconds() < 10) {
            throw new InvalidResumeDataException("Минимальная пауза должна быть не меньше 10 секунд.");
        }
        if (command.pauseToSeconds() < command.pauseFromSeconds()) {
            throw new InvalidResumeDataException("Максимальная пауза должна быть не меньше минимальной.");
        }
        return new ResumeAutomationSettings(
                command.coverLetter() == null ? "" : command.coverLetter().trim(),
                command.dailyLimit(),
                command.pauseFromSeconds(),
                command.pauseToSeconds(),
                command.skipQuestionnaires(), ai
        );
    }

    private String normalizeRequired(String value, int maximumLength, String emptyMessage) {
        if (value == null || value.isBlank()) {
            throw new InvalidResumeDataException(emptyMessage);
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidResumeDataException("Значение не должно быть длиннее %d символов.".formatted(maximumLength));
        }
        return normalized;
    }

}
