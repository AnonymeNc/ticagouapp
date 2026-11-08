package nc.ticagou.console;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Console de l'éditeur (ADR 0017) : parc des crèches, facturation, relances préparées, reporting. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class ConsoleApplication {

    public static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(NOUMEA);
    }
}
