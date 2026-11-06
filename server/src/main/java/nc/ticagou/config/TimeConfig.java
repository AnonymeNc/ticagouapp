package nc.ticagou.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** Horloge injectée partout (jamais {@code LocalDateTime.now()} direct) pour rester testable. */
    @Bean
    Clock clock(TiCagouProperties properties) {
        return Clock.system(properties.zone());
    }
}
