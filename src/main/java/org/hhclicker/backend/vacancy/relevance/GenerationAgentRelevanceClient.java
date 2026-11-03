package org.hhclicker.backend.vacancy.relevance;

import java.util.List;
import java.util.UUID;

public interface GenerationAgentRelevanceClient {

    SubmittedJob submit(GenerationRequest request);

    default SubmittedJob submit(GenerationRequest request, String idempotencyKey) {
        return submit(request);
    }

    GenerationJob find(UUID id);

    record GenerationRequest(String resumeText, List<VacancyInput> vacancies) {
    }

    record VacancyInput(
            String hhId,
            String title,
            String company,
            String salary,
            NormalizedSalaryInput normalizedSalary,
            String location,
            String address,
            List<String> workFormats,
            String employmentForm,
            List<String> workScheduleByDays,
            String experience,
            Boolean internship,
            Boolean temporary,
            List<String> professionalRoles,
            String plainText
    ) {
        public VacancyInput(String hhId, String title, String company, String salary,
                            NormalizedSalaryInput normalizedSalary, String location, String address,
                            List<String> workFormats, String employmentForm, List<String> workScheduleByDays,
                            String experience, Boolean internship, Boolean temporary,
                            List<String> professionalRoles) {
            this(hhId, title, company, salary, normalizedSalary, location, address, workFormats,
                    employmentForm, workScheduleByDays, experience, internship, temporary, professionalRoles, "");
        }
    }

    record NormalizedSalaryInput(
            Long from,
            Long to,
            String currency,
            Boolean gross,
            String mode,
            String frequency
    ) {
    }

    record SubmittedJob(UUID id, String status) {
    }

    record GenerationJob(UUID id, String status, GenerationResult result, GenerationError error) {
    }

    record GenerationResult(List<Evaluation> items) {
    }

    record Evaluation(String hhId, String category, String confidence, String reason) {
    }

    record GenerationError(String code, String message, Long resetsAt) {
        public GenerationError(String code, String message) { this(code, message, null); }
    }
}
