package org.hhclicker.backend.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hhclicker.backend.browser.model.BrowserResumeSummary;

public record BrowserResumeSummaryRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{20,100}") String hhResumeId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 100000) String plainText
) {
    public BrowserResumeSummary toModel() {
        return new BrowserResumeSummary(hhResumeId, name.trim(), plainText);
    }
}
