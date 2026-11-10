package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;

import nc.ticagou.IntegrationTest;

/** Mot de passe oublié par la direction : lien créé par l'éditeur (creche.sh mot-de-passe), usage unique, 24 heures. */
class PasswordResetIT extends IntegrationTest {

    /** Même requête que {@code creche.sh mot-de-passe} : seule l'empreinte du jeton est gardée. */
    private void createLink(String token, String login, String validity) {
        var created = jdbc.queryForList("""
                INSERT INTO password_reset (token_hash, structure_id, user_id, expires_at)
                SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex'), structure_id, id, now() + ?::interval
                FROM app_user WHERE lower(login) = lower(?) AND active RETURNING (SELECT login FROM app_user u WHERE u.id = user_id)""",
                String.class, token, validity, login);
        assertThat(created).as("le compte nommé, comme l'affiche creche.sh").containsExactly(login);
    }

    private int reset(String token, String password) throws Exception {
        return status(perform(post("/api/v1/auth/password-reset"), null, Map.of("token", token, "password", password)));
    }

    @Test
    void lienAUsageUniqueQuiFermeLesSessions() throws Exception {
        install();
        var token = "0123456789abcdef0123456789abcdef0123456789abcdef";
        createLink(token, DIRECTOR_LOGIN, "24 hours");

        var account = body(perform(post("/api/v1/auth/password-reset/check"), null, Map.of("token", token)));
        assertThat(account.get("login").asText()).isEqualTo(DIRECTOR_LOGIN);

        assertThat(reset(token, "court")).as("mot de passe trop court : le lien reste valable").isEqualTo(422);
        assertThat(reset(token, "Lagon-Bleu-2027!")).isEqualTo(204);

        assertThat(status(perform(post("/api/v1/auth/login"), null, Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD))))
                .as("ancien mot de passe").isEqualTo(401);
        assertThat(status(perform(post("/api/v1/auth/login"), null, Map.of("login", DIRECTOR_LOGIN, "password", "Lagon-Bleu-2027!"))))
                .isEqualTo(200);

        var reused = perform(post("/api/v1/auth/password-reset"), null, Map.of("token", token, "password", "Autre-Mot-2027!"));
        assertThat(status(reused)).as("usage unique").isEqualTo(422);
        assertThat(body(reused).get("code").asText()).isEqualTo("reset_expired");
        assertThat(auditCount("USER_PASSWORD_RESET_BY_LINK")).isEqualTo(1);
    }

    @Test
    void lienExpireOuInconnuRefuse() throws Exception {
        install();
        var expired = "fedcba9876543210fedcba9876543210fedcba9876543210";
        createLink(expired, DIRECTOR_LOGIN, "-1 minute");
        assertThat(reset(expired, "Lagon-Bleu-2027!")).isEqualTo(422);
        assertThat(status(perform(post("/api/v1/auth/password-reset/check"), null, Map.of("token", "abcdef0123456789abcdef0123456789"))))
                .isEqualTo(422);
        assertThat(status(perform(post("/api/v1/auth/password-reset/check"), null, Map.of("token", "pas un jeton")))).isEqualTo(422);
    }
}
