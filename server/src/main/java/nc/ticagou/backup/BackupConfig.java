package nc.ticagou.backup;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import nc.ticagou.config.TiCagouProperties;

@Configuration
class BackupConfig {

    /** Flyway migre après une sauvegarde de la base existante. */
    @Bean
    FlywayMigrationStrategy preMigrationBackup(DataSource dataSource, PgTools pg, TiCagouProperties properties,
                                               Clock clock) {
        return new PreMigrationBackup(dataSource, pg, properties, clock);
    }
}
