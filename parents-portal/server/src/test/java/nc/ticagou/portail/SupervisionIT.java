package nc.ticagou.portail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import nc.ticagou.portail.supervision.AlertRules;
import nc.ticagou.portail.supervision.SupervisionService;

/** Supervision des serveurs de crèche (ADR 0012) : activation, état signé, alertes et récapitulatif par e-mail. */
class SupervisionIT extends PortalTest {

    @Autowired
    SupervisionService supervision;

    Creche site;

    @BeforeEach
    void activer() throws Exception {
        MAILS.clear();
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var code = supervision.newCode("Les P'tits Niaoulis");
        var result = mvc.perform(post("/supervision/v1/activate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                Map.of("activationCode", code, "name", "Les P'tits Niaoulis",
                        "publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))))).andReturn();
        assertThat(status(result)).isEqualTo(200);
        site = new Creche(UUID.fromString(body(result).get("siteId").asText()), keys);
    }

    Map<String, Object> healthy() {
        return Map.of("version", "0.2.0", "database", "UP",
                "backup", Map.of("enabled", true, "toolsAvailable", true, "stale", false, "lastSuccessAt", Instant.now().toString()),
                "disk", Map.of("dataFreeBytes", 200L << 30, "dataTotalBytes", 500L << 30),
                "license", Map.of("state", "ACTIVE", "validUntil", "2027-10-31"),
                "tablets", Map.of("paired", 2, "silent", 0),
                "portal", Map.of("state", "DESACTIVE", "failures", 0),
                "attendanceToReview", 0,
                "childName", "ne doit pas être gardé");
    }

    /** Les e-mails partent en arrière-plan : on attend qu'il y en ait {@code n}. */
    static void awaitMails(int n) throws InterruptedException {
        var deadline = Instant.now().plusSeconds(5);
        while (MAILS.size() < n && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
    }

    int send(Map<String, Object> status) throws Exception {
        return status(signed(site, "POST", "/supervision/v1/status", json.writeValueAsBytes(status), Instant.now()));
    }

    Creche other(String name) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var result = mvc.perform(post("/supervision/v1/activate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                Map.of("activationCode", supervision.newCode(name), "name", name,
                        "publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))))).andReturn();
        return new Creche(UUID.fromString(body(result).get("siteId").asText()), keys);
    }

    Map<String, Object> withGroup(String hash, String name, long outstanding) {
        var status = new java.util.HashMap<>(healthy());
        var group = new java.util.LinkedHashMap<String, Object>(Map.of("codeHash", hash, "name", name,
                "url", "https://" + name.toLowerCase().replace(' ', '-') + ".ticagou.nc", "activeChildren", 30, "places", 36,
                "occupancyPercent", 81, "invoicedLastMonth", 1_250_000L, "outstanding", outstanding, "ratioBreaches7d", 1,
                "asOf", "2026-11-10"));
        group.put("childName", "ne doit pas sortir");
        status.put("group", group);
        return status;
    }

    @Test
    void vueGroupeReserveeAuxCrechesDuMemeGroupe() throws Exception {
        var hash = "a".repeat(64);
        var sister = other("Les Cagous de Dumbéa");
        var stranger = other("Crèche voisine");
        assertThat(send(withGroup(hash, "Les P'tits Niaoulis", 85_000))).isEqualTo(204);
        assertThat(status(signed(sister, "POST", "/supervision/v1/status", json.writeValueAsBytes(withGroup(hash, "Les Cagous de Dumbéa", 0)),
                Instant.now()))).isEqualTo(204);
        assertThat(status(signed(stranger, "POST", "/supervision/v1/status", json.writeValueAsBytes(withGroup("b".repeat(64), "Crèche voisine", 0)),
                Instant.now()))).isEqualTo(204);

        var group = body(signed(site, "GET", "/supervision/v1/group", new byte[0], Instant.now()));
        assertThat(group).extracting(g -> g.get("name").asText()).containsExactly("Les Cagous de Dumbéa", "Les P'tits Niaoulis");
        assertThat(group.get(1).get("self").asBoolean()).isTrue();
        assertThat(group.get(1).get("outstanding").asLong()).isEqualTo(85_000);
        assertThat(group.toString()).doesNotContain("childName", "Crèche voisine");

        // La crèche quitte le groupe : son état suivant part sans le bloc, plus personne ne la voit.
        assertThat(send(healthy())).isEqualTo(204);
        var after = body(signed(sister, "GET", "/supervision/v1/group", new byte[0], Instant.now()));
        assertThat(after).extracting(g -> g.get("name").asText()).containsExactly("Les Cagous de Dumbéa");
        assertThat(body(signed(site, "GET", "/supervision/v1/group", new byte[0], Instant.now()))).isEmpty();
    }

    @Test
    void etatSigneEnregistreSansChampInconnu() throws Exception {
        assertThat(send(healthy())).isEqualTo(204);
        var stored = jdbc.queryForObject("SELECT last_status::text FROM supervision_site", String.class);
        assertThat(stored).contains("0.2.0").doesNotContain("childName");
        assertThat(MAILS).isEmpty();

        // Clé d'une autre crèche : refusé.
        var impostor = new Creche(site.id(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        assertThat(status(signed(impostor, "POST", "/supervision/v1/status", json.writeValueAsBytes(healthy()), Instant.now())))
                .isEqualTo(401);
    }

    @Test
    void alerteOuvertePuisResolueUnEMailChacune() throws Exception {
        var full = new java.util.HashMap<>(healthy());
        full.put("disk", Map.of("dataFreeBytes", 2L << 30, "dataTotalBytes", 500L << 30));
        full.put("backup", Map.of("enabled", true, "toolsAvailable", true, "stale", true, "lastSuccessAt", "2026-10-30T15:00:00Z"));
        assertThat(send(full)).isEqualTo(204);
        assertThat(jdbc.queryForList("SELECT kind FROM supervision_alert WHERE closed_at IS NULL ORDER BY kind", String.class))
                .containsExactly("DISQUE", "SAUVEGARDE");
        awaitMails(2);
        assertThat(MAILS).hasSize(2);
        assertThat(MAILS.getFirst().getTo()).containsExactly("support@ticagou.nc");
        assertThat(MAILS.stream().map(m -> m.getSubject())).anyMatch(s -> s.contains("Alerte — Les P'tits Niaoulis — Espace disque"));

        // Même état renvoyé : pas de nouvel e-mail.
        assertThat(send(full)).isEqualTo(204);
        assertThat(MAILS).hasSize(2);

        assertThat(send(healthy())).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supervision_alert WHERE closed_at IS NULL", Long.class)).isZero();
        awaitMails(4);
        assertThat(MAILS).hasSize(4);
        assertThat(MAILS.get(3).getSubject()).startsWith("[Ti'Cagou] Résolu");
    }

    @Test
    void serveurMuetEtRecapitulatif() throws Exception {
        assertThat(send(healthy())).isEqualTo(204);
        jdbc.update("UPDATE supervision_site SET last_seen_at = ?", Timestamp.from(Instant.now().minusSeconds(13 * 3600)));
        supervision.evaluateAll();
        awaitMails(1);
        assertThat(jdbc.queryForList("SELECT kind FROM supervision_alert WHERE closed_at IS NULL", String.class)).containsExactly("SILENCE");
        MAILS.clear();
        supervision.sendDigest();
        awaitMails(1);
        assertThat(MAILS).hasSize(1);
        assertThat(MAILS.getFirst().getSubject()).contains("1 site, 1 alerte");
        assertThat(MAILS.getFirst().getText()).contains("Les P'tits Niaoulis", "Serveur muet");
    }

    @Test
    void administrationParLHebergeur() throws Exception {
        assertThat(send(healthy())).isEqualTo(204);
        var refused = mvc.perform(get("/admin/v1/supervision")).andReturn();
        assertThat(status(refused)).isEqualTo(401);
        var list = mvc.perform(get("/admin/v1/supervision").header("Authorization", "Bearer jeton-hebergeur")).andReturn();
        assertThat(body(list).get(0).get("name").asText()).isEqualTo("Les P'tits Niaoulis");
        assertThat(body(list).get(0).get("version").asText()).isEqualTo("0.2.0");
        assertThat(body(list).get(0).get("slug").isNull()).as("libellé libre : pas de slug").isTrue();
        var code = mvc.perform(post("/admin/v1/supervision-codes").header("Authorization", "Bearer jeton-hebergeur")
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Crèche B\"}")).andReturn();
        assertThat(body(code).get("activationCode").asText()).startsWith("S");
    }

    @Test
    void consoleDeLEditeurSlugUsageEtHistorique() throws Exception {
        // Crèche créée par creche.sh : le libellé du code est son slug.
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var activated = mvc.perform(post("/supervision/v1/activate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                Map.of("activationCode", supervision.newCode("lagon-bleu"), "name", "Micro-crèche Lagon Bleu",
                        "publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))))).andReturn();
        var lagon = new Creche(UUID.fromString(body(activated).get("siteId").asText()), keys);
        var since = Instant.now().minusSeconds(1);
        var status = new java.util.HashMap<>(healthy());
        status.put("usage", Map.of("activeChildren", 12, "places", 12, "attendances7d", 118));
        status.put("backup", Map.of("enabled", false));
        assertThat(status(signed(lagon, "POST", "/supervision/v1/status", json.writeValueAsBytes(status), Instant.now()))).isEqualTo(204);

        var list = body(mvc.perform(get("/admin/v1/supervision").header("Authorization", "Bearer jeton-hebergeur")).andReturn());
        var row = java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .filter(n -> "lagon-bleu".equals(n.path("slug").asText(null))).findFirst().orElseThrow();
        assertThat(row.at("/status/usage/attendances7d").asInt()).as("usage gardé").isEqualTo(118);

        assertThat(status(mvc.perform(get("/admin/v1/supervision/alerts").param("since", since.toString())).andReturn())).isEqualTo(401);
        var alerts = body(mvc.perform(get("/admin/v1/supervision/alerts").param("since", since.toString())
                .header("Authorization", "Bearer jeton-hebergeur")).andReturn());
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("slug").asText()).isEqualTo("lagon-bleu");
        assertThat(alerts.get(0).get("kind").asText()).isEqualTo("SAUVEGARDE");
        assertThat(alerts.get(0).get("closedAt").isNull()).isTrue();
        var later = body(mvc.perform(get("/admin/v1/supervision/alerts").param("since", Instant.now().plusSeconds(60).toString())
                .header("Authorization", "Bearer jeton-hebergeur")).andReturn());
        assertThat(later).isEmpty();
    }

    @Test
    void reglesDAlerte() throws Exception {
        var now = Instant.parse("2026-11-03T01:00:00Z"); // 12 h à Nouméa
        assertThat(AlertRules.evaluate(null, now.minusSeconds(3 * 3600), now)).containsKey("SILENCE");
        assertThat(AlertRules.evaluate(null, now.minusSeconds(3600), now)).isEmpty();
        var night = Instant.parse("2026-11-03T12:00:00Z"); // 23 h à Nouméa
        assertThat(AlertRules.evaluate(null, night.minusSeconds(6 * 3600), night)).as("12 h tolérées la nuit").isEmpty();
        var status = json.readTree("""
                {"license":{"state":"EXPIREE_GRACE","deadline":"2026-12-01"},"tablets":{"silent":2},
                 "portal":{"state":"ACTIF","failures":6,"lastError":"Portail injoignable"},
                 "disk":{"dataFreeBytes":53687091200,"dataTotalBytes":1099511627776}}""");
        var alerts = AlertRules.evaluate(status, now, now);
        assertThat(alerts).containsOnlyKeys("LICENCE", "TABLETTES", "PORTAIL", "DISQUE");
        assertThat(alerts.get("TABLETTES")).isEqualTo("2 tablettes muettes depuis 3 jours.");
        assertThat(alerts.get("DISQUE")).as("50 Go libres sur 1 To : moins de 10 %").contains("50,0 Go");
    }
}
