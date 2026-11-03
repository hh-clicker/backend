package org.hhclicker.backend.browser.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.browser.dto.*;
import org.hhclicker.backend.browser.service.BrowserProfileService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/browser")
@Tag(name = "Browser profiles", description = "Постоянные профили Google Chrome")
public class BrowserController {

    private final BrowserProfileService service;

    public BrowserController(BrowserProfileService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Получить профиль и состояние браузера")
    public BrowserPageResponse load(@RequestParam UUID accountId) {
        return BrowserPageResponse.from(service.loadPage(accountId));
    }

    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public BrowserProfileResponse create(@Valid @RequestBody CreateBrowserProfileRequest request) {
        var profile = service.create(request.accountId());
        return BrowserProfileResponse.from(profile, service.loadPage(profile.accountId()).runtime());
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse start(@Valid @RequestBody StartBrowserRequest request) {
        return new BrowserCommandReceiptResponse(service.open(request.accountId()));
    }

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse stop() {
        return new BrowserCommandReceiptResponse(service.close());
    }

    @PostMapping("/check-authorization")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Проверить авторизацию в запущенном профиле HH-аккаунта")
    public BrowserCommandReceiptResponse checkAuthorization(@Valid @RequestBody StartBrowserRequest request) {
        return new BrowserCommandReceiptResponse(service.checkAuthorization(request.accountId()));
    }

    @PostMapping("/open-url")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse openUrl(@Valid @RequestBody OpenBrowserUrlRequest request) {
        return new BrowserCommandReceiptResponse(service.openUrl(request.accountId(), request.url()));
    }

    @PostMapping("/discover-resumes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Проверить вход и прочитать карточки резюме без импорта")
    public BrowserCommandReceiptResponse discoverResumes(@Valid @RequestBody StartBrowserRequest request) {
        return new BrowserCommandReceiptResponse(service.discoverResumes(request.accountId()));
    }

    @PostMapping("/resume-text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse readResumeText(@Valid @RequestBody ResumeTextRequest request) {
        return new BrowserCommandReceiptResponse(service.readResumeText(request.accountId(), request.hhResumeId()));
    }

    public record ResumeTextRequest(@jakarta.validation.constraints.NotNull UUID accountId,
                                    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{20,100}") String hhResumeId) {
    }
}
