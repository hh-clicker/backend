package org.hhclicker.backend.overview.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.resume.service.ResumeProfile;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.search.model.SearchStatus;
import org.hhclicker.backend.search.service.SearchProfile;
import org.hhclicker.backend.search.service.SearchService;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class OverviewService {

    private final AccountService accountService;
    private final ResumeService resumeService;
    private final SearchService searchService;
    private final org.hhclicker.backend.application.service.ApplicationService applications;

    public OverviewService(AccountService accountService, ResumeService resumeService, SearchService searchService,
                           org.hhclicker.backend.application.service.ApplicationService applications) {
        this.accountService = accountService;
        this.resumeService = resumeService;
        this.searchService = searchService;
        this.applications = applications;
    }

    public OverviewData load(UUID requestedAccountId, UUID requestedResumeId) {
        List<HhAccount> accounts = accountService.findAll();
        HhAccount selectedAccount = selectAccount(accounts, requestedAccountId);
        List<ResumeProfile> resumes = selectedAccount == null
                ? List.of()
                : resumeService.findAll(selectedAccount.id());
        ResumeProfile selectedResume = selectResume(resumes, requestedResumeId);
        long dailyLimit = selectedResume == null ? 0 : selectedResume.resume().settings().dailyLimit();
        var runs = selectedResume == null ? List.<org.hhclicker.backend.application.model.ApplicationModels.Run>of()
                : applications.list(selectedResume.resume().id());
        var attention = selectedResume == null ? List.<org.hhclicker.backend.application.model.ApplicationModels.Attention>of()
                : applications.attention(selectedResume.resume().id()).stream().filter(task -> task.status().equals("OPEN")).toList();
        List<SearchProfile> searches = selectedResume == null
                ? List.of()
                : searchService.findAll(selectedResume.resume().id());
        long newVacancies = selectedResume == null ? 0 : selectedResume.statistics().newVacancies();
        long queuedVacancies = selectedResume == null ? 0 : selectedResume.statistics().queuedVacancies();
        List<OverviewRecentSearch> recentSearches = searches.stream()
                .limit(3)
                .map(profile -> new OverviewRecentSearch(
                        profile.search().id(),
                        profile.search().name(),
                        profile.search().createdAt(),
                        profile.search().newVacancies(),
                        profile.search().status() == SearchStatus.PAUSED ? "PAUSED" : "ACTIVE"
                ))
                .toList();

        return new OverviewData(
                accounts,
                selectedAccount,
                resumes,
                selectedResume,
                List.of(
                        new OverviewMetric(
                                "new",
                                "Новые вакансии",
                                newVacancies,
                                searches.isEmpty() ? "Нет сохранённых поисков" : "%d сохранённых поисков".formatted(searches.size()),
                                "purple"
                        ),
                        new OverviewMetric("selected", "Выбраны к отклику", queuedVacancies, "Текущая очередь", "blue"),
                        new OverviewMetric("succeeded", "Успешно сегодня", selectedResume == null ? 0 : applications.succeededToday(selectedResume.resume().id()), "По выбранному резюме", "green"),
                        new OverviewMetric("attention", "Требуют внимания", attention.size(), "Ожидают проверки", "orange"),
                        new OverviewMetric("limit", "Остаток лимита", selectedResume == null ? 0 : applications.remaining(selectedResume.resume().id()), "из %d в день; попытки всего аккаунта".formatted(dailyLimit), "blue")
                ),
                recentSearches, runs, attention
        );
    }

    private HhAccount selectAccount(List<HhAccount> accounts, UUID requestedAccountId) {
        if (requestedAccountId != null) {
            HhAccount requested = accounts.stream()
                    .filter(account -> account.id().equals(requestedAccountId))
                    .findFirst()
                    .orElse(null);
            if (requested != null) {
                return requested;
            }
        }
        return accounts.isEmpty() ? null : accounts.getFirst();
    }

    private ResumeProfile selectResume(List<ResumeProfile> resumes, UUID requestedResumeId) {
        if (requestedResumeId != null) {
            ResumeProfile requested = resumes.stream()
                    .filter(profile -> profile.resume().id().equals(requestedResumeId))
                    .findFirst()
                    .orElse(null);
            if (requested != null) {
                return requested;
            }
        }
        return resumes.isEmpty() ? null : resumes.getFirst();
    }
}
