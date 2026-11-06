package nc.ticagou.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import nc.ticagou.health.HealthResponse.DatabaseStatus;
import nc.ticagou.health.HealthResponse.Status;

@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final int DB_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final Clock clock;
    private final String version;

    public HealthService(DataSource dataSource, Clock clock, ObjectProvider<BuildProperties> buildProperties) {
        this.dataSource = dataSource;
        this.clock = clock;
        var build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    public HealthResponse check() {
        // Le serveur répond même sans base : le client affiche alors « base indisponible ».
        var database = databaseStatus();
        return new HealthResponse(
                database == DatabaseStatus.UP ? Status.UP : Status.DEGRADED,
                version,
                OffsetDateTime.now(clock),
                clock.getZone().getId(),
                database);
    }

    private DatabaseStatus databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_TIMEOUT_SECONDS) ? DatabaseStatus.UP : DatabaseStatus.DOWN;
        } catch (SQLException e) {
            log.warn("Base de données injoignable : {}", e.getMessage());
            return DatabaseStatus.DOWN;
        }
    }
}
