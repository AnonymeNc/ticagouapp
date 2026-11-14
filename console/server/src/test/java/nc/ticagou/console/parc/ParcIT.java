package nc.ticagou.console.parc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import nc.ticagou.console.auth.CurrentOperator;

/** Parc des crèches : supervision et hôte rapprochés par slug, vue du parc, fiche, notes, prospects, instantanés. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ParcIT.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class ParcIT {

    static final Instant NOW = Instant.parse("2026-11-09T21:00:00Z"); // 10/11/2026 08:00 à Nouméa

    /** Faux portail : l'API d'administration de la supervision. */
    static final HttpServer PORTAL;
    static volatile String sites = "[]";
    static volatile String alerts = "[]";
    static final List<String> SINCE = new CopyOnWriteArrayList<>();

    static {
        try {
            PORTAL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        PORTAL.createContext("/admin/v1/supervision", exchange -> {
            var authorized = "Bearer jeton-portail".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            var path = exchange.getRequestURI().getPath();
            byte[] body;
            if (!authorized) {
                body = "{\"code\":\"admin_token\"}".getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/alerts")) {
                SINCE.add(exchange.getRequestURI().getQuery());
                body = alerts.getBytes(StandardCharsets.UTF_8);
            } else {
                body = sites.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        PORTAL.start();
    }

    @AfterAll
    static void stop() {
        PORTAL.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("console.portal-url", () -> "http://127.0.0.1:" + PORTAL.getAddress().getPort());
        registry.add("console.portal-admin-token", () -> "jeton-portail");
        registry.add("console.host-token", () -> "jeton-hote");
        registry.add("console.scheduling", () -> "false");
        registry.add("console.secret-key", () -> "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    static final class FixedClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Pacific/Noumea");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }

        @Bean
        @Primary
        FixedClock fixedClock() {
            return new FixedClock();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FixedClock clock;
    @Autowired SupervisionCollector collector;
    @Autowired Snapshots snapshots;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE creche_snapshot, alert_history, creche_note, creche, client, audit_event CASCADE");
        jdbc.update("UPDATE collector SET last_attempt_at = NULL, last_success_at = NULL, last_error = NULL, cursor_at = NULL, data = NULL");
        clock.now = NOW;
        sites = "[]";
        alerts = "[]";
        SINCE.clear();
    }

    static RequestPostProcessor operator() {
        var principal = new CurrentOperator.Principal(UUID.randomUUID(), "jordan", "Jordan Moniot");
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    MvcResult call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(operator()).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(request).andReturn();
    }

    JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    int host(String token, Object report) throws Exception {
        var request = post("/interne/v1/hote").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(report));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    static String site(String slug, String name, String seenAt, String version) {
        return """
                {"id":"%s","name":"%s","slug":%s,"activatedAt":"2026-01-01T00:00:00Z","lastSeenAt":"%s","suspended":false,
                 "version":"%s","status":{"version":"%s","database":"UP",
                   "backup":{"enabled":true,"toolsAvailable":true,"stale":true,"lastSuccessAt":"2026-11-07T16:41:00Z"},
                   "disk":{"dataFreeBytes":18000000000,"dataTotalBytes":50000000000},
                   "license":{"state":"EXPIREE_GRACE","validUntil":"2026-10-31","deadline":"2026-11-30","desktops":1,"desktopLimit":1},
                   "tablets":{"paired":2,"silent":0},"portal":{"state":"DESACTIVE","failures":0,"pending":0},
                   "attendanceToReview":0,
                   "usage":{"activeChildren":12,"places":12,"activeUsers30d":5,"lastDirectionLoginOn":"2026-11-08",
                            "attendances7d":118,"familyInvoicesLastMonth":12}},
                 "alerts":[]}""".formatted(UUID.randomUUID(), name, slug == null ? "null" : "\"" + slug + "\"", seenAt, version, version);
    }

    static Map<String, Object> hostEntry(String slug, String container, String image) {
        return Map.of("slug", slug, "container", container, "image", image, "domain", slug + ".ticagou.nc", "hiddenSections", "COMPTABILITE,REPAS", "directoryCode", "NK7Q2M",
                "subscription", Map.of("number", "A-12", "plan", "Ti’Cagou — jusqu’à 12 places", "places", 12, "desktops", 1,
                        "tablets", 2, "validUntil", "2026-10-31"),
                "secret", "ne doit pas être gardé");
    }

    @Test
    void supervisionEtHoteRapprocheesParSlug() throws Exception {
        sites = "[" + site("lagon-bleu", "Micro-crèche Lagon Bleu", "2026-11-09T20:45:00Z", "1.5.0") + ","
                + site(null, "Ancienne crèche", "2026-11-09T20:45:00Z", "1.5.0") + "]";
        alerts = """
                [{"id":7,"slug":"lagon-bleu","kind":"SAUVEGARDE","detail":"Dernière sauvegarde réussie le 07/11/2026 à 03:41.",
                  "openedAt":"2026-11-08T16:45:00Z","closedAt":null},
                 {"id":3,"slug":"lagon-bleu","kind":"TABLETTES","detail":"1 tablette muette depuis 3 jours.",
                  "openedAt":"2026-09-14T09:15:00Z","closedAt":"2026-09-16T07:30:00Z"},
                 {"id":9,"slug":"inconnue","kind":"DISQUE","detail":"?","openedAt":"2026-11-08T16:45:00Z","closedAt":null}]""";

        assertThat(host(null, Map.of("creches", List.of()))).isEqualTo(401);
        assertThat(host("faux", Map.of("creches", List.of()))).isEqualTo(401);
        assertThat(host("jeton-hote", Map.of("creches", List.of(hostEntry("lagon-bleu", "healthy", "1.5.0"),
                hostEntry("niaoulis", "healthy", "1.4.2")), "disk", Map.of("freeBytes", 100, "totalBytes", 400)))).isEqualTo(204);
        collector.collect();

        assertThat(mvc.perform(get("/api/v1/parc")).andReturn().getResponse().getStatus()).as("connexion exigée").isEqualTo(401);
        var parc = read(call(get("/api/v1/parc"), null));
        assertThat(parc.at("/creches").size()).isEqualTo(2);
        var lagon = parc.at("/creches/0");
        assertThat(lagon.get("slug").asText()).isEqualTo("lagon-bleu");
        assertThat(lagon.get("name").asText()).as("nom repris de la supervision").isEqualTo("Micro-crèche Lagon Bleu");
        assertThat(lagon.at("/health/level").asText()).isEqualTo("BAD");
        assertThat(lagon.at("/health/reasons/0").asText()).isEqualTo("Sauvegarde");
        assertThat(lagon.get("plan").asText()).isEqualTo("Ti’Cagou");
        assertThat(lagon.has("modules")).isFalse();
        assertThat(lagon.get("licenseState").asText()).isEqualTo("EXPIREE_GRACE");
        assertThat(lagon.get("diskUsedPercent").asInt()).isEqualTo(64);
        assertThat(lagon.get("tabletLimit").asInt()).isEqualTo(2);
        assertThat(lagon.get("alerts").size()).as("l'alerte fermée n'est pas en cours").isEqualTo(1);
        var niaoulis = parc.at("/creches/1");
        assertThat(niaoulis.get("name").asText()).as("connue de l'hôte seulement : son slug").isEqualTo("niaoulis");
        assertThat(niaoulis.at("/health/level").asText()).isEqualTo("WARN");
        assertThat(niaoulis.at("/health/reasons/0").asText()).isEqualTo("Aucun état de supervision reçu");
        assertThat(niaoulis.get("version").asText()).isEqualTo("1.4.2");
        assertThat(parc.at("/summary/bad").asInt()).isEqualTo(1);
        assertThat(parc.at("/summary/warn").asInt()).isEqualTo(1);
        assertThat(parc.at("/summary/renewals").asInt()).as("abonnement en grâce").isEqualTo(1);
        assertThat(parc.at("/summary/versionsBehind").asInt()).isEqualTo(1);
        assertThat(parc.at("/sources/unlinkedSites/0").asText()).isEqualTo("Ancienne crèche");
        assertThat(parc.at("/sources/hostDiskTotal").asLong()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT host::text FROM creche WHERE slug = 'lagon-bleu'", String.class))
                .as("champs inconnus de l'hôte écartés").doesNotContain("secret");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'CRECHE_ENREGISTREE'", Integer.class)).isEqualTo(2);

        // Même état relu : pas de nouvel instantané ; état suivant : un de plus.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creche_snapshot", Integer.class)).isEqualTo(1);
        clock.now = NOW.plus(Duration.ofMinutes(5));
        collector.collect();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creche_snapshot", Integer.class)).isEqualTo(1);
        assertThat(SINCE).hasSize(2);
        assertThat(SINCE.get(0)).isEqualTo("since=1970-01-01T00:00:00Z");
        assertThat(SINCE.get(1)).as("depuis la lecture précédente, moins une minute").isEqualTo("since=2026-11-09T20:59:00Z");
        sites = "[" + site("lagon-bleu", "Micro-crèche Lagon Bleu", "2026-11-09T21:00:00Z", "1.5.0") + "]";
        collector.collect();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creche_snapshot", Integer.class)).isEqualTo(2);

        // L'hôte ne cite plus niaoulis : instance absente, pastille grise.
        assertThat(host("jeton-hote", Map.of("creches", List.of(hostEntry("lagon-bleu", "healthy", "1.5.0"))))).isEqualTo(204);
        var after = read(call(get("/api/v1/parc"), null));
        assertThat(after.at("/creches/1/health/level").asText()).isEqualTo("OFF");
        assertThat(after.at("/creches/1/container").asText()).isEqualTo("absent");

        // Fiche : historique des alertes, courbe, état complet.
        var detail = read(call(get("/api/v1/parc/lagon-bleu"), null));
        assertThat(detail.get("history").size()).isEqualTo(2);
        assertThat(detail.at("/history/0/label").asText()).isEqualTo("Sauvegarde");
        assertThat(detail.get("points").size()).isEqualTo(1);
        assertThat(detail.at("/points/0/activeChildren").asInt()).isEqualTo(12);
        assertThat(detail.at("/status/usage/attendances7d").asInt()).isEqualTo(118);
        assertThat(detail.get("domain").asText()).isEqualTo("lagon-bleu.ticagou.nc");
        assertThat(detail.get("hiddenSections").toString()).as("rubriques masquées (ADR 0022)").isEqualTo("[\"COMPTABILITE\",\"REPAS\"]");
        assertThat(detail.get("directoryCode").asText()).as("code de l’annuaire (ADR 0025)").isEqualTo("NK7Q2M");
        assertThat(call(get("/api/v1/parc/inconnue"), null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void unSiteIllisibleNEmpechePasDeLireLesAutres() throws Exception {
        var broken = site("niaoulis", "Les Niaoulis", "2026-11-09T20:45:00Z", "1.5.0").replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"pas-un-uuid\"");
        sites = "[" + broken + "," + site("lagon-bleu", "Lagon Bleu", "2026-11-09T20:45:00Z", "1.5.0") + "]";
        collector.collect();
        var parc = read(call(get("/api/v1/parc"), null));
        assertThat(parc.get("creches").size()).isEqualTo(1);
        assertThat(parc.at("/creches/0/slug").asText()).isEqualTo("lagon-bleu");
        assertThat(parc.at("/sources/rejectedSites/0").asText()).isEqualTo("niaoulis");
        assertThat(parc.at("/sources/supervisionError").isNull()).isTrue();
    }

    @Test
    void portailInjoignableNoteSansRienEffacer() throws Exception {
        sites = "[" + site("lagon-bleu", "Lagon Bleu", "2026-11-09T20:45:00Z", "1.5.0") + "]";
        collector.collect();
        sites = "pas du json";
        clock.now = NOW.plus(Duration.ofMinutes(5));
        collector.collect();
        var parc = read(call(get("/api/v1/parc"), null));
        assertThat(parc.get("creches").size()).isEqualTo(1);
        assertThat(parc.at("/sources/supervisionError").asText()).startsWith("Portail injoignable");
        assertThat(Instant.parse(parc.at("/sources/supervisionAt").asText())).isEqualTo(NOW);
    }

    @Test
    void ficheNotesProspectRechercheEtJournal() throws Exception {
        assertThat(host("jeton-hote", Map.of("creches", List.of(hostEntry("lagon-bleu", "healthy", "1.5.0"))))).isEqualTo(204);

        var created = call(post("/api/v1/parc"), Map.of("slug", "kids-club-bourail", "name", "Kids Club Bourail", "city", "Bourail"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(created).at("/row/lifecycle").asText()).isEqualTo("PROSPECT");
        assertThat(read(created).at("/row/health/level").asText()).isEqualTo("OFF");
        assertThat(call(post("/api/v1/parc"), Map.of("slug", "kids-club-bourail", "name", "Autre")).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(post("/api/v1/parc"), Map.of("slug", "Mauvais Slug", "name", "Autre")).getResponse().getStatus()).isEqualTo(400);

        var client = new java.util.HashMap<String, Object>(Map.of("name", "SARL Lagon Bleu", "kind", "PRIVE", "ridet", "1234567.001",
                "directorName", "Nathalie Kaloï", "directorEmail", "direction@lagon-bleu.nc", "billingEmail", "compta@lagon-bleu.nc",
                "paymentDays", 30));
        var fiche = new java.util.HashMap<String, Object>(Map.of("name", "Micro-crèche Lagon Bleu", "city", "Mont-Dore",
                "lifecycle", "CLIENT", "clientSince", "2025-11-01", "client", client));
        var updated = call(put("/api/v1/parc/lagon-bleu"), fiche);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(updated).at("/client/ridet").asText()).isEqualTo("1234567.001");
        assertThat(read(updated).at("/row/city").asText()).isEqualTo("Mont-Dore");
        var detail = jdbc.queryForObject("SELECT detail::text FROM audit_event WHERE action = 'FICHE_MODIFIEE'", String.class);
        assertThat(detail).contains("\"ridet\"", "\"apres\": \"1234567.001\"").doesNotContain("delaiPaiement");

        client.put("billingEmail", "pas-un-email");
        var invalid = call(put("/api/v1/parc/lagon-bleu"), fiche);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(invalid).get("detail").asText()).isEqualTo("E-mail de facturation invalide.");

        clock.now = NOW.plus(Duration.ofMinutes(2));
        var noted = read(call(post("/api/v1/parc/lagon-bleu/notes"), Map.of("kind", "APPEL", "text", "Relance du renouvellement, rappeler lundi.")));
        assertThat(noted.at("/notes/0/text").asText()).startsWith("Relance");
        assertThat(noted.at("/notes/0/operatorLogin").asText()).isEqualTo("jordan");
        assertThat(Instant.parse(noted.at("/row/lastCallAt").asText())).isEqualTo(clock.now);
        assertThat(noted.at("/journal/0/action").asText()).isEqualTo("APPEL_NOTE");
        assertThat(noted.at("/journal/1/action").asText()).isEqualTo("FICHE_MODIFIEE");

        var hits = read(call(get("/api/v1/recherche").param("q", "creche lagon"), null));
        assertThat(hits.size()).as("sans accent, nom de la crèche").isEqualTo(1);
        assertThat(read(call(get("/api/v1/recherche").param("q", "sarl"), null)).get(0).get("slug").asText()).as("raison sociale")
                .isEqualTo("lagon-bleu");
        assertThat(read(call(get("/api/v1/recherche").param("q", "b"), null)).size()).as("une lettre : rien").isZero();
    }

    @Test
    void instantanesReduitsAUnParJourApres30Jours() throws Exception {
        assertThat(host("jeton-hote", Map.of("creches", List.of(hostEntry("lagon-bleu", "healthy", "1.5.0"))))).isEqualTo(204);
        var id = jdbc.queryForObject("SELECT id FROM creche WHERE slug = 'lagon-bleu'", UUID.class);
        // 40 jours avant : trois états le même jour à Nouméa ; 10 jours avant : deux états.
        var old = NOW.minus(Duration.ofDays(40));
        for (var at : List.of(old, old.plus(Duration.ofHours(1)), old.plus(Duration.ofHours(2)),
                NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)).plus(Duration.ofMinutes(15)))) {
            jdbc.update("INSERT INTO creche_snapshot (creche_id, at, data) VALUES (?, ?, '{\"dataFree\":1,\"dataTotal\":4}'::jsonb)",
                    id, Timestamp.from(at));
        }
        assertThat(snapshots.reduce()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT at FROM creche_snapshot ORDER BY at", Timestamp.class))
                .extracting(Timestamp::toInstant)
                .containsExactly(old.plus(Duration.ofHours(2)), NOW.minus(Duration.ofDays(10)),
                        NOW.minus(Duration.ofDays(10)).plus(Duration.ofMinutes(15)));
        var points = read(call(get("/api/v1/parc/lagon-bleu"), null)).get("points");
        assertThat(points.size()).as("un point par jour").isEqualTo(2);
        assertThat(points.get(0).get("diskUsedPercent").asInt()).isEqualTo(75);
    }
}
