package nc.ticagou.health;

import java.time.OffsetDateTime;

/**
 * État du service, interrogé par le bureau et la tablette (pastille de connexion).
 *
 * @param status   UP si tout fonctionne, DEGRADED si le serveur répond mais pas la base
 * @param database état de la connexion PostgreSQL
 */
public record HealthResponse(
        Status status,
        String version,
        OffsetDateTime serverTime,
        String zone,
        DatabaseStatus database) {

    public enum Status { UP, DEGRADED }

    public enum DatabaseStatus { UP, DOWN }
}
