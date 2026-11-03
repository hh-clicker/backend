package org.hhclicker.backend.vacancy.selection.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.vacancy.catalog.exception.InvalidVacancyDataException;
import org.hhclicker.backend.vacancy.catalog.exception.VacancyNotFoundException;
import org.hhclicker.backend.vacancy.catalog.model.Vacancy;
import org.hhclicker.backend.vacancy.catalog.model.VacancyListing;
import org.hhclicker.backend.vacancy.catalog.model.VacancyStatus;
import org.hhclicker.backend.vacancy.catalog.repository.VacancyRepository;
import org.hhclicker.backend.vacancy.selection.exception.VacancyTransitionNotAllowedException;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Пользовательский отбор вакансий: очередь откликов, пропуск и восстановление.
 * <p>
 * Постановка в очередь не меняет статус вакансии и поэтому не перемещает её между вкладками UI.
 */
@Service
@Transactional(readOnly = true)
public class VacancySelectionService {

    private final VacancyRepository vacancyRepository;
    private final ResumeRepository resumeRepository;
    private final Clock clock;

    public VacancySelectionService(VacancyRepository vacancyRepository, ResumeRepository resumeRepository, Clock clock) {
        this.vacancyRepository = vacancyRepository;
        this.resumeRepository = resumeRepository;
        this.clock = clock;
    }

    @Transactional
    public VacancyListing setQueued(UUID vacancyId, boolean queuedForApplication) {
        return setQueued(List.of(vacancyId), queuedForApplication).getFirst();
    }

    /**
     * Массовое изменение очереди выполняется целиком: недопустимый переход хотя бы одной вакансии
     * отменяет весь запрос.
     */
    @Transactional
    public List<VacancyListing> setQueued(List<UUID> vacancyIds, boolean queuedForApplication) {
        List<UUID> requestedIds = distinctIds(vacancyIds);
        List<Vacancy> vacancies = findAllRequired(requestedIds);
        if (queuedForApplication) {
            Map<UUID, HhResume> resumes = new HashMap<>();
            for (Vacancy vacancy : vacancies) {
                ensureQueueable(vacancy, resumes);
            }
        }

        Instant now = clock.instant();
        for (Vacancy vacancy : vacancies) {
            if (vacancy.queuedForApplication() != queuedForApplication) {
                updateState(vacancy, vacancy.withState(
                        vacancy.status(), vacancy.banScope(), queuedForApplication, now
                ));
            }
        }
        return listings(requestedIds);
    }

    @Transactional
    public VacancyListing skip(UUID vacancyId) {
        Vacancy vacancy = findRequired(vacancyId);
        if (!vacancy.status().isActive()) {
            throw new VacancyTransitionNotAllowedException(
                    "Пропустить можно только новую или уже пропущенную вакансию."
            );
        }
        updateState(vacancy, vacancy.withState(VacancyStatus.USER_SKIPPED, null, false, clock.instant()));
        return listing(vacancyId);
    }

    @Transactional
    public void closeFromHh(UUID vacancyId) {
        vacancyRepository.markClosed(vacancyId, clock.instant());
    }

    @Transactional
    public VacancyListing restore(UUID vacancyId) {
        Vacancy vacancy = findRequired(vacancyId);
        if (vacancy.status() != VacancyStatus.USER_SKIPPED) {
            throw new VacancyTransitionNotAllowedException(
                    "Вернуть в работу можно только пропущенную вакансию."
            );
        }
        updateState(vacancy, vacancy.withState(
                VacancyStatus.NEW, null, vacancy.queuedForApplication(), clock.instant()
        ));
        return listing(vacancyId);
    }

    @Transactional
    public VacancyListing markQuestionnaireApplied(UUID vacancyId) {
        Vacancy vacancy = findRequired(vacancyId);
        if (vacancy.status() == VacancyStatus.ALREADY_APPLIED) {
            return listing(vacancyId);
        }
        if (vacancy.status() != VacancyStatus.NEW || !requiresQuestionnaire(vacancy)) {
            throw new VacancyTransitionNotAllowedException(
                    "Отметить ручной отклик можно только для новой вакансии с анкетой."
            );
        }
        updateState(vacancy, vacancy.withState(
                VacancyStatus.ALREADY_APPLIED, null, false, clock.instant()
        ));
        return listing(vacancyId);
    }

    public Vacancy questionnaireVerificationTarget(UUID vacancyId) {
        Vacancy vacancy = findRequired(vacancyId);
        if (vacancy.status() != VacancyStatus.NEW || !requiresQuestionnaire(vacancy)) {
            throw new VacancyTransitionNotAllowedException(
                    "Проверить ручной отклик можно только для новой вакансии с анкетой."
            );
        }
        return vacancy;
    }

    private void ensureQueueable(Vacancy vacancy, Map<UUID, HhResume> resumes) {
        if (!vacancy.status().isActive()) {
            throw new VacancyTransitionNotAllowedException(
                    "Вакансию %s нельзя поставить в очередь откликов в текущем статусе."
                            .formatted(vacancy.id())
            );
        }
        if (requiresQuestionnaire(vacancy)) {
            throw new VacancyTransitionNotAllowedException(
                    "Вакансию %s нельзя поставить в очередь: для отклика требуется анкета."
                            .formatted(vacancy.id())
            );
        }
        if (!requiresCoverLetter(vacancy)) {
            return;
        }
        HhResume resume = resumes.computeIfAbsent(vacancy.resumeId(), this::findResumeRequired);
        if (!resume.settings().aiLetter().enabled() && resume.settings().coverLetter().isBlank()) {
            throw new VacancyTransitionNotAllowedException(
                    "Вакансию %s нельзя поставить в очередь: настройте генерацию или резервное письмо."
                            .formatted(vacancy.id())
            );
        }
    }

    private boolean requiresQuestionnaire(Vacancy vacancy) {
        if (Boolean.TRUE.equals(vacancy.hasQuestionnaire())) {
            return true;
        }
        if (vacancy.enrichment() == null) {
            return false;
        }
        var value = vacancy.enrichment().get("userTestPresent");
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private boolean requiresCoverLetter(Vacancy vacancy) {
        if (vacancy.enrichment() == null) {
            return false;
        }
        var value = vacancy.enrichment().get("responseLetterRequired");
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private HhResume findResumeRequired(UUID resumeId) {
        return resumeRepository.findById(resumeId)
                .orElseThrow(() -> new VacancyTransitionNotAllowedException(
                        "Настройки резюме для вакансии %s не найдены.".formatted(resumeId)
                ));
    }

    /**
     * Запись выполняется только из того статуса, который был прочитан при проверке перехода:
     * параллельная блокировка работодателя не должна быть затёрта пользовательским выбором.
     */
    private void updateState(Vacancy expected, Vacancy target) {
        if (!vacancyRepository.updateState(
                target.id(),
                EnumSet.of(expected.status()),
                target.status(),
                target.banScope(),
                target.queuedForApplication(),
                target.updatedAt()
        )) {
            throw new VacancyTransitionNotAllowedException(
                    "Состояние вакансии %s изменилось, обновите список и повторите операцию."
                            .formatted(target.id())
            );
        }
    }

    private Vacancy findRequired(UUID vacancyId) {
        return vacancyRepository.findById(vacancyId)
                .orElseThrow(() -> new VacancyNotFoundException(vacancyId));
    }

    private List<Vacancy> findAllRequired(List<UUID> vacancyIds) {
        Map<UUID, Vacancy> found = vacancyRepository.findAllByIds(vacancyIds).stream()
                .collect(Collectors.toMap(Vacancy::id, Function.identity()));
        List<Vacancy> vacancies = new ArrayList<>(vacancyIds.size());
        for (UUID vacancyId : vacancyIds) {
            Vacancy vacancy = found.get(vacancyId);
            if (vacancy == null) {
                throw new VacancyNotFoundException(vacancyId);
            }
            vacancies.add(vacancy);
        }
        return vacancies;
    }

    private VacancyListing listing(UUID vacancyId) {
        return vacancyRepository.findListingById(vacancyId)
                .orElseThrow(() -> new VacancyNotFoundException(vacancyId));
    }

    private List<VacancyListing> listings(List<UUID> vacancyIds) {
        Map<UUID, VacancyListing> found = vacancyRepository.findListingsByIds(vacancyIds).stream()
                .collect(Collectors.toMap(listing -> listing.vacancy().id(), Function.identity()));
        List<VacancyListing> listings = new ArrayList<>(vacancyIds.size());
        for (UUID vacancyId : vacancyIds) {
            VacancyListing listing = found.get(vacancyId);
            if (listing == null) {
                throw new VacancyNotFoundException(vacancyId);
            }
            listings.add(listing);
        }
        return listings;
    }

    private List<UUID> distinctIds(List<UUID> vacancyIds) {
        if (vacancyIds == null || vacancyIds.isEmpty()) {
            throw new InvalidVacancyDataException("Не выбрано ни одной вакансии.");
        }
        if (vacancyIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidVacancyDataException("Список вакансий содержит пустой идентификатор.");
        }
        return List.copyOf(new LinkedHashSet<>(vacancyIds));
    }
}
