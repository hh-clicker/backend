package org.hhclicker.backend.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.account.dto.AccountResponse;
import org.hhclicker.backend.account.dto.CreateAccountRequest;
import org.hhclicker.backend.account.dto.UpdateAccountRequest;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.account.service.CreateAccountCommand;
import org.hhclicker.backend.account.service.UpdateAccountCommand;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "HH accounts", description = "Локальные профили HH-аккаунтов")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @Operation(summary = "Получить HH-аккаунты")
    public List<AccountResponse> findAll() {
        return accountService.findAll().stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Получить HH-аккаунт")
    @ApiResponse(responseCode = "404", description = "Аккаунт не найден", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public AccountResponse findById(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.findById(accountId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить HH-аккаунт")
    @ApiResponse(responseCode = "201", description = "Аккаунт добавлен")
    @ApiResponse(responseCode = "400", description = "Некорректные данные", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "HH-аккаунт уже существует", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.create(new CreateAccountCommand(request.name(), request.hhUserId())));
    }

    @PutMapping("/{accountId}")
    @Operation(summary = "Изменить HH-аккаунт")
    @ApiResponse(responseCode = "400", description = "Некорректные данные", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Аккаунт не найден", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "HH-аккаунт уже существует", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public AccountResponse update(
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateAccountRequest request
    ) {
        return AccountResponse.from(accountService.update(
                accountId,
                new UpdateAccountCommand(request.name(), request.hhUserId())
        ));
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить HH-аккаунт")
    @ApiResponse(responseCode = "204", description = "Аккаунт удалён")
    @ApiResponse(responseCode = "404", description = "Аккаунт не найден", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "С аккаунтом связаны резюме или другие данные", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public void delete(@PathVariable UUID accountId) {
        accountService.delete(accountId);
    }
}
