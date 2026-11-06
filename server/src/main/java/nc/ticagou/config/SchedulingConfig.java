package nc.ticagou.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tâches périodiques : recalcul du taux d'encadrement chaque minute, battement des flux SSE. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
