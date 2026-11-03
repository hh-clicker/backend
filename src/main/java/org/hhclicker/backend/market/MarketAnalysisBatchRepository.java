package org.hhclicker.backend.market;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class MarketAnalysisBatchRepository {
    private final JdbcClient jdbc;
    public MarketAnalysisBatchRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Batch(UUID runId, int index, int start, int size, String status, int attempt,
                        UUID jobId, String errorCode, String errorMessage) {
        public String key() { return "market-" + runId + "-extract-" + index + "-attempt-" + attempt; }
        public boolean active() { return "SUBMITTING".equals(status) || "SUBMITTED".equals(status); }
    }

    public boolean exists(UUID id) {
        return jdbc.sql("select exists(select 1 from market_analysis_batch where run_id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    public List<Batch> list(UUID id) {
        return jdbc.sql("select * from market_analysis_batch where run_id = :id order by batch_index")
                .param("id", id).query((rs, row) -> new Batch(rs.getObject("run_id", UUID.class),
                        rs.getInt("batch_index"), rs.getInt("start_position"), rs.getInt("batch_size"),
                        rs.getString("status"), rs.getInt("attempt"), rs.getObject("job_id", UUID.class),
                        rs.getString("error_code"), rs.getString("error_message"))).list();
    }

    public void insert(Batch batch) {
        jdbc.sql("""
                insert into market_analysis_batch(run_id, batch_index, start_position, batch_size, status)
                values (:id, :index, :start, :size, 'READY')
                """).param("id", batch.runId()).param("index", batch.index())
                .param("start", batch.start()).param("size", batch.size()).update();
    }

    public void save(Batch batch) {
        jdbc.sql("""
                update market_analysis_batch set status = :status, attempt = :attempt,
                    job_id = :job, error_code = :code, error_message = :message
                where run_id = :id and batch_index = :index
                """).param("id", batch.runId()).param("index", batch.index()).param("status", batch.status())
                .param("attempt", batch.attempt()).param("job", batch.jobId())
                .param("code", batch.errorCode()).param("message", batch.errorMessage()).update();
    }
}
