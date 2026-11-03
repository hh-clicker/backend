package org.hhclicker.backend.resume.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeAutomationSettings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ResumeRepository {

    private static final String RESUME_COLUMNS = """
            id, account_id, hh_resume_id, name, cover_letter, daily_limit,
            pause_from_seconds, pause_to_seconds, skip_questionnaires,
            created_at, updated_at, plain_text, text_loaded_at, ai_letter_enabled, ai_letter_template, ai_letter_max_length
            """;

    private final JdbcClient jdbcClient;

    public ResumeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static HhResume mapResume(ResultSet resultSet, int rowNumber) throws SQLException {
        return new HhResume(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                resultSet.getString("hh_resume_id"),
                resultSet.getString("name"),
                new ResumeAutomationSettings(
                        resultSet.getString("cover_letter"),
                        resultSet.getInt("daily_limit"),
                        resultSet.getInt("pause_from_seconds"),
                        resultSet.getInt("pause_to_seconds"),
                        resultSet.getBoolean("skip_questionnaires"),
                        new org.hhclicker.backend.resume.model.AiLetterSettings(resultSet.getBoolean("ai_letter_enabled"),
                                resultSet.getString("ai_letter_template"), resultSet.getInt("ai_letter_max_length"))
                ),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("plain_text"),
                resultSet.getObject("text_loaded_at", OffsetDateTime.class) == null ? null
                        : resultSet.getObject("text_loaded_at", OffsetDateTime.class).toInstant()
        );
    }

    private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public List<HhResume> findAll() {
        return jdbcClient.sql("""
                        select %s
                        from hh_resume
                        order by created_at, id
                        """.formatted(RESUME_COLUMNS))
                .query(ResumeRepository::mapResume)
                .list();
    }

    public List<HhResume> findAllByAccountId(UUID accountId) {
        return jdbcClient.sql("""
                        select %s
                        from hh_resume
                        where account_id = :accountId
                        order by created_at, id
                        """.formatted(RESUME_COLUMNS))
                .param("accountId", accountId)
                .query(ResumeRepository::mapResume)
                .list();
    }

    public Optional<HhResume> findById(UUID id) {
        return jdbcClient.sql("""
                        select %s
                        from hh_resume
                        where id = :id
                        """.formatted(RESUME_COLUMNS))
                .param("id", id)
                .query(ResumeRepository::mapResume)
                .optional();
    }

    public Optional<HhResume> findByHhResumeId(String hhResumeId) {
        return jdbcClient.sql("""
                        select %s
                        from hh_resume
                        where hh_resume_id = :hhResumeId
                        """.formatted(RESUME_COLUMNS))
                .param("hhResumeId", hhResumeId)
                .query(ResumeRepository::mapResume)
                .optional();
    }

    public boolean insertIfAbsent(HhResume resume) {
        return jdbcClient.sql("""
                        insert into hh_resume (
                            id, account_id, hh_resume_id, name, cover_letter, daily_limit,
                            pause_from_seconds, pause_to_seconds, skip_questionnaires,
                            created_at, updated_at, plain_text, text_loaded_at
                        ) values (
                            :id, :accountId, :hhResumeId, :name, :coverLetter, :dailyLimit,
                            :pauseFromSeconds, :pauseToSeconds, :skipQuestionnaires,
                            :createdAt, :updatedAt, :plainText, :textLoadedAt
                        )
                        on conflict (hh_resume_id) do nothing
                        """)
                .param("id", resume.id())
                .param("accountId", resume.accountId())
                .param("hhResumeId", resume.hhResumeId())
                .param("name", resume.name())
                .param("coverLetter", resume.settings().coverLetter())
                .param("dailyLimit", resume.settings().dailyLimit())
                .param("pauseFromSeconds", resume.settings().pauseFromSeconds())
                .param("pauseToSeconds", resume.settings().pauseToSeconds())
                .param("skipQuestionnaires", resume.settings().skipQuestionnaires())
                .param("createdAt", toOffsetDateTime(resume.createdAt()))
                .param("updatedAt", toOffsetDateTime(resume.updatedAt()))
                .param("plainText", resume.plainText())
                .param("textLoadedAt", toOffsetDateTime(resume.textLoadedAt()))
                .update() == 1;
    }

    public boolean updateSettings(HhResume resume) {
        return jdbcClient.sql("""
                        update hh_resume
                        set cover_letter = :coverLetter,
                            ai_letter_enabled = :aiEnabled, ai_letter_template = :aiTemplate, ai_letter_max_length = :aiMax,
                            daily_limit = :dailyLimit,
                            pause_from_seconds = :pauseFromSeconds,
                            pause_to_seconds = :pauseToSeconds,
                            skip_questionnaires = :skipQuestionnaires,
                            updated_at = :updatedAt
                        where id = :id
                        """)
                .param("id", resume.id())
                .param("coverLetter", resume.settings().coverLetter())
                .param("dailyLimit", resume.settings().dailyLimit())
                .param("pauseFromSeconds", resume.settings().pauseFromSeconds())
                .param("pauseToSeconds", resume.settings().pauseToSeconds())
                .param("skipQuestionnaires", resume.settings().skipQuestionnaires())
                .param("updatedAt", toOffsetDateTime(resume.updatedAt()))
                .param("aiEnabled", resume.settings().aiLetter().enabled())
                .param("aiTemplate", resume.settings().aiLetter().template())
                .param("aiMax", resume.settings().aiLetter().maxLength())
                .update() == 1;
    }

    public boolean deleteById(UUID id) {
        return jdbcClient.sql("delete from hh_resume where id = :id")
                .param("id", id)
                .update() == 1;
    }
}
