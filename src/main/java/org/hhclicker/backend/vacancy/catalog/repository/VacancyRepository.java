package org.hhclicker.backend.vacancy.catalog.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.vacancy.catalog.model.*;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VacancyRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VACANCY_COLUMNS = """
            v.id, v.resume_id, v.hh_vacancy_id, v.title, v.employer_id, v.company_name,
            v.salary_from, v.salary_to, v.salary_currency, v.salary_gross,
            v.location, v.work_format, v.experience, v.published_at, v.salary_text,
            v.has_questionnaire, v.status, v.ban_scope, v.queued_for_application,
            v.first_seen_at, v.last_seen_at, v.created_at, v.updated_at,
            v.relevance_category, v.relevance_confidence, v.relevance_reason, v.relevance_analyzed_at,
            v.enrichment, coalesce(char_length(v.plain_text), 0) as text_length,
            (
                select array_agg(s.name order by s.position)
                from vacancy_label s
                where s.vacancy_id = v.id
            ) as labels,
            exists (
                select 1 from application_item i
                join hh_resume r on r.id = v.resume_id and r.account_id = i.account_id
                where i.hh_vacancy_id = v.hh_vacancy_id
                  and i.status = 'ATTENTION_REQUIRED' and i.outcome = 'UNKNOWN'
                  and not exists (
                      select 1 from application_item confirmed
                      where confirmed.account_id = i.account_id and confirmed.hh_vacancy_id = i.hh_vacancy_id
                        and confirmed.outcome in ('SENT', 'SENT_LETTER_UNCONFIRMED',
                          'SENT_LETTER_REJECTED_ALREADY_VIEWED', 'ALREADY_APPLIED')
                  )
            ) as has_unconfirmed_application
            """;

    private static final String FIRST_SEARCH_LINK = """
            left join lateral (
                select l.search_id
                from vacancy_search_link l
                where l.vacancy_id = v.id
                order by l.created_at, l.id
                limit 1
            ) first_link on true
            """;

    private final JdbcClient jdbcClient;

    public VacancyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static UUID mapId(ResultSet resultSet, int rowNumber) throws SQLException {
        return resultSet.getObject("id", UUID.class);
    }

    private VacancyListing mapListing(ResultSet resultSet, int rowNumber) throws SQLException {
        return new VacancyListing(
                mapVacancy(resultSet, rowNumber),
                resultSet.getObject("listing_search_id", UUID.class),
                resultSet.getBoolean("has_unconfirmed_application")
        );
    }

    private Vacancy mapVacancy(ResultSet resultSet, int rowNumber) throws SQLException {
        String banScope = resultSet.getString("ban_scope");
        String relevanceCategory = resultSet.getString("relevance_category");
        return new Vacancy(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("resume_id", UUID.class),
                resultSet.getString("hh_vacancy_id"),
                resultSet.getString("title"),
                resultSet.getString("employer_id"),
                resultSet.getString("company_name"),
                mapSalary(resultSet),
                resultSet.getString("location"),
                resultSet.getString("work_format"),
                resultSet.getString("experience"),
                toInstant(resultSet, "published_at"),
                resultSet.getString("salary_text"),
                mapLabels(resultSet),
                resultSet.getObject("has_questionnaire", Boolean.class),
                VacancyStatus.valueOf(resultSet.getString("status")),
                banScope == null ? null : CompanyBanScope.valueOf(banScope),
                resultSet.getBoolean("queued_for_application"),
                resultSet.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                relevanceCategory == null ? null : new VacancyRelevance(
                        VacancyRelevanceCategory.valueOf(relevanceCategory),
                        VacancyRelevanceConfidence.valueOf(resultSet.getString("relevance_confidence")),
                        resultSet.getString("relevance_reason"),
                        toInstant(resultSet, "relevance_analyzed_at")
                ),
                mapEnrichment(resultSet),
                resultSet.getInt("text_length")
        );
    }

    private JsonNode mapEnrichment(ResultSet resultSet) throws SQLException {
        String value = resultSet.getString("enrichment");
        if (value == null) return null;
        try {
            return JSON.readTree(value);
        } catch (JacksonException exception) {
            throw new SQLException("Stored vacancy enrichment is not valid JSON", exception);
        }
    }

    private String serializeEnrichment(JsonNode enrichment) {
        return enrichment == null ? null : enrichment.toString();
    }

    private static VacancySalary mapSalary(ResultSet resultSet) throws SQLException {
        String currency = resultSet.getString("salary_currency");
        if (currency == null) {
            return null;
        }
        Long from = resultSet.getObject("salary_from", Long.class);
        Long to = resultSet.getObject("salary_to", Long.class);
        return new VacancySalary(from, to, SalaryCurrency.valueOf(currency), resultSet.getBoolean("salary_gross"));
    }

    private static List<String> mapLabels(ResultSet resultSet) throws SQLException {
        Array labels = resultSet.getArray("labels");
        if (labels == null) {
            return List.of();
        }
        return List.of((String[]) labels.getArray());
    }

    private static Instant toInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public Optional<org.hhclicker.backend.vacancy.catalog.model.VacancyApplicationSource> applicationSource(UUID id) {
        return jdbcClient.sql("""
                select s.source_url, l.position / 100 as page from vacancy_search_link l
                join vacancy_search s on s.id = l.search_id where l.vacancy_id = :id
                order by l.updated_at desc, l.id limit 1
                """).param("id", id).query((rs, row) ->
                new org.hhclicker.backend.vacancy.catalog.model.VacancyApplicationSource(rs.getString("source_url"), rs.getInt("page"))).optional();
    }

    public void markApplied(UUID id) {
        jdbcClient.sql("update vacancy set status = 'ALREADY_APPLIED', ban_scope = null, queued_for_application = false, updated_at = now() where id = :id")
                .param("id", id).update();
    }

    public void markClosed(UUID id, Instant updatedAt) {
        jdbcClient.sql("""
                update vacancy set status = 'CLOSED', ban_scope = null,
                  queued_for_application = false, updated_at = :updatedAt
                where id = :id and status in ('NEW', 'USER_SKIPPED', 'COMPANY_BANNED')
                """).param("id", id).param("updatedAt", toOffsetDateTime(updatedAt)).update();
    }

    public Optional<VacancyText> findText(UUID id) {
        return jdbcClient.sql("select plain_text, text_loaded_at from vacancy where id = :id")
                .param("id", id).query((rs, row) -> new VacancyText(rs.getString("plain_text"), toInstant(rs, "text_loaded_at"))).optional();
    }

    public void saveText(UUID id, String plainText) {
        // Search scans update only listing metadata; they never erase this detail snapshot.
        jdbcClient.sql("update vacancy set plain_text = :text, text_loaded_at = now() where id = :id")
                .param("id", id).param("text", plainText).update();
    }

    public boolean saveRelevance(UUID id, VacancyRelevance relevance) {
        return jdbcClient.sql("""
                        update vacancy
                        set relevance_category = :category,
                            relevance_confidence = :confidence,
                            relevance_reason = :reason,
                            relevance_analyzed_at = :analyzedAt
                        where id = :id
                        """)
                .param("id", id)
                .param("category", relevance.category().name())
                .param("confidence", relevance.confidence().name())
                .param("reason", relevance.reason())
                .param("analyzedAt", toOffsetDateTime(relevance.analyzedAt()))
                .update() == 1;
    }

    public void markQuestionnaire(UUID id) {
        jdbcClient.sql("update vacancy set has_questionnaire = true, updated_at = now() where id = :id")
                .param("id", id).update();
    }

    public List<VacancyListing> findAllListings() {
        return jdbcClient.sql("""
                        select %s, first_link.search_id as listing_search_id
                        from vacancy v
                        %s
                        order by v.first_seen_at desc, v.id
                        """.formatted(VACANCY_COLUMNS, FIRST_SEARCH_LINK))
                .query(this::mapListing)
                .list();
    }

    public List<VacancyListing> findListingsByResumeId(UUID resumeId) {
        return jdbcClient.sql("""
                        select %s, first_link.search_id as listing_search_id
                        from vacancy v
                        %s
                        where v.resume_id = :resumeId
                        order by v.first_seen_at desc, v.id
                        """.formatted(VACANCY_COLUMNS, FIRST_SEARCH_LINK))
                .param("resumeId", resumeId)
                .query(this::mapListing)
                .list();
    }

    public List<VacancyListing> findListingsBySearchId(UUID searchId) {
        return jdbcClient.sql("""
                        select %s, link.search_id as listing_search_id
                        from vacancy v
                        join vacancy_search_link link
                          on link.vacancy_id = v.id and link.search_id = :searchId
                        order by link.updated_at desc, link.position, v.first_seen_at desc, v.id
                        """.formatted(VACANCY_COLUMNS))
                .param("searchId", searchId)
                .query(this::mapListing)
                .list();
    }

    public Optional<VacancyListing> findListingById(UUID id) {
        return jdbcClient.sql("""
                        select %s, first_link.search_id as listing_search_id
                        from vacancy v
                        %s
                        where v.id = :id
                        """.formatted(VACANCY_COLUMNS, FIRST_SEARCH_LINK))
                .param("id", id)
                .query(this::mapListing)
                .optional();
    }

    public List<VacancyListing> findListingsByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select %s, first_link.search_id as listing_search_id
                        from vacancy v
                        %s
                        where v.id in (:ids)
                        """.formatted(VACANCY_COLUMNS, FIRST_SEARCH_LINK))
                .param("ids", ids)
                .query(this::mapListing)
                .list();
    }

    public Optional<Vacancy> findById(UUID id) {
        return jdbcClient.sql("""
                        select %s
                        from vacancy v
                        where v.id = :id
                        """.formatted(VACANCY_COLUMNS))
                .param("id", id)
                .query(this::mapVacancy)
                .optional();
    }

    public List<Vacancy> findAllByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select %s
                        from vacancy v
                        where v.id in (:ids)
                        """.formatted(VACANCY_COLUMNS))
                .param("ids", ids)
                .query(this::mapVacancy)
                .list();
    }

    public List<String> findEmployerIdsByResumeIdAndHhVacancyIds(UUID resumeId, Collection<String> hhVacancyIds) {
        if (hhVacancyIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                select distinct employer_id from vacancy
                where resume_id = :resumeId and hh_vacancy_id in (:ids) and employer_id is not null
                """).param("resumeId", resumeId).param("ids", hhVacancyIds).query(String.class).list();
    }

    public List<Vacancy> findAllByResumeIdAndHhVacancyIds(UUID resumeId, Collection<String> hhVacancyIds) {
        if (hhVacancyIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select %s
                        from vacancy v
                        where v.resume_id = :resumeId and v.hh_vacancy_id in (:hhVacancyIds)
                        """.formatted(VACANCY_COLUMNS))
                .param("resumeId", resumeId)
                .param("hhVacancyIds", hhVacancyIds)
                .query(this::mapVacancy)
                .list();
    }

    public VacancyCounts getCountsByResumeId(UUID resumeId) {
        return jdbcClient.sql("""
                        select count(*) filter (where status = 'NEW') as new_vacancies,
                               count(*) filter (where queued_for_application) as queued_vacancies
                        from vacancy
                        where resume_id = :resumeId
                        """)
                .param("resumeId", resumeId)
                .query((resultSet, rowNumber) -> new VacancyCounts(
                        resultSet.getLong("new_vacancies"),
                        resultSet.getLong("queued_vacancies")
                ))
                .single();
    }

    public Optional<String> findLatestCompanyName(String employerId) {
        return jdbcClient.sql("""
                        select company_name
                        from vacancy
                        where employer_id = :employerId
                        order by last_seen_at desc, id
                        limit 1
                        """)
                .param("employerId", employerId)
                .query(String.class)
                .optional();
    }

    /**
     * Количество скрытых блокировкой вакансий в разрезе работодателя и резюме.
     * Для глобальной блокировки строки одного работодателя суммируются.
     */
    public List<EmployerBannedCount> countBannedVacanciesByEmployerAndResume(Collection<String> employerIds) {
        if (employerIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select employer_id, resume_id, count(*) as banned_count
                        from vacancy
                        where status = 'COMPANY_BANNED' and employer_id in (:employerIds)
                        group by employer_id, resume_id
                        """)
                .param("employerIds", employerIds)
                .query((resultSet, rowNumber) -> new EmployerBannedCount(
                        resultSet.getString("employer_id"),
                        resultSet.getObject("resume_id", UUID.class),
                        resultSet.getLong("banned_count")
                ))
                .list();
    }

    /**
     * Вставляет вакансию, если пары (резюме, вакансия HH) ещё нет. Пустой результат означает,
     * что строку успела создать параллельная транзакция и вакансию нужно обновить.
     */
    public Optional<UUID> insertIfAbsent(Vacancy vacancy) {
        return jdbcClient.sql("""
                        insert into vacancy (
                            id, resume_id, hh_vacancy_id, title, employer_id, company_name,
                            salary_from, salary_to, salary_currency, salary_gross,
                            location, work_format, experience, published_at, salary_text,
                            has_questionnaire, status, ban_scope, queued_for_application,
                            first_seen_at, last_seen_at, created_at, updated_at, enrichment
                        ) values (
                            :id, :resumeId, :hhVacancyId, :title, :employerId, :companyName,
                            :salaryFrom, :salaryTo, :salaryCurrency, :salaryGross,
                            :location, :workFormat, :experience, :publishedAt, :salaryText,
                            :hasQuestionnaire, :status, :banScope, :queuedForApplication,
                            :firstSeenAt, :lastSeenAt, :createdAt, :updatedAt, cast(:enrichment as jsonb)
                        )
                        on conflict (resume_id, hh_vacancy_id) do nothing
                        returning id
                        """)
                .param("id", vacancy.id())
                .param("resumeId", vacancy.resumeId())
                .param("hhVacancyId", vacancy.hhVacancyId())
                .param("title", vacancy.title())
                .param("employerId", vacancy.employerId())
                .param("companyName", vacancy.companyName())
                .param("salaryFrom", vacancy.salary() == null ? null : vacancy.salary().from())
                .param("salaryTo", vacancy.salary() == null ? null : vacancy.salary().to())
                .param("salaryCurrency", vacancy.salary() == null ? null : vacancy.salary().currency().name())
                .param("salaryGross", vacancy.salary() == null ? null : Boolean.valueOf(vacancy.salary().gross()))
                .param("location", vacancy.location())
                .param("workFormat", vacancy.workFormat())
                .param("experience", vacancy.experience())
                .param("publishedAt", toOffsetDateTime(vacancy.publishedAt()))
                .param("salaryText", vacancy.salaryText())
                .param("hasQuestionnaire", vacancy.hasQuestionnaire())
                .param("status", vacancy.status().name())
                .param("banScope", vacancy.banScope() == null ? null : vacancy.banScope().name())
                .param("queuedForApplication", vacancy.queuedForApplication())
                .param("firstSeenAt", toOffsetDateTime(vacancy.firstSeenAt()))
                .param("lastSeenAt", toOffsetDateTime(vacancy.lastSeenAt()))
                .param("createdAt", toOffsetDateTime(vacancy.createdAt()))
                .param("updatedAt", toOffsetDateTime(vacancy.updatedAt()))
                .param("enrichment", serializeEnrichment(vacancy.enrichment()))
                .query(VacancyRepository::mapId)
                .optional();
    }

    /**
     * Обновляет только внешние данные HH. Пользовательский выбор, пропуск и очередь не затрагиваются,
     * метки перезаписываются отдельно через {@link #replaceLabels(UUID, List)}.
     */
    public boolean updateExternalData(Vacancy vacancy) {
        return jdbcClient.sql("""
                        update vacancy
                        set title = :title,
                            employer_id = :employerId,
                            company_name = :companyName,
                            salary_from = :salaryFrom,
                            salary_to = :salaryTo,
                            salary_currency = :salaryCurrency,
                            salary_gross = :salaryGross,
                            location = :location,
                            work_format = :workFormat,
                            experience = :experience,
                            published_at = :publishedAt,
                            salary_text = :salaryText,
                            has_questionnaire = :hasQuestionnaire,
                            enrichment = cast(:enrichment as jsonb),
                            last_seen_at = :lastSeenAt,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", vacancy.id())
                .param("title", vacancy.title())
                .param("employerId", vacancy.employerId())
                .param("companyName", vacancy.companyName())
                .param("salaryFrom", vacancy.salary() == null ? null : vacancy.salary().from())
                .param("salaryTo", vacancy.salary() == null ? null : vacancy.salary().to())
                .param("salaryCurrency", vacancy.salary() == null ? null : vacancy.salary().currency().name())
                .param("salaryGross", vacancy.salary() == null ? null : Boolean.valueOf(vacancy.salary().gross()))
                .param("location", vacancy.location())
                .param("workFormat", vacancy.workFormat())
                .param("experience", vacancy.experience())
                .param("publishedAt", toOffsetDateTime(vacancy.publishedAt()))
                .param("salaryText", vacancy.salaryText())
                .param("hasQuestionnaire", vacancy.hasQuestionnaire())
                .param("enrichment", serializeEnrichment(vacancy.enrichment()))
                .param("lastSeenAt", toOffsetDateTime(vacancy.lastSeenAt()))
                .param("updatedAt", toOffsetDateTime(vacancy.updatedAt()))
                .update() == 1;
    }

    /**
     * Меняет состояние вакансии только из ожидаемых статусов. Ноль изменённых строк означает,
     * что состояние успела поменять параллельная операция, и решение нужно принимать заново.
     */
    public boolean updateState(
            UUID vacancyId,
            Collection<VacancyStatus> allowedCurrentStatuses,
            VacancyStatus status,
            CompanyBanScope banScope,
            boolean queuedForApplication,
            Instant updatedAt
    ) {
        if (allowedCurrentStatuses.isEmpty()) {
            return false;
        }
        return jdbcClient.sql("""
                        update vacancy
                        set status = :status,
                            ban_scope = :banScope,
                            queued_for_application = :queuedForApplication,
                            updated_at = :updatedAt
                        where id = :id and status in (:allowedCurrentStatuses)
                        """)
                .param("id", vacancyId)
                .param("allowedCurrentStatuses", allowedCurrentStatuses.stream().map(Enum::name).toList())
                .param("status", status.name())
                .param("banScope", banScope == null ? null : banScope.name())
                .param("queuedForApplication", queuedForApplication)
                .param("updatedAt", toOffsetDateTime(updatedAt))
                .update() == 1;
    }

    /**
     * Переводит активные вакансии работодателя в {@code COMPANY_BANNED} и приводит область к
     * применимой. {@code resumeIds == null} означает глобальную блокировку — все резюме.
     */
    public List<UUID> banVacanciesOfEmployer(
            String employerId,
            CompanyBanScope scope,
            Collection<UUID> resumeIds,
            Instant updatedAt
    ) {
        if (resumeIds != null && resumeIds.isEmpty()) {
            return List.of();
        }
        String resumeFilter = resumeIds == null ? "" : "and resume_id in (:resumeIds)";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        update vacancy
                        set status = 'COMPANY_BANNED',
                            ban_scope = :scope,
                            queued_for_application = false,
                            updated_at = :updatedAt
                        where employer_id = :employerId
                          %s
                          and (
                              status in ('NEW', 'USER_SKIPPED')
                              or (status = 'COMPANY_BANNED' and ban_scope is distinct from :scope)
                          )
                        returning id
                        """.formatted(resumeFilter))
                .param("employerId", employerId)
                .param("scope", scope.name())
                .param("updatedAt", toOffsetDateTime(updatedAt));
        if (resumeIds != null) {
            statement = statement.param("resumeIds", resumeIds);
        }
        return statement.query(VacancyRepository::mapId).list();
    }

    /**
     * Снимает блокировку с вакансий работодателя, к которым больше не применим ни один ban.
     * Вакансии возвращаются в {@code NEW} без автоматической постановки в очередь.
     */
    public List<UUID> releaseVacanciesOfEmployer(
            String employerId,
            Collection<UUID> stillBannedResumeIds,
            Instant updatedAt
    ) {
        String resumeFilter = stillBannedResumeIds.isEmpty() ? "" : "and resume_id not in (:resumeIds)";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        update vacancy
                        set status = 'NEW',
                            ban_scope = null,
                            queued_for_application = false,
                            updated_at = :updatedAt
                        where employer_id = :employerId
                          and status = 'COMPANY_BANNED'
                          %s
                        returning id
                        """.formatted(resumeFilter))
                .param("employerId", employerId)
                .param("updatedAt", toOffsetDateTime(updatedAt));
        if (!stillBannedResumeIds.isEmpty()) {
            statement = statement.param("resumeIds", stillBannedResumeIds);
        }
        return statement.query(VacancyRepository::mapId).list();
    }

    /**
     * Метки нормализованы прикладным сервисом: пробельные символы схлопнуты, поэтому перевод
     * строки безопасно используется как разделитель списка.
     */
    public void replaceLabels(UUID vacancyId, List<String> labels) {
        jdbcClient.sql("delete from vacancy_label where vacancy_id = :vacancyId")
                .param("vacancyId", vacancyId)
                .update();
        if (labels.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                        insert into vacancy_label (vacancy_id, position, name)
                        select :vacancyId, label.ordinality - 1, label.name
                        from unnest(string_to_array(:labels, chr(10)))
                             with ordinality as label(name, ordinality)
                        """)
                .param("vacancyId", vacancyId)
                .param("labels", String.join("\n", labels))
                .update();
    }
}
