package nc.ticagou;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.auth.LoginThrottle;
import nc.ticagou.device.PairingThrottle;

/**
 * Base des tests d'intégration : application complète, PostgreSQL 17 (Testcontainers), base vidée
 * avant chaque test. Ignorés si Docker n'est pas disponible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    protected static final String DIRECTOR_LOGIN = "nathalie.kaloi";
    protected static final String DIRECTOR_PASSWORD = "Niaouli-2026!";

    /** Documents chiffrés des tests, hors du répertoire du projet. */
    protected static final Path STORAGE;

    /** Archives des sauvegardes. */
    protected static final Path BACKUPS;

    static {
        try {
            STORAGE = Files.createTempDirectory("ticagou-documents");
            BACKUPS = Files.createTempDirectory("ticagou-sauvegardes");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("ticagou.storage.dir", STORAGE::toString);
        registry.add("ticagou.backup.dir", BACKUPS::toString);
        registry.add("ticagou.scheduling.enabled", () -> "false");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private PairingThrottle pairingThrottle;

    @Autowired
    private LoginThrottle loginThrottle;

    @Autowired
    protected nc.ticagou.staffing.StaffingBroadcaster staffingBroadcaster;

    @BeforeEach
    void resetDatabase() throws IOException {
        // Une diffusion de l'encadrement du test précédent ne doit pas écrire après la remise à zéro.
        staffingBroadcaster.awaitPending();
        pairingThrottle.reset();
        loginThrottle.reset();
        try (var files = Files.list(BACKUPS)) {
            for (var file : files.toList()) {
                Files.delete(file);
            }
        }
        // TRUNCATE contourne les triggers « ajout seul » des journaux (déclenchés ligne à ligne).
        jdbc.execute("""
                TRUNCATE installation, structure, structure_opening_hours, section, app_user, refresh_token,
                         audit_event, child_record_access, structure_counter, stored_file, family, guardian,
                         child, authorized_person, child_document, health_info, allergy, vaccination, consent,
                         tariff_schedule, tariff_band, enrollment_criterion, enrollment_request, enrollment_request_day,
                         enrollment_request_criterion, enrollment_note, enrollment_event, contract, adaptation_session,
                         contract_version, contract_schedule_day, ratio_rule, device, child_attendance,
                         child_instruction, departure_override, staff_attendance, kiosk_operation,
                         attendance_correction, ratio_breach, diet, menu_week, menu_item, meal_adjustment,
                         meal_note, meal_quarter_closure, billing_rule, absence_reason, tgc_rate, child_absence,
                         funder, child_funding, invoice, invoice_line, credit_note, credit_note_line, invoice_event,
                         funder_request, funder_request_line, payment, payment_allocation, reminder, staff_profile,
                         staff_qualification, staff_training, staff_schedule, staff_schedule_day, staff_shift_override,
                         staff_absence, staff_settings, timesheet_month, backup_run, subscription, daily_entry,
                         daily_entry_child, portal_settings, portal_outbox, guardian_portal_access, health_share,
                         portal_document, reported_absence, family_message, desktop_client,
                         accounting_account, accounting_settings, accounting_party, monitoring_settings
                         RESTART IDENTITY CASCADE""");
        jdbc.update("""
                UPDATE backup_settings SET enabled = TRUE, keep_days = 7, keep_weeks = 4, keep_months = 12, last_test_on = NULL,
                       last_test_ok = NULL WHERE id = 1""");
    }

    // --- Données ---------------------------------------------------------------

    protected Map<String, Object> setupRequest() {
        var structure = new LinkedHashMap<String, Object>();
        structure.put("name", "Les P’tits Niaoulis");
        structure.put("city", "Dumbéa");
        structure.put("ridet", "1234567.001");
        structure.put("agrementNumber", "AGR-2024-017");
        structure.put("agrementDate", "2024-01-15");
        structure.put("capacity", 48);
        return Map.of(
                "structure", structure,
                "sections", List.of(
                        section("Bébés", 2, 18, 12, "#9e2f66", 0),
                        section("Moyens", 18, 30, 16, "#924510", 1),
                        section("Grands", 30, null, 20, "#3d479b", 2)),
                "admin", Map.of("firstName", "Nathalie", "lastName", "Kaloï", "login", DIRECTOR_LOGIN,
                        "password", DIRECTOR_PASSWORD));
    }

    protected static Map<String, Object> section(String name, int min, Integer max, int capacity, String color,
                                                 int order) {
        var section = new LinkedHashMap<String, Object>();
        section.put("name", name);
        section.put("minAgeMonths", min);
        section.put("maxAgeMonths", max);
        section.put("capacity", capacity);
        section.put("color", color);
        section.put("displayOrder", order);
        return section;
    }

    /** Installe la structure et renvoie le jeton d'accès de la directrice. */
    protected String install() throws Exception {
        var result = perform(post("/api/v1/setup"), null, setupRequest());
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Installation impossible : " + result.getResponse().getContentAsString());
        }
        return body(result).get("accessToken").asText();
    }

    protected JsonNode createUser(String token, String role, String login, String password, String pin)
            throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("firstName", "Prénom" + role);
        request.put("lastName", "Nom" + role);
        request.put("role", role);
        request.put("login", login);
        request.put("password", password);
        request.put("pin", pin);
        var result = perform(post("/api/v1/users"), token, request);
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Création impossible : " + result.getResponse().getContentAsString());
        }
        return body(result);
    }

    /** Crée un enfant dans une nouvelle famille « Famille {nom} » ; renvoie son identifiant. */
    protected String createChild(String token, String firstName, String lastName, String birthDate,
                                 String sectionId) throws Exception {
        return createChild(token, firstName, lastName, birthDate, sectionId, "2025-02-01");
    }

    protected String createChild(String token, String firstName, String lastName, String birthDate,
                                 String sectionId, String entryDate) throws Exception {
        var child = new LinkedHashMap<String, Object>();
        child.put("firstName", firstName);
        child.put("lastName", lastName);
        child.put("birthDate", birthDate);
        child.put("sectionId", sectionId);
        child.put("entryDate", entryDate);
        var guardian = new LinkedHashMap<String, Object>();
        guardian.put("firstName", "Sarah");
        guardian.put("lastName", lastName);
        guardian.put("relationship", "Mère");
        guardian.put("legalGuardian", true);
        guardian.put("payer", true);
        guardian.put("mobilePhone", "77 12 34");
        guardian.put("email", "sarah@exemple.nc");
        var request = Map.of("child", child, "family", Map.of(
                "family", Map.of("label", "Famille " + lastName, "city", "Nouméa"),
                "guardian", guardian));
        var result = perform(post("/api/v1/children"), token, request);
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Création impossible : " + result.getResponse().getContentAsString());
        }
        return body(result).get("id").asText();
    }

    protected String sectionId(String token, String name) throws Exception {
        for (var section : body(getAs(token, "/api/v1/sections"))) {
            if (section.get("name").asText().equals(name)) {
                return section.get("id").asText();
            }
        }
        throw new IllegalArgumentException(name);
    }

    protected long childAccessCount(String childId, String context) {
        return jdbc.queryForObject("SELECT count(*) FROM child_record_access WHERE child_id = ?::uuid AND context = ?",
                Long.class, childId, context);
    }

    protected JsonNode login(String login, String password) throws Exception {
        return body(perform(post("/api/v1/auth/login"), null, Map.of("login", login, "password", password)));
    }

    protected String accessToken(String login, String password) throws Exception {
        return login(login, password).get("accessToken").asText();
    }

    // --- HTTP --------------------------------------------------------------------

    protected MvcResult perform(MockHttpServletRequestBuilder request, String token, Object payload) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload));
        }
        return mvc.perform(request).andReturn();
    }

    protected MvcResult getAs(String token, String path) throws Exception {
        return perform(get(path), token, null);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    protected int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** Barème au volume horaire de la maquette Contrat (moins de 20 h, 20 à 35 h, plus de 35 h), sans date de fin. */
    protected String createVolumeTariff(String token, String validFrom) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("label", "Barème " + validFrom.substring(0, 4));
        request.put("criterion", "HEURES_SEMAINE");
        request.put("weeksPerYear", 47);
        request.put("registrationFee", 10_000);
        request.put("validFrom", validFrom);
        request.put("bands", List.of(Map.of("lowerBound", 0, "hourlyRate", 620),
                Map.of("lowerBound", 20 * 60, "hourlyRate", 560), Map.of("lowerBound", 35 * 60 + 15, "hourlyRate", 510)));
        request.put("formulas", List.of(Map.of("label", "Temps plein", "monthlyFee", 98_500),
                Map.of("label", "Matins seulement", "monthlyFee", 60_000)));
        var result = perform(post("/api/v1/tariff-schedules"), token, request);
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Barème impossible : " + result.getResponse().getContentAsString());
        }
        return body(result).get("id").asText();
    }

    /** Tablette appairée par la directrice : identifiant, secret et jeton d'appareil. */
    protected record Device(String id, String secret, String token) {
    }

    protected Device pairDevice(String directorToken, String name) throws Exception {
        var created = perform(post("/api/v1/devices"), directorToken, Map.of("name", name));
        if (created.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Appairage impossible : " + created.getResponse().getContentAsString());
        }
        var pair = body(perform(post("/api/v1/devices/pair"), null, Map.of("code", body(created).get("code").asText())));
        var id = pair.get("deviceId").asText();
        var secret = pair.get("secret").asText();
        return new Device(id, secret, deviceToken(id, secret));
    }

    protected String deviceToken(String deviceId, String secret) throws Exception {
        var session = perform(post("/api/v1/devices/session"), null, Map.of("deviceId", deviceId, "secret", secret));
        if (session.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Jeton d'appareil refusé : " + session.getResponse().getContentAsString());
        }
        return body(session).get("accessToken").asText();
    }

    /** Connexion PIN sur la tablette ; renvoie la réponse de session (jeton, sessionId, utilisateur). */
    protected JsonNode pinLogin(Device device, String userId, String pin) throws Exception {
        var result = perform(post("/api/v1/auth/pin"), device.token(), Map.of("userId", userId, "pin", pin));
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Connexion PIN refusée : " + result.getResponse().getContentAsString());
        }
        return body(result);
    }

    protected long auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Long.class, action);
    }
}
