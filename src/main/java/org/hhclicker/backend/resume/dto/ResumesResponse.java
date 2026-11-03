package org.hhclicker.backend.resume.dto;

import java.util.List;

public record ResumesResponse(
        List<ResumeProfileResponse> items
) {
}

