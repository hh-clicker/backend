package org.hhclicker.backend.resume.service;

import org.hhclicker.backend.resume.model.ResumeStatistics;

import java.util.UUID;

/**
 * Исходящий порт для составной статистики резюме, данные которой принадлежат другим модулям.
 */
public interface ResumeStatisticsProvider {

    ResumeStatistics get(UUID resumeId);
}
