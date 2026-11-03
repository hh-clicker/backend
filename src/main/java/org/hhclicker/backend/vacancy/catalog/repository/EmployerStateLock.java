package org.hhclicker.backend.vacancy.catalog.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Serializes ingestion and stop-list changes until the surrounding transaction ends. */
@Repository
public class EmployerStateLock {
    private static final int NAMESPACE = 0x484843;
    private static final int INGESTION_NAMESPACE = 0x484849;
    private final JdbcClient jdbc;

    public EmployerStateLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire(Collection<String> employerIds) {
        requireTransaction();
        // Sort the actual lock keys, including collisions, to avoid inverse acquisition order.
        // A hash collision only serializes unrelated employers; it cannot bypass a lock.
        employerIds.stream().filter(Objects::nonNull).map(String::hashCode).distinct().sorted()
                .forEach(key -> lock(NAMESPACE, key));
    }

    public void acquireIngestion(UUID resumeId) {
        requireTransaction();
        // Stabilize old employer IDs before acquiring both old and incoming employer locks.
        lock(INGESTION_NAMESPACE, resumeId.hashCode());
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Employer state locks require an active transaction");
        }
    }

    private void lock(int namespace, int key) {
        jdbc.sql("select pg_advisory_xact_lock(:namespace, :key)")
                .param("namespace", namespace).param("key", key)
                .query((row, index) -> true).single();
    }
}
