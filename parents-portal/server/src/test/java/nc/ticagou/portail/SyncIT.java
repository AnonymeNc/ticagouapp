package nc.ticagou.portail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Synchronisation signée avec les serveurs des crèches (ADR 0010), d'après les exemples de parents-portal/contract. */
class SyncIT extends PortalTest {

    private static final UUID FAMILY = UUID.fromString("0b9a3c1e-0000-4000-8000-000000000100");

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void codeDActivationAUsageUnique() throws Exception {
        var code = sync.newActivationCode("Les P'tits Cagous");
        var request = Map.of("activationCode", code, "structureName", "Les P'tits Cagous", "publicKey",
                Base64.getEncoder().encodeToString(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded()));
        var first = mvc.perform(post("/sync/v1/activate").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andReturn();
        assertThat(status(first)).isEqualTo(200);
        var again = mvc.perform(post("/sync/v1/activate").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andReturn();
        assertThat(status(again)).isEqualTo(403);
        assertThat(body(again).get("code").asText()).isEqualTo("activation_code");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_code WHERE code_hash = ?", Long.class, code)).isZero();
    }

    @Test
    void lotDuContratAppliqueEtRejoueSansDoublon() throws Exception {
        var creche = activate("Les P'tits Cagous");
        var batch = fixture("batch.json");
        var result = batch(creche, batch);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("acceptedUpTo").asLong()).isEqualTo(fixture("batch-response.json").get("acceptedUpTo").asLong());

        assertThat(jdbc.queryForObject("SELECT phone FROM portal_structure", String.class)).isEqualTo("27 12 34");
        assertThat(jdbc.queryForObject("SELECT email FROM guardian", String.class)).isEqualTo("marie.wamytan@example.nc");
        assertThat(jdbc.queryForObject("SELECT shared_health->0->>'text' FROM child", String.class)).contains("PAI");
        assertThat(jdbc.queryForList("SELECT type FROM item ORDER BY type", String.class))
                .containsExactly("ATTENDANCE", "BOOKING", "DOCUMENT", "ENTRY", "INVOICE", "MESSAGE", "NEWS");
        // Actualité : affichée jusqu'à sa date de fin, pièce jointe chiffrée, nom du fichier gardé.
        assertThat(jdbc.queryForObject("SELECT expires_on FROM item WHERE type = 'NEWS'", java.sql.Date.class).toLocalDate())
                .isEqualTo(java.time.LocalDate.parse("2026-11-08"));
        assertThat(jdbc.queryForObject("SELECT payload::text FROM item WHERE type = 'NEWS'", String.class))
                .contains("menu-semaine-45.pdf").doesNotContain("JVBER", "familyIds");
        assertThat(jdbc.queryForObject("SELECT file_type FROM item WHERE type = 'NEWS'", String.class)).isEqualTo("application/pdf");
        // Fichiers chiffrés au repos : le PDF n'apparaît pas en clair.
        var stored = jdbc.queryForObject("SELECT file FROM item WHERE type = 'INVOICE'", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("%PDF");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM item WHERE type = 'INVOICE'", String.class))
                .doesNotContain("JVBER").contains("FA-2026-0042");

        // Même lot renvoyé (coupure) : rien de plus.
        var signedAgain = batch(creche, batch);
        assertThat(status(signedAgain)).isEqualTo(200);
        assertThat(count("item")).isEqualTo(7);
        assertThat(count("item_family")).isEqualTo(7);
    }

    @Test
    void signatureFausseDateAncienneEtRejeuRefuses() throws Exception {
        var creche = activate("Crèche A");
        var body = json.writeValueAsBytes(Map.of("items", List.of()));
        var now = Instant.now();
        assertThat(status(signed(creche, "POST", "/sync/v1/batch", body, now))).isEqualTo(200);
        // Rejeu de la requête identique (même date, même signature).
        var replay = signed(creche, "POST", "/sync/v1/batch", body, now);
        assertThat(status(replay)).isEqualTo(401);
        assertThat(body(replay).get("code").asText()).isEqualTo("replay");
        var stale = signed(creche, "POST", "/sync/v1/batch", body, now.minusSeconds(600));
        assertThat(body(stale).get("code").asText()).isEqualTo("clock_skew");

        var impostor = new Creche(creche.id(), java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        var forged = signed(impostor, "POST", "/sync/v1/batch", body, Instant.now());
        assertThat(status(forged)).isEqualTo(401);
        assertThat(body(forged).get("code").asText()).isEqualTo("bad_signature");
        assertThat(status(mvc.perform(post("/sync/v1/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn())).isEqualTo(401);
    }

    @Test
    void structuresIsoleesEtEffacementParFamillePuisStructure() throws Exception {
        var a = activate("Crèche A");
        var b = activate("Crèche B");
        batch(a, fixture("batch.json"));
        batch(b, fixture("batch.json"));
        assertThat(count("guardian")).isEqualTo(2);

        // La crèche B ne lit pas la boîte de A.
        sync.deposit(a.id(), "MESSAGE", FAMILY, Map.of("body", "Bonjour"));
        assertThat(body(signed(b, "GET", "/sync/v1/inbox?after=0", new byte[0], Instant.now())).get("items")).isEmpty();
        var inbox = body(signed(a, "GET", "/sync/v1/inbox?after=0", new byte[0], Instant.now())).get("items");
        assertThat(inbox).hasSize(1);
        var ack = json.writeValueAsBytes(Map.of("upTo", inbox.get(0).get("seq").asLong()));
        assertThat(status(signed(a, "POST", "/sync/v1/inbox/ack", ack, Instant.now()))).isEqualTo(204);
        assertThat(count("inbox")).isZero();

        batch(a, Map.of("items", List.of(Map.of("seq", 13, "type", "PURGE_FAMILY", "id", FAMILY, "deleted", true))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guardian WHERE structure_id = ?", Long.class, a.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE structure_id = ?", Long.class, a.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE structure_id = ?", Long.class, b.id())).isEqualTo(7);

        batch(b, Map.of("items", List.of(Map.of("seq", Long.MAX_VALUE, "type", "PURGE_STRUCTURE", "id", b.id(), "deleted", true))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_structure WHERE id = ?", Long.class, b.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE structure_id = ?", Long.class, b.id())).isZero();
        // Plus de clé : la crèche effacée ne peut plus rien envoyer.
        assertThat(status(batch(b, Map.of("items", List.of())))).isEqualTo(401);
    }

    @Test
    void suppressionEtRetention() throws Exception {
        var creche = activate("Crèche A");
        batch(creche, fixture("batch.json"));
        var entry = UUID.fromString("0b9a3c1e-0000-4000-8000-000000003000");
        batch(creche, Map.of("items", List.of(Map.of("seq", 20, "type", "ENTRY", "id", entry, "deleted", true))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE id = ?", Long.class, entry)).isZero();

        // L'actualité de l'exemple a une date de fin déjà passée : seule la présence doit expirer ici.
        jdbc.update("UPDATE item SET expires_on = current_date + 1 WHERE type = 'NEWS'");
        jdbc.update("UPDATE item SET expires_on = current_date - 1 WHERE type = 'ATTENDANCE'");
        jdbc.update("INSERT INTO login_request (id, email, token_hash, code_hash, created_at, expires_at) "
                + "VALUES (gen_random_uuid(), 'x@y.nc', 'a', 'b', now() - interval '2 days', now() - interval '2 days')");
        assertThat(new nc.ticagou.portail.retention.RetentionJob(jdbc, null, java.time.Clock.systemUTC()).run()).isEqualTo(1);
        assertThat(count("login_request")).isZero();
    }
}
