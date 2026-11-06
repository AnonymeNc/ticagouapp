package nc.ticagou.portail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Portail parents hébergé (ADR 0010) : alimenté par les serveurs des crèches, consulté par les familles. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
