package org.hhclicker.backend.resume.service;

import org.hhclicker.backend.resume.model.HhResume;
import org.hhclicker.backend.resume.model.ResumeStatistics;

public record ResumeProfile(
        HhResume resume,
        String accountName,
        ResumeStatistics statistics
) {
}

