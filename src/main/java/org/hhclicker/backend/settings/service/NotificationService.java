package org.hhclicker.backend.settings.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.settings.model.NotificationRun;
import org.hhclicker.backend.settings.model.NotificationSettings;
import org.hhclicker.backend.settings.repository.NotificationRepository;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public NotificationSettings settings() {
        return repository.settings();
    }

    public List<NotificationRun> runs() {
        return repository.runs();
    }

    @Transactional
    public NotificationSettings save(NotificationSettings settings) {
        repository.save(settings);
        return settings;
    }
}
