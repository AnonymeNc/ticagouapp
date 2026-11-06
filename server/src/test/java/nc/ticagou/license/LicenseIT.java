package nc.ticagou.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import nc.ticagou.IntegrationTest;

/** ADR 0011 : évaluation de 30 jours, grâce, lecture seule, PC du bureau comptés. */
class LicenseIT extends IntegrationTest {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Pacific/Noumea"));

    @Autowired
    LicenseService licenses;

    String director;

    @BeforeEach
    void installer() throws Exception {
        ReflectionTestUtils.setField(licenses, "lastRecorded", null);
        director = install();
    }

    /** Le jour retenu est gardé en mémoire : les autres classes de test repartent du vrai jour. */
    @AfterEach
    void oublierLeJourRetenu() {
        ReflectionTestUtils.setField(licenses, "lastRecorded", null);
    }

    MvcResult loginFrom(String clientId, String name) throws Exception {
        var request = post("/api/v1/auth/login");
        if (clientId != null) {
            request.header(DesktopClientService.HEADER, clientId).header(DesktopClientService.NAME_HEADER, name);
        }
        return perform(request, null, Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));
    }

    void installedDaysAgo(int days) {
        jdbc.update("UPDATE installation SET initialized_at = now() - make_interval(days => ?)", days);
    }

    void subscribedUntil(LocalDate validUntil) {
        TestLicenses.apply(jdbc, 2, validUntil);
    }

    @Test
    void evaluationDeTrenteJoursPuisLectureSeule() throws Exception {
        installedDaysAgo(29);
        var evaluation = body(getAs(director, "/api/v1/system/license"));
        assertThat(evaluation.get("state").asText()).isEqualTo("EVALUATION");
        assertThat(evaluation.get("daysLeft").asInt()).isEqualTo(1);
        assertThat(status(perform(post("/api/v1/children"), director, Map.of()))).as("saisie permise (400 = validation)")
                .isNotEqualTo(403);

        installedDaysAgo(30);
        var ended = body(getAs(director, "/api/v1/system/license/status"));
        assertThat(ended.get("state").asText()).isEqualTo("EVALUATION_TERMINEE");
        var refused = perform(post("/api/v1/children"), director, Map.of());
        assertThat(status(refused)).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("license_read_only");

        // Restent possibles : lecture, connexion, sauvegarde. L'abonnement débloque tout.
        assertThat(status(getAs(director, "/api/v1/children"))).isEqualTo(200);
        assertThat(status(loginFrom(null, null))).isEqualTo(200);
        assertThat(status(perform(post("/api/v1/backups/run"), director, null))).isNotEqualTo(403);
        subscribedUntil(TODAY.plusYears(1));
        assertThat(body(getAs(director, "/api/v1/system/license")).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(status(perform(post("/api/v1/children"), director, Map.of()))).isNotEqualTo(403);
    }

    @Test
    void licenceExpireeGraceDeTrenteJoursPuisLectureSeule() throws Exception {
        subscribedUntil(TODAY.minusDays(30));
        var grace = body(getAs(director, "/api/v1/system/license"));
        assertThat(grace.get("state").asText()).isEqualTo("EXPIREE_GRACE");
        assertThat(grace.get("deadline").asText()).isEqualTo(TODAY.plusDays(1).toString());
        assertThat(status(perform(post("/api/v1/children"), director, Map.of()))).isNotEqualTo(403);
        var pairing = perform(post("/api/v1/devices"), director, Map.of("name", "Accueil"));
        assertThat(body(pairing).get("code").asText()).as("pas d'appairage pendant la grâce").isEqualTo("license_expired");

        subscribedUntil(TODAY.minusDays(31));
        assertThat(body(getAs(director, "/api/v1/system/license/status")).get("state").asText()).isEqualTo("LECTURE_SEULE");
        assertThat(body(perform(post("/api/v1/children"), director, Map.of())).get("code").asText())
                .isEqualTo("license_read_only");
    }

    @Test
    void reculerLHorlogeNeRendPasDeJours() throws Exception {
        installedDaysAgo(10);
        jdbc.update("UPDATE installation SET last_seen_on = ?", Date.valueOf(TODAY.plusDays(25)));
        ReflectionTestUtils.setField(licenses, "lastRecorded", null);
        var status = body(getAs(director, "/api/v1/system/license/status"));
        assertThat(status.get("state").asText()).as("le serveur a déjà vu une date plus tardive")
                .isEqualTo("EVALUATION_TERMINEE");
    }

    @Test
    void pcDuBureauComptesRetiresEtLiberes() throws Exception {
        // L'installation a enregistré un PC sans identifiant (assistant) ; l'évaluation en permet deux.
        assertThat(status(loginFrom("pc-accueil", "Accueil"))).isEqualTo(200);
        var third = loginFrom("pc-bureau", "Bureau%20de%20la%20direction");
        assertThat(status(third)).isEqualTo(422);
        assertThat(body(third).get("code").asText()).isEqualTo("license_desktops");
        assertThat(auditCount("DESKTOP_REFUSED")).isEqualTo(1);

        var list = body(getAs(director, "/api/v1/system/desktops"));
        assertThat(list).hasSize(2);
        var accueil = list.findValues("label").stream().map(n -> n.asText()).toList();
        assertThat(accueil).contains("Accueil");
        assertThat(body(getAs(director, "/api/v1/system/license")).get("desktops").asInt()).isEqualTo(2);

        // Retrait : la place est libérée, le PC retiré ne peut plus se connecter.
        var accueilId = jdbc.queryForObject("SELECT id FROM desktop_client WHERE label = 'Accueil'", String.class);
        assertThat(status(perform(post("/api/v1/system/desktops/" + accueilId + "/revoke"), director, null))).isEqualTo(204);
        assertThat(status(loginFrom("pc-bureau", "Bureau%20de%20la%20direction"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT label FROM desktop_client WHERE revoked_at IS NULL AND label LIKE 'Bureau%'",
                String.class)).isEqualTo("Bureau de la direction");
        var revoked = loginFrom("pc-accueil", "Accueil");
        assertThat(status(revoked)).isEqualTo(403);
        assertThat(body(revoked).get("code").asText()).isEqualTo("desktop_revoked");

        // Un PC absent depuis 90 jours libère sa place.
        jdbc.update("UPDATE desktop_client SET last_seen_at = now() - interval '91 days' WHERE label LIKE 'Bureau%'");
        assertThat(status(loginFrom("pc-salle", "Salle"))).isEqualTo(200);
    }

    @Test
    void unPcRetirePerdSaSessionAuRenouvellement() throws Exception {
        var session = body(loginFrom("pc-accueil", "Accueil"));
        var id = jdbc.queryForObject("SELECT id FROM desktop_client WHERE label = 'Accueil'", String.class);
        perform(post("/api/v1/system/desktops/" + id + "/revoke"), director, null);
        var refresh = post("/api/v1/auth/refresh").header(DesktopClientService.HEADER, "pc-accueil");
        var result = perform(refresh, null, Map.of("refreshToken", session.get("refreshToken").asText()));
        assertThat(status(result)).isEqualTo(403);
    }

    @Test
    void lesRolesNonDirectionNeVoientQueLEtat() throws Exception {
        createUser(director, "EDUCATRICE", "lea.wema", "Educatrice-2026!", null);
        var educ = accessToken("lea.wema", "Educatrice-2026!");
        assertThat(status(getAs(educ, "/api/v1/system/license/status"))).isEqualTo(200);
        assertThat(status(getAs(educ, "/api/v1/system/desktops"))).isEqualTo(403);
    }
}
