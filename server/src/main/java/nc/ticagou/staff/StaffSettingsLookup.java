package nc.ticagou.staff;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Réglages du module Personnel en vigueur à une date (valeurs par défaut si la structure n'en a pas). */
@Component
public class StaffSettingsLookup {

    private final StaffSettingsRepository settings;
    private final Clock clock;

    public StaffSettingsLookup(StaffSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffSettings on(UUID structureId, LocalDate day) {
        return settings.findByStructureIdOrderByValidFromDesc(structureId).stream()
                .filter(s -> !s.getValidFrom().isAfter(day)).findFirst()
                .orElseGet(() -> {
                    var defaults = new StaffSettings(structureId, day, Instant.now(clock));
                    defaults.update(30, 30, 39 * 60);
                    return defaults;
                });
    }
}
