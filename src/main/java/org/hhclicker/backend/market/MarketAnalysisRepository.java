package org.hhclicker.backend.market;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MarketAnalysisRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MarketAnalysisRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record Source(UUID id, String hhId, String title, String employerKey, String company,
                         String text, Instant attemptedAt) { }
    public record Fact(UUID vacancyId, String name, String kind, String priority, String evidence) { }
    public record Coverage(UUID requirementId, String status, String evidence) { }
    public record Diagnostic(String phase, UUID itemId, String reason, String name, String evidence) { }
    public record NotificationRun(UUID id, UUID accountId, UUID resumeId, String resumeName,
                                  String status, String phase, String revision, Instant updatedAt,
                                  int processed, int total, String error) { }
    public record Run(UUID id, UUID resumeId, String resumeName, String resumeText, Instant createdAt,
                      String status, String phase, int position, UUID jobId, List<Source> sources,
                      List<Fact> facts, List<Coverage> coverage, List<Diagnostic> diagnostics,
                      String markdown, String error, Instant estimateStartedAt, int estimateStartPosition,
                      UUID searchId, String searchName) {
        public Run(UUID id, UUID resumeId, String resumeName, String resumeText, Instant createdAt,
                   String status, String phase, int position, UUID jobId, List<Source> sources,
                   List<Fact> facts, List<Coverage> coverage, List<Diagnostic> diagnostics,
                   String markdown, String error, Instant estimateStartedAt, int estimateStartPosition) {
            this(id, resumeId, resumeName, resumeText, createdAt, status, phase, position, jobId,
                    sources, facts, coverage, diagnostics, markdown, error, estimateStartedAt,
                    estimateStartPosition, null, null);
        }
        public Run(UUID id, UUID resumeId, String resumeName, String resumeText, Instant createdAt,
                   String status, String phase, int position, UUID jobId, List<Source> sources,
                   List<Fact> facts, List<Coverage> coverage, List<Diagnostic> diagnostics,
                   String markdown, String error) {
            this(id, resumeId, resumeName, resumeText, createdAt, status, phase, position, jobId,
                    sources, facts, coverage, diagnostics, markdown, error, null, 0);
        }

        public Run(UUID id, UUID resumeId, String resumeName, String resumeText, Instant createdAt,
                   String status, String phase, int position, UUID jobId, List<Source> sources,
                   List<Fact> facts, List<Coverage> coverage, String markdown, String error) {
            this(id, resumeId, resumeName, resumeText, createdAt, status, phase, position, jobId,
                    sources, facts, coverage, List.of(), markdown, error);
        }
    }

    public List<Source> sources(UUID resumeId) { return sources(resumeId, null); }

    public List<Source> sources(UUID resumeId, UUID searchId) {
        return sources(resumeId, searchId, null);
    }

    public List<Source> sources(UUID resumeId, UUID searchId, MarketRelevanceCategory relevanceCategory) {
        String filter = searchId == null ? "" : "and exists (select 1 from vacancy_search_link link where link.vacancy_id = v.id and link.search_id = :searchId)";
        String categoryFilter = relevanceCategory == null ? "" : "and v.relevance_category = :relevanceCategory";
        var statement = jdbc.sql("""
                select v.id, v.hh_vacancy_id, v.title, v.employer_id, v.company_name, v.plain_text,
                       coalesce(v.text_loaded_at, v.updated_at) as attempted_at
                from vacancy v
                where v.resume_id = :resumeId
                  and v.plain_text is not null and length(trim(v.plain_text)) > 0
                  and v.status <> 'CLOSED'
                %s
                %s
                order by coalesce(v.text_loaded_at, v.updated_at), v.id
                """.formatted(filter, categoryFilter)).param("resumeId", resumeId);
        if (searchId != null) statement = statement.param("searchId", searchId);
        if (relevanceCategory != null) statement = statement.param("relevanceCategory", relevanceCategory.name());
        return statement.query((rs, row) -> new Source(
                rs.getObject("id", UUID.class), rs.getString("hh_vacancy_id"), rs.getString("title"),
                rs.getString("employer_id") == null || rs.getString("employer_id").isBlank()
                        ? "name:" + rs.getString("company_name").strip().toLowerCase(java.util.Locale.ROOT)
                        : "id:" + rs.getString("employer_id"),
                rs.getString("company_name"), rs.getString("plain_text"),
                rs.getTimestamp("attempted_at") == null ? null : rs.getTimestamp("attempted_at").toInstant())).list();
    }

    public Optional<String> searchName(UUID resumeId, UUID searchId) {
        return jdbc.sql("select name from vacancy_search where id = :searchId and resume_id = :resumeId")
                .param("searchId", searchId).param("resumeId", resumeId).query(String.class).optional();
    }

    public void insert(Run run) {
        jdbc.sql("""
                insert into market_analysis_run
                  (id, resume_id, resume_name, resume_text, status, phase, sources_json, search_id, search_name)
                values (:id, :resumeId, :resumeName, :resumeText, 'RUNNING', 'EXTRACT', cast(:sources as jsonb), :searchId, :searchName)
                """).param("id", run.id()).param("resumeId", run.resumeId())
                .param("resumeName", run.resumeName()).param("resumeText", run.resumeText())
                .param("sources", json.writeValueAsString(run.sources()))
                .param("searchId", run.searchId()).param("searchName", run.searchName()).update();
    }

    public void save(Run run) {
        jdbc.sql("""
                update market_analysis_run set updated_at = now(), status = :status, phase = :phase,
                  position = :position, job_id = :jobId, facts_json = cast(:facts as jsonb),
                  coverage_json = cast(:coverage as jsonb), diagnostics_json = cast(:diagnostics as jsonb),
                  report_markdown = :markdown, error_message = :error,
                  estimate_started_at = :estimateStartedAt, estimate_start_position = :estimateStartPosition
                where id = :id
                """).param("id", run.id()).param("status", run.status()).param("phase", run.phase())
                .param("position", run.position()).param("jobId", run.jobId())
                .param("facts", json.writeValueAsString(run.facts()))
                .param("coverage", json.writeValueAsString(run.coverage()))
                .param("diagnostics", json.writeValueAsString(run.diagnostics()))
                .param("markdown", run.markdown()).param("error", run.error())
                .param("estimateStartedAt", run.estimateStartedAt() == null ? null
                        : OffsetDateTime.ofInstant(run.estimateStartedAt(), ZoneOffset.UTC))
                .param("estimateStartPosition", run.estimateStartPosition()).update();
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql("select *, sources_json::text as sources_text, facts_json::text as facts_text, coverage_json::text as coverage_text, diagnostics_json::text as diagnostics_text from market_analysis_run where id = :id")
                .param("id", id).query(this::map).optional();
    }

    public void lock(UUID id) {
        jdbc.sql("select id from market_analysis_run where id = :id for update")
                .param("id", id).query(UUID.class).single();
    }

    public boolean acceptsBatchResults(UUID id) {
        return jdbc.sql("select exists(select 1 from market_analysis_run where id = :id and status in ('RUNNING', 'STOPPING'))")
                .param("id", id).query(Boolean.class).single();
    }

    public List<Run> list(UUID resumeId) {
        return jdbc.sql("select *, sources_json::text as sources_text, facts_json::text as facts_text, coverage_json::text as coverage_text, diagnostics_json::text as diagnostics_text from market_analysis_run where resume_id = :resumeId order by created_at desc, id desc")
                .param("resumeId", resumeId).query(this::map).list();
    }

    public List<Run> active() {
        return jdbc.sql("select *, sources_json::text as sources_text, facts_json::text as facts_text, coverage_json::text as coverage_text, diagnostics_json::text as diagnostics_text from market_analysis_run where status in ('RUNNING', 'STOPPING') order by created_at")
                .query(this::map).list();
    }

    public boolean hasOpen(UUID resumeId, UUID exceptId) {
        String query = "select exists(select 1 from market_analysis_run where resume_id = :resumeId "
                + "and status in ('RUNNING', 'STOPPING', 'PAUSED')"
                + (exceptId == null ? ")" : " and id <> :exceptId)");
        var statement = jdbc.sql(query).param("resumeId", resumeId);
        if (exceptId != null) statement = statement.param("exceptId", exceptId);
        return statement.query(Boolean.class).single();
    }

    public List<NotificationRun> notificationRuns() {
        return jdbc.sql("""
                select m.id, r.account_id, m.resume_id, m.resume_name, m.status, m.phase,
                  m.updated_at::text as revision, m.updated_at, m.position,
                  jsonb_array_length(m.sources_json) as total, m.error_message
                from market_analysis_run m join hh_resume r on r.id = m.resume_id
                order by m.created_at desc, m.id desc limit 100
                """).query((rs, row) -> new NotificationRun(rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("resume_id", UUID.class),
                rs.getString("resume_name"), rs.getString("status"), rs.getString("phase"), rs.getString("revision"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("position"), rs.getInt("total"), rs.getString("error_message"))).list();
    }

    private Run map(ResultSet rs, int row) throws SQLException {
        return new Run(rs.getObject("id", UUID.class), rs.getObject("resume_id", UUID.class),
                rs.getString("resume_name"), rs.getString("resume_text"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("status"), rs.getString("phase"), rs.getInt("position"), rs.getObject("job_id", UUID.class),
                json.readValue(rs.getString("sources_text"), new TypeReference<List<Source>>() {}),
                json.readValue(rs.getString("facts_text"), new TypeReference<List<Fact>>() {}),
                json.readValue(rs.getString("coverage_text"), new TypeReference<List<Coverage>>() {}),
                json.readValue(rs.getString("diagnostics_text"), new TypeReference<List<Diagnostic>>() {}),
                rs.getString("report_markdown"), rs.getString("error_message"),
                rs.getTimestamp("estimate_started_at") == null ? null : rs.getTimestamp("estimate_started_at").toInstant(),
                rs.getInt("estimate_start_position"), rs.getObject("search_id", UUID.class), rs.getString("search_name"));
    }
}
