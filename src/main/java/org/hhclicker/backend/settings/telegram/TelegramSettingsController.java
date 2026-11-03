package org.hhclicker.backend.settings.telegram;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings/telegram")
public class TelegramSettingsController {
    private final TelegramConnectionService service;

    public TelegramSettingsController(TelegramConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public TelegramConnectionService.View get() {
        return service.state();
    }

    @PostMapping("/connect")
    public TelegramConnectionService.View connect(@RequestBody @Valid ConnectRequest request) {
        return service.connect(request.token());
    }

    @PostMapping("/test")
    public TelegramConnectionService.View test() {
        return service.test();
    }

    @DeleteMapping
    public TelegramConnectionService.View disconnect() {
        return service.disconnect();
    }

    public record ConnectRequest(@NotBlank @Size(max = 256) String token) {
        @Override
        public String toString() {
            return "ConnectRequest[token=REDACTED]";
        }
    }
}
