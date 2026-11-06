package nc.ticagou.portail;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Horloge injectée (remplaçable dans les tests) ; les jours affichés aux parents sont ceux de Nouméa. */
@Configuration
public class TimeConfig {

    public static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");

    @Bean
    Clock clock() {
        return Clock.system(NOUMEA);
    }
}
