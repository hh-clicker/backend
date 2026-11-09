package org.hhclicker.backend.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateResumeTextRequest(@NotBlank @Size(max = 100000) String plainText) {}
