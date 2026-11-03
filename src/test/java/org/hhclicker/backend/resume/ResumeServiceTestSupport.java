package org.hhclicker.backend.resume;

import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.resume.model.AiLetterSettings;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;
import org.hhclicker.backend.resume.model.ResumeStatistics;
import org.hhclicker.backend.resume.repository.ResumeRepository;
import org.hhclicker.backend.resume.service.ResumeService;
import org.hhclicker.backend.resume.service.ResumeStatisticsProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая обвязка unit-тестов {@code ResumeService}.
 * <p>
 * Часы фиксированы, поэтому все проверки временных меток детерминированы. Соседние модули
 * подменяются моками: проверяется решение сервиса, а не чужой SQL.
 */
abstract class ResumeServiceTestSupport {

    protected static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    protected static final Instant EARLIER = Instant.parse("2026-09-01T08:00:00Z");
    protected static final String HASH = "abcdef0123456789abcd";
    protected static final String OTHER_HASH = "0123456789abcdef0123";
    protected static final String PLAIN_TEXT = "Java-разработчик\n\nОпыт: сервисы на Spring";
    protected static final String AI_TEMPLATE = "Здравствуйте!\n#AI_CHANGE#\nСпасибо!";

    protected final ResumeRepository repository = mock(ResumeRepository.class);
    protected final AccountService accounts = mock(AccountService.class);
    protected final ResumeStatisticsProvider statisticsProvider = mock(ResumeStatisticsProvider.class);
    protected final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    protected final ResumeService service = new ResumeService(repository, accounts, statisticsProvider, clock);

    protected final UUID accountId = UUID.randomUUID();
    protected final UUID resumeId = UUID.randomUUID();

    protected final ResumeStatistics statistics = new ResumeStatistics(2, 7, 3, 1, 4);

    /**
     * Аккаунт-владелец и статистика, которые сервис подмешивает в {@code ResumeProfile}.
     */
    protected void stubAccount(String name) {
        stubAccount(accountId, name);
    }

    protected void stubAccount(UUID id, String name) {
        when(accounts.findById(id)).thenReturn(new HhAccount(id, name, null, AuthorizationStatus.AUTHORIZED, EARLIER, EARLIER));
    }

    protected void stubStatistics() {
        when(statisticsProvider.get(any())).thenReturn(statistics);
    }

    protected HhResume resume() {
        return resume(resumeId, accountId, HASH, "Java-разработчик", ResumeAutomationSettings.defaults());
    }

    protected HhResume resume(UUID id, UUID owner, String hash, String name, ResumeAutomationSettings settings) {
        return new HhResume(id, owner, hash, name, settings, EARLIER, EARLIER, PLAIN_TEXT, EARLIER);
    }

    protected ResumeAutomationSettings settings(String coverLetter, int dailyLimit, int pauseFrom, int pauseTo,
                                                boolean skipQuestionnaires, AiLetterSettings ai) {
        return new ResumeAutomationSettings(coverLetter, dailyLimit, pauseFrom, pauseTo, skipQuestionnaires, ai);
    }
}
