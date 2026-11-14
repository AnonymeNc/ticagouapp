package nc.ticagou.portail.parent;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portail.auth.AuthService;
import nc.ticagou.portail.push.PushService;
import nc.ticagou.portail.sync.SyncService;

/**
 * « Supprimer mon compte » (maquette Pa8, ADR 0024, exigence d'Apple) : confirmé par un code reçu par e-mail, efface
 * tout de suite l'accès au portail (sessions, appareils, notifications) et prévient chaque crèche concernée par un
 * message, pour qu'elle recontacte la famille (export, effacement de son dossier selon ses obligations).
 */
@Service
public class AccountService {

    static final String MESSAGE = "Demande automatique : ce parent a supprimé son compte du portail depuis l’application "
            + "Ti’Cagou Parents. Il ne reçoit plus rien. Merci de le recontacter pour l’export ou l’effacement des données "
            + "de la famille, selon vos obligations.";

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final PushService push;
    private final SyncService sync;
    private final Clock clock;

    public AccountService(JdbcTemplate jdbc, AuthService auth, PushService push, SyncService sync, Clock clock) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.push = push;
        this.sync = sync;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = nc.ticagou.portail.common.ApiException.class)
    public void delete(String email, String code, String ip) {
        // Le code fait foi : il ouvre une session aussitôt révoquée avec les autres.
        auth.verifyCode(email, code, ip);
        var now = Timestamp.from(clock.instant());
        for (var row : jdbc.queryForList("SELECT structure_id, family_id, id FROM guardian WHERE lower(email) = lower(?)", email)) {
            sync.deposit((UUID) row.get("structure_id"), "MESSAGE", (UUID) row.get("family_id"),
                    Map.of("guardianId", row.get("id"), "body", MESSAGE, "sentAt", now.toInstant()));
        }
        jdbc.update("UPDATE parent_session SET revoked_at = ? WHERE lower(email) = lower(?) AND revoked_at IS NULL", now, email);
        push.forgetAll(email);
        jdbc.update("INSERT INTO account_deletion (email, deleted_at) VALUES (lower(?), ?) "
                + "ON CONFLICT (email) DO UPDATE SET deleted_at = excluded.deleted_at", email, now);
    }
}
