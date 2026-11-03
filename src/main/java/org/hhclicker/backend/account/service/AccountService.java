package org.hhclicker.backend.account.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.account.exception.AccountHasDependentDataException;
import org.hhclicker.backend.account.exception.AccountNotFoundException;
import org.hhclicker.backend.account.exception.HhAccountAlreadyExistsException;
import org.hhclicker.backend.account.exception.InvalidAccountDataException;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.repository.AccountRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    public List<HhAccount> findAll() {
        return accountRepository.findAll();
    }

    public HhAccount findById(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional
    public HhAccount create(CreateAccountCommand command) {
        String name = normalizeName(command.name());
        String hhUserId = normalizeNullable(command.hhUserId());
        validateHhUserId(hhUserId);
        ensureHhUserIdAvailable(hhUserId, null);

        Instant now = clock.instant();
        HhAccount account = new HhAccount(
                UUID.randomUUID(),
                name,
                hhUserId,
                AuthorizationStatus.UNKNOWN,
                now,
                now
        );
        try {
            accountRepository.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new HhAccountAlreadyExistsException(hhUserId, exception);
        }
        return account;
    }

    @Transactional
    public HhAccount update(UUID accountId, UpdateAccountCommand command) {
        HhAccount existing = findById(accountId);
        String name = normalizeName(command.name());
        String hhUserId = normalizeNullable(command.hhUserId());
        validateHhUserId(hhUserId);
        ensureHhUserIdAvailable(hhUserId, accountId);

        HhAccount updated = new HhAccount(
                existing.id(),
                name,
                hhUserId,
                existing.authorizationStatus(),
                existing.createdAt(),
                clock.instant()
        );
        try {
            if (!accountRepository.update(updated)) {
                throw new AccountNotFoundException(accountId);
            }
        } catch (DuplicateKeyException exception) {
            throw new HhAccountAlreadyExistsException(hhUserId, exception);
        }
        return findById(accountId);
    }

    @Transactional
    public void delete(UUID accountId) {
        try {
            if (!accountRepository.deleteById(accountId)) {
                throw new AccountNotFoundException(accountId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new AccountHasDependentDataException(accountId, exception);
        }
    }

    @Transactional
    public void updateAuthorizationStatus(UUID accountId, AuthorizationStatus status) {
        if (!accountRepository.updateAuthorizationStatus(accountId, status, clock.instant())) {
            throw new AccountNotFoundException(accountId);
        }
    }

    private void ensureHhUserIdAvailable(String hhUserId, UUID currentAccountId) {
        if (hhUserId == null) {
            return;
        }
        accountRepository.findByHhUserId(hhUserId)
                .filter(account -> !account.id().equals(currentAccountId))
                .ifPresent(account -> {
                    throw new HhAccountAlreadyExistsException(hhUserId);
                });
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidAccountDataException("Название HH-аккаунта не должно быть пустым.");
        }
        String normalized = value.trim();
        if (normalized.length() > 120) {
            throw new InvalidAccountDataException("Название HH-аккаунта не должно быть длиннее 120 символов.");
        }
        return normalized;
    }

    private void validateHhUserId(String hhUserId) {
        if (hhUserId != null && hhUserId.length() > 100) {
            throw new InvalidAccountDataException("Идентификатор пользователя HH не должен быть длиннее 100 символов.");
        }
    }
}
