package nc.ticagou.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** Horloge injectée partout (jamais {@code LocalDateTime.now()} direct) pour rester testable. */
    @Bean
    Clock clock(TiCagouProperties properties) {
        return Clock.system(properties.zone());
    }

    /** {@code @PastOrPresent} suit la même horloge : sur une machine en UTC, la date du jour à Nouméa passerait pour future. */
    @Bean
    ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
