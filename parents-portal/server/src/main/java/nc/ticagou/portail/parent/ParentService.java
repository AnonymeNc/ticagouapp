package nc.ticagou.portail.parent;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.TimeConfig;
import nc.ticagou.portail.auth.AuthService;
import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.FileCrypto;
import nc.ticagou.portail.common.Hashes;
import nc.ticagou.portail.sync.SyncService;

/**
 * Ce qu'un parent voit et envoie. Chaque lecture est restreinte aux familles rattachées à l'adresse de la session
 * (un même parent peut avoir des enfants dans deux crèches) ; un identifiant d'une autre famille répond « introuvable ».
 */
@Service
public class ParentService {

    /** Famille accessible : structure, famille, responsable. */
    public record Access(UUID structureId, UUID familyId, UUID guardianId, String firstName, String lastName) {
    }

    public record Content(byte[] bytes, String type) {
    }

    private final JdbcTemplate jdbc;
    private final FileCrypto crypto;
    private final SyncService sync;
    private final ObjectMapper json;
    private final Clock clock;

    public ParentService(JdbcTemplate jdbc, FileCrypto crypto, SyncService sync, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.sync = sync;
        this.json = json;
        this.clock = clock;
    }

    public List<Access> accesses(String email) {
        return jdbc.query("SELECT g.structure_id, g.family_id, g.id, g.first_name, g.last_name FROM guardian g "
                        + "JOIN portal_structure s ON s.id = g.structure_id WHERE lower(g.email) = lower(?) AND "
                        + AuthService.OPEN_STRUCTURE + " ORDER BY s.name", (rs, i) -> new Access(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5)), email,
                java.sql.Date.valueOf(LocalDate.now(clock.withZone(TimeConfig.NOUMEA))));
    }

    private Access access(String email, UUID structureId, UUID familyId) {
        return accesses(email).stream().filter(a -> a.structureId().equals(structureId) && a.familyId().equals(familyId))
                .findFirst().orElseThrow(() -> ApiException.notFound("Introuvable."));
    }

    /** Famille de l'enfant, si l'enfant appartient à une famille de la session. */
    private Access childAccess(String email, UUID structureId, UUID childId) {
        var families = jdbc.queryForList("SELECT family_id FROM child WHERE structure_id = ? AND id = ?", UUID.class,
                structureId, childId);
        if (families.isEmpty()) {
            throw ApiException.notFound("Enfant introuvable.");
        }
        return access(email, structureId, families.getFirst());
    }

    // --- Profil -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> me(String email) {
        var families = new ArrayList<Map<String, Object>>();
        for (var a : accesses(email)) {
            var structure = jdbc.queryForMap("SELECT name, phone, payment_info, meal_absence_cutoff FROM portal_structure WHERE id = ?",
                    a.structureId());
            var children = jdbc.query("SELECT id, first_name, last_initial, section, photo IS NOT NULL AS has_photo, shared_health "
                    + "FROM child WHERE structure_id = ? AND family_id = ? ORDER BY first_name", (rs, i) -> {
                        var child = new LinkedHashMap<String, Object>();
                        child.put("id", rs.getObject("id", UUID.class));
                        child.put("firstName", rs.getString("first_name"));
                        child.put("lastInitial", rs.getString("last_initial"));
                        child.put("section", rs.getString("section"));
                        child.put("hasPhoto", rs.getBoolean("has_photo"));
                        child.put("sharedHealth", read(rs.getString("shared_health")));
                        return child;
                    }, a.structureId(), a.familyId());
            var family = new LinkedHashMap<String, Object>();
            family.put("structureId", a.structureId());
            family.put("structureName", structure.get("name"));
            family.put("structurePhone", structure.get("phone"));
            family.put("paymentInfo", structure.get("payment_info"));
            // Vide : la crèche ne pratique pas la non-facturation du repas pour une absence signalée tôt.
            family.put("mealAbsenceCutoff", structure.get("meal_absence_cutoff"));
            family.put("familyId", a.familyId());
            family.put("guardianId", a.guardianId());
            family.put("firstName", a.firstName());
            family.put("children", children);
            family.put("unreadMessages", count(a, "MESSAGE", "AND (i.payload->>'fromFamily')::boolean = false "
                    + "AND (i.payload->>'readAt') IS NULL"));
            family.put("documentsToSign", count(a, "DOCUMENT", "AND i.payload->>'status' = 'A_SIGNER'"));
            families.add(family);
        }
        return Map.of("email", email, "families", families);
    }

    private long count(Access a, String type, String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM item i JOIN item_family f ON f.structure_id = i.structure_id "
                + "AND f.item_id = i.id WHERE i.structure_id = ? AND f.family_id = ? AND i.type = ? " + condition,
                Long.class, a.structureId(), a.familyId(), type);
    }

    public Content childPhoto(String email, UUID structureId, UUID childId) {
        childAccess(email, structureId, childId);
        var rows = jdbc.query("SELECT photo, photo_type FROM child WHERE structure_id = ? AND id = ? AND photo IS NOT NULL",
                (rs, i) -> new Content(crypto.decrypt(structureId, childId, rs.getBytes(1)), rs.getString(2)),
                structureId, childId);
        return rows.stream().findFirst().orElseThrow(() -> ApiException.notFound("Pas de photo."));
    }

    // --- Journée ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> day(String email, UUID structureId, UUID childId, LocalDate day) {
        childAccess(email, structureId, childId);
        var date = day == null ? LocalDate.now(clock.withZone(TimeConfig.NOUMEA)) : day;
        var items = jdbc.query("SELECT i.id, i.type, i.at, i.payload, i.file IS NOT NULL AS has_file FROM item i "
                + "JOIN item_child c ON c.structure_id = i.structure_id AND c.item_id = i.id "
                + "WHERE i.structure_id = ? AND c.child_id = ? AND i.day = ? AND i.type IN ('ATTENDANCE', 'ENTRY') "
                + "ORDER BY i.at", (rs, i) -> row(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getTimestamp("at"), rs.getString("payload"), rs.getBoolean("has_file")), structureId, childId, Date.valueOf(date));
        var days = jdbc.queryForList("SELECT DISTINCT i.day FROM item i JOIN item_child c ON c.structure_id = i.structure_id "
                + "AND c.item_id = i.id WHERE i.structure_id = ? AND c.child_id = ? AND i.type IN ('ATTENDANCE', 'ENTRY') "
                + "ORDER BY i.day DESC LIMIT 60", LocalDate.class, structureId, childId);
        return Map.of("day", date, "items", items, "days", days);
    }

    // --- Factures, documents ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> items(String email, String type) {
        var result = new ArrayList<Map<String, Object>>();
        for (var a : accesses(email)) {
            result.addAll(jdbc.query("SELECT i.id, i.type, i.at, i.payload, i.file IS NOT NULL AS has_file, i.day FROM item i "
                    + "JOIN item_family f ON f.structure_id = i.structure_id AND f.item_id = i.id "
                    + "WHERE i.structure_id = ? AND f.family_id = ? AND i.type = ? ORDER BY i.day DESC NULLS LAST, i.updated_at DESC",
                    (rs, i) -> {
                        var row = row(rs.getObject("id", UUID.class), rs.getString("type"), rs.getTimestamp("at"),
                                rs.getString("payload"), rs.getBoolean("has_file"));
                        row.put("structureId", a.structureId());
                        row.put("familyId", a.familyId());
                        return row;
                    }, a.structureId(), a.familyId(), type));
        }
        return result;
    }

    /** Fichier d'un élément (photo, PDF) rattaché à une famille de la session. */
    public Content file(String email, UUID structureId, UUID itemId) {
        var families = jdbc.queryForList("SELECT family_id FROM item_family WHERE structure_id = ? AND item_id = ?",
                UUID.class, structureId, itemId);
        var allowed = accesses(email).stream().anyMatch(a -> a.structureId().equals(structureId) && families.contains(a.familyId()));
        if (!allowed) {
            throw ApiException.notFound("Introuvable.");
        }
        var rows = jdbc.query("SELECT file, file_type FROM item WHERE structure_id = ? AND id = ? AND file IS NOT NULL",
                (rs, i) -> new Content(crypto.decrypt(structureId, itemId, rs.getBytes(1)), rs.getString(2)), structureId, itemId);
        return rows.stream().findFirst().orElseThrow(() -> ApiException.notFound("Pas de fichier."));
    }

    /**
     * Signature électronique simple : nom saisi, consentement coché, empreinte du PDF effectivement servi. Remontée à
     * la crèche, qui l'enregistre.
     */
    @Transactional
    public Map<String, Object> sign(String email, UUID structureId, UUID documentId, boolean refused, String ip) {
        var families = jdbc.queryForList("SELECT family_id FROM item_family WHERE structure_id = ? AND item_id = ?",
                UUID.class, structureId, documentId);
        var access = accesses(email).stream()
                .filter(a -> a.structureId().equals(structureId) && families.contains(a.familyId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Document introuvable."));
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) read(jdbc.queryForObject(
                "SELECT payload FROM item WHERE structure_id = ? AND id = ? AND type = 'DOCUMENT' FOR UPDATE", String.class,
                structureId, documentId));
        if (refused && "CONTRAT".equals(payload.path("kind").asText())) {
            throw ApiException.rule("contract_refusal", "Un contrat ne se refuse pas ici : parlez-en à la direction.");
        }
        if (!"A_SIGNER".equals(payload.path("status").asText())) {
            throw ApiException.rule("already_signed", "Ce document est déjà signé.");
        }
        var pdf = file(email, structureId, documentId).bytes();
        var sha = Hashes.sha256(pdf);
        if (!sha.equals(payload.path("sha256").asText())) {
            throw ApiException.rule("document_altered", "Le document ne correspond pas à celui de la crèche.");
        }
        var now = clock.instant();
        var signature = new LinkedHashMap<String, Object>();
        signature.put("documentId", documentId);
        signature.put("guardianId", access.guardianId());
        var signerName = access.firstName() + " " + access.lastName();
        var decision = refused ? "REFUSE" : "ACCEPTE";
        signature.put("signerName", signerName);
        signature.put("decision", decision);
        signature.put("signedAt", now);
        signature.put("ip", ip);
        signature.put("sha256", sha);
        sync.deposit(structureId, "SIGNATURE", access.familyId(), signature);
        payload.put("status", refused ? "REFUSE" : "SIGNE");
        payload.put("signedAt", now.toString());
        payload.put("signerName", signerName);
        jdbc.update("UPDATE item SET payload = ?::jsonb, updated_at = ? WHERE structure_id = ? AND id = ?",
                payload.toString(), Timestamp.from(now), structureId, documentId);
        return Map.of("status", payload.get("status").asText(), "signedAt", now);
    }

    // --- Messages -----------------------------------------------------------------------------

    @Transactional
    public List<Map<String, Object>> messages(String email, UUID structureId, UUID familyId) {
        access(email, structureId, familyId);
        var list = jdbc.query("SELECT i.id, i.type, i.at, i.payload, false AS has_file FROM item i "
                + "JOIN item_family f ON f.structure_id = i.structure_id AND f.item_id = i.id "
                + "WHERE i.structure_id = ? AND f.family_id = ? AND i.type = 'MESSAGE' ORDER BY i.at",
                (rs, i) -> row(rs.getObject("id", UUID.class), rs.getString("type"), rs.getTimestamp("at"),
                        rs.getString("payload"), false), structureId, familyId);
        // Lus à l'ouverture du fil.
        jdbc.update("UPDATE item i SET payload = jsonb_set(payload, '{readAt}', to_jsonb(?::text)) FROM item_family f "
                + "WHERE f.structure_id = i.structure_id AND f.item_id = i.id AND i.structure_id = ? AND f.family_id = ? "
                + "AND i.type = 'MESSAGE' AND (i.payload->>'fromFamily')::boolean = false AND i.payload->>'readAt' IS NULL",
                clock.instant().toString(), structureId, familyId);
        return list;
    }

    @Transactional
    public Map<String, Object> send(String email, UUID structureId, UUID familyId, String body) {
        var access = access(email, structureId, familyId);
        var now = clock.instant();
        var text = body.strip();
        sync.deposit(structureId, "MESSAGE", familyId, Map.of("guardianId", access.guardianId(), "body", text, "sentAt", now));
        var id = UUID.randomUUID();
        var payload = Map.of("author", access.firstName(), "body", text, "sentAt", now.toString(), "fromFamily", true);
        store(structureId, id, "MESSAGE", null, now, payload, LocalDate.now(clock.withZone(TimeConfig.NOUMEA)).plusMonths(12),
                List.of(familyId), List.of());
        return row(id, "MESSAGE", Timestamp.from(now), write(payload), false);
    }

    // --- Absences -----------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> reportAbsence(String email, UUID structureId, UUID childId, LocalDate from, LocalDate to,
                                             String reason) {
        var access = childAccess(email, structureId, childId);
        var today = LocalDate.now(clock.withZone(TimeConfig.NOUMEA));
        if (from.isBefore(today)) {
            throw ApiException.rule("absence_past", "Une absence se signale pour aujourd’hui ou plus tard.");
        }
        if (to.isBefore(from) || from.plusDays(92).isBefore(to)) {
            throw ApiException.rule("absence_dates", "Vérifiez les dates : trois mois au plus.");
        }
        var now = clock.instant();
        var clean = reason == null || reason.isBlank() ? null : reason.strip();
        var inbox = new LinkedHashMap<String, Object>();
        inbox.put("childId", childId);
        inbox.put("guardianId", access.guardianId());
        inbox.put("fromDay", from);
        inbox.put("toDay", to);
        inbox.put("reason", clean);
        inbox.put("reportedAt", now);
        sync.deposit(structureId, "ABSENCE", access.familyId(), inbox);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("childId", childId.toString());
        payload.put("fromDay", from.toString());
        payload.put("toDay", to.toString());
        payload.put("reason", clean);
        payload.put("reportedAt", now.toString());
        var id = UUID.randomUUID();
        store(structureId, id, "ABSENCE", from, now, payload, to.plusDays(60), List.of(access.familyId()), List.of(childId));
        return row(id, "ABSENCE", Timestamp.from(now), write(payload), false);
    }

    // --- Outils -------------------------------------------------------------------------------

    private void store(UUID structureId, UUID id, String type, LocalDate day, Instant at, Map<String, ?> payload,
                       LocalDate expires, List<UUID> families, List<UUID> children) {
        jdbc.update("INSERT INTO item (structure_id, id, type, day, at, payload, expires_on, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)", structureId, id, type, day == null ? null : Date.valueOf(day),
                Timestamp.from(at), write(payload), Date.valueOf(expires), Timestamp.from(at));
        for (var family : families) {
            jdbc.update("INSERT INTO item_family (structure_id, item_id, family_id) VALUES (?, ?, ?)", structureId, id, family);
        }
        for (var child : children) {
            jdbc.update("INSERT INTO item_child (structure_id, item_id, child_id) VALUES (?, ?, ?)", structureId, id, child);
        }
    }

    private Map<String, Object> row(UUID id, String type, Timestamp at, String payload, boolean hasFile) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("type", type);
        row.put("at", at == null ? null : at.toInstant());
        row.put("data", read(payload));
        row.put("hasFile", hasFile);
        return row;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
