package nc.ticagou.portail.sync;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import nc.ticagou.portail.TimeConfig;
import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.FileCrypto;
import nc.ticagou.portail.common.Hashes;

/**
 * Applique les lots envoyés par le serveur d'une crèche (ADR 0010). Le portail ne calcule rien : il garde ce qu'il
 * reçoit, pour la durée prévue, et efface sur ordre. Un élément déjà reçu (numéro de séquence) est ignoré.
 */
@Service
public class SyncService {

    /** Durées de conservation : transmissions et photos 60 jours, factures et documents 24 mois, messages 12 mois. */
    static final Period DAILY_RETENTION = Period.ofDays(60);
    static final Period BILLING_RETENTION = Period.ofMonths(24);
    static final Period MESSAGE_RETENTION = Period.ofMonths(12);
    static final Period ACTIVATION_VALIDITY = Period.ofDays(30);

    private final JdbcTemplate jdbc;
    private final FileCrypto crypto;
    private final ObjectMapper json;
    private final Clock clock;

    public SyncService(JdbcTemplate jdbc, FileCrypto crypto, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.json = json;
        this.clock = clock;
    }

    // --- Activation ---------------------------------------------------------------------------

    /** Crée un code d'activation (hébergeur) ; seul son haché est gardé. */
    @Transactional
    public String newActivationCode(String label) {
        var raw = Hashes.token(9).toUpperCase().replaceAll("[^A-Z0-9]", "X");
        var code = raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
        var now = clock.instant();
        jdbc.update("INSERT INTO activation_code (code_hash, label, created_at, expires_at) VALUES (?, ?, ?, ?)",
                Hashes.sha256(code), label, Timestamp.from(now), Timestamp.from(now.plus(java.time.Duration.ofDays(30))));
        return code;
    }

    /** Enregistre une structure et sa clé publique ; le code d'activation ne sert qu'une fois. */
    @Transactional
    public UUID activate(String code, String publicKeyBase64, String name) {
        byte[] publicKey;
        try {
            publicKey = Base64.getDecoder().decode(publicKeyBase64 == null ? "" : publicKeyBase64.strip());
            java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(publicKey));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            throw ApiException.rule("bad_public_key", "Clé publique invalide.");
        }
        var now = clock.instant();
        int claimed = jdbc.update("UPDATE activation_code SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
                Timestamp.from(now), Hashes.sha256(code == null ? "" : code.strip().toUpperCase()), Timestamp.from(now));
        if (claimed != 1) {
            throw ApiException.forbidden("activation_code", "Code d’activation inconnu, expiré ou déjà utilisé.");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO portal_structure (id, name, public_key, activated_at) VALUES (?, ?, ?, ?)",
                id, clip(name, 150, "Crèche"), publicKey, Timestamp.from(now));
        jdbc.update("UPDATE activation_code SET structure_id = ? WHERE code_hash = ?", id,
                Hashes.sha256(code.strip().toUpperCase()));
        return id;
    }

    // --- Lots ---------------------------------------------------------------------------------

    @Transactional
    public long apply(UUID structureId, JsonNode batch) {
        var lastSeq = jdbc.queryForObject("SELECT last_seq FROM portal_structure WHERE id = ? FOR UPDATE", Long.class, structureId);
        long accepted = lastSeq;
        for (var item : batch.path("items")) {
            long seq = item.path("seq").asLong();
            var type = item.path("type").asText();
            if ("PURGE_STRUCTURE".equals(type)) {
                jdbc.update("DELETE FROM portal_structure WHERE id = ?", structureId);
                return seq;
            }
            if (seq <= lastSeq) {
                accepted = Math.max(accepted, seq);
                continue;
            }
            var id = UUID.fromString(item.path("id").asText());
            boolean deleted = item.path("deleted").asBoolean();
            var payload = item.path("payload");
            switch (type) {
                case "STRUCTURE" -> structure(structureId, payload);
                case "GUARDIAN" -> guardian(structureId, id, deleted, payload);
                case "CHILD" -> child(structureId, id, deleted, payload);
                case "ATTENDANCE", "ENTRY", "INVOICE", "DOCUMENT", "MESSAGE" -> item(structureId, type, id, deleted, payload);
                case "PURGE_FAMILY" -> purgeFamily(structureId, id);
                default -> throw ApiException.rule("unknown_type", "Type inconnu : " + type);
            }
            accepted = Math.max(accepted, seq);
        }
        jdbc.update("UPDATE portal_structure SET last_seq = ?, last_sync_at = ? WHERE id = ?", accepted,
                Timestamp.from(clock.instant()), structureId);
        return accepted;
    }

    private void structure(UUID structureId, JsonNode p) {
        jdbc.update("UPDATE portal_structure SET name = ?, phone = ?, payment_info = ?, meal_absence_cutoff = ? WHERE id = ?",
                clip(text(p, "name"), 150, "Crèche"), text(p, "phone"), text(p, "paymentInfo"), text(p, "mealAbsenceCutoff"),
                structureId);
    }

    private void guardian(UUID structureId, UUID id, boolean deleted, JsonNode p) {
        var previous = jdbc.query("SELECT email FROM guardian WHERE structure_id = ? AND id = ?",
                (rs, i) -> rs.getString(1), structureId, id);
        jdbc.update("DELETE FROM guardian WHERE structure_id = ? AND id = ?", structureId, id);
        if (!deleted) {
            jdbc.update("INSERT INTO guardian (structure_id, id, family_id, first_name, last_name, email, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", structureId, id, uuid(p, "familyId"), text(p, "firstName"),
                    text(p, "lastName"), text(p, "email").strip().toLowerCase(), Timestamp.from(clock.instant()));
        }
        // Accès retiré ou adresse changée : les sessions de l'ancienne adresse ne voient plus rien de cette famille ;
        // sans autre rattachement, elles sont révoquées.
        for (var email : previous) {
            revokeOrphanSessions(email);
        }
    }

    void revokeOrphanSessions(String email) {
        jdbc.update("UPDATE parent_session SET revoked_at = ? WHERE lower(email) = lower(?) AND revoked_at IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM guardian g WHERE lower(g.email) = lower(?))",
                Timestamp.from(clock.instant()), email, email);
    }

    private void child(UUID structureId, UUID id, boolean deleted, JsonNode p) {
        jdbc.update("DELETE FROM child WHERE structure_id = ? AND id = ?", structureId, id);
        if (deleted) {
            return;
        }
        var photo = bytes(p, "photo");
        jdbc.update("INSERT INTO child (structure_id, id, family_id, first_name, last_initial, section, photo, photo_type, "
                        + "shared_health, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                structureId, id, uuid(p, "familyId"), text(p, "firstName"), text(p, "lastInitial"), text(p, "section"),
                photo == null ? null : crypto.encrypt(structureId, id, photo), photo == null ? null : text(p, "photoType"),
                p.path("sharedHealth").isArray() ? p.get("sharedHealth").toString() : "[]", Timestamp.from(clock.instant()));
    }

    private void item(UUID structureId, String type, UUID id, boolean deleted, JsonNode p) {
        jdbc.update("DELETE FROM item WHERE structure_id = ? AND id = ?", structureId, id);
        if (deleted) {
            return;
        }
        var today = LocalDate.now(clock.withZone(TimeConfig.NOUMEA));
        var stored = (ObjectNode) p.deepCopy();
        byte[] file = null;
        String fileType = null;
        LocalDate day = null;
        Instant at = null;
        List<UUID> families = new ArrayList<>();
        List<UUID> children = new ArrayList<>();
        LocalDate expires;
        switch (type) {
            case "ATTENDANCE" -> {
                day = LocalDate.parse(text(p, "day"));
                at = instant(p, "arrivedAt");
                families.add(uuid(p, "familyId"));
                children.add(uuid(p, "childId"));
                expires = day.plus(DAILY_RETENTION);
            }
            case "ENTRY" -> {
                day = LocalDate.parse(text(p, "day"));
                at = instant(p, "at");
                p.path("familyIds").forEach(f -> families.add(UUID.fromString(f.asText())));
                p.path("childIds").forEach(c -> children.add(UUID.fromString(c.asText())));
                file = bytes(p, "photo");
                fileType = file == null ? null : text(p, "photoType");
                expires = day.plus(DAILY_RETENTION);
            }
            case "INVOICE" -> {
                day = LocalDate.parse(text(p, "issuedOn"));
                families.add(uuid(p, "familyId"));
                file = bytes(p, "pdf");
                fileType = "application/pdf";
                expires = today.plus(BILLING_RETENTION);
            }
            case "DOCUMENT" -> {
                families.add(uuid(p, "familyId"));
                if (p.hasNonNull("childId")) {
                    children.add(uuid(p, "childId"));
                }
                file = bytes(p, "pdf");
                fileType = "application/pdf";
                expires = today.plus(BILLING_RETENTION);
            }
            default -> {
                at = instant(p, "sentAt");
                families.add(uuid(p, "familyId"));
                stored.put("fromFamily", false);
                expires = today.plus(MESSAGE_RETENTION);
            }
        }
        stored.remove(List.of("photo", "photoType", "pdf", "familyIds", "childIds", "familyId"));
        jdbc.update("INSERT INTO item (structure_id, id, type, day, at, payload, file, file_type, expires_on, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)", structureId, id, type,
                day == null ? null : Date.valueOf(day), at == null ? null : Timestamp.from(at), stored.toString(),
                file == null ? null : crypto.encrypt(structureId, id, file), fileType, Date.valueOf(expires),
                Timestamp.from(clock.instant()));
        link(structureId, id, families, children);
    }

    void link(UUID structureId, UUID id, List<UUID> families, List<UUID> children) {
        for (var family : families.stream().distinct().toList()) {
            jdbc.update("INSERT INTO item_family (structure_id, item_id, family_id) VALUES (?, ?, ?)", structureId, id, family);
        }
        for (var child : children.stream().distinct().toList()) {
            jdbc.update("INSERT INTO item_child (structure_id, item_id, child_id) VALUES (?, ?, ?)", structureId, id, child);
        }
    }

    /** Efface tout ce qui concerne une famille ; une photo de groupe reste pour les autres familles. */
    private void purgeFamily(UUID structureId, UUID familyId) {
        var emails = jdbc.queryForList("SELECT email FROM guardian WHERE structure_id = ? AND family_id = ?", String.class,
                structureId, familyId);
        var childIds = jdbc.queryForList("SELECT id FROM child WHERE structure_id = ? AND family_id = ?", UUID.class,
                structureId, familyId);
        jdbc.update("DELETE FROM item_family WHERE structure_id = ? AND family_id = ?", structureId, familyId);
        for (var child : childIds) {
            jdbc.update("DELETE FROM item_child WHERE structure_id = ? AND child_id = ?", structureId, child);
        }
        jdbc.update("DELETE FROM item i WHERE i.structure_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM item_family f WHERE f.structure_id = i.structure_id AND f.item_id = i.id)", structureId);
        jdbc.update("DELETE FROM child WHERE structure_id = ? AND family_id = ?", structureId, familyId);
        jdbc.update("DELETE FROM guardian WHERE structure_id = ? AND family_id = ?", structureId, familyId);
        jdbc.update("DELETE FROM inbox WHERE structure_id = ? AND family_id = ?", structureId, familyId);
        emails.forEach(this::revokeOrphanSessions);
    }

    // --- Remontée -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> inbox(UUID structureId, long after) {
        return jdbc.query("SELECT seq, type, id, family_id, payload FROM inbox WHERE structure_id = ? AND seq > ? "
                + "ORDER BY seq LIMIT 200", (rs, i) -> {
                    try {
                        return Map.<String, Object>of("seq", rs.getLong("seq"), "type", rs.getString("type"),
                                "id", rs.getObject("id", UUID.class), "familyId", rs.getObject("family_id", UUID.class),
                                "payload", json.readTree(rs.getString("payload")));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }, structureId, after);
    }

    @Transactional
    public void ack(UUID structureId, long upTo) {
        jdbc.update("DELETE FROM inbox WHERE structure_id = ? AND seq <= ?", structureId, upTo);
    }

    /** Dépose un élément envoyé par un parent, lu par le serveur de la crèche à sa prochaine synchronisation. */
    public void deposit(UUID structureId, String type, UUID familyId, Object payload) {
        try {
            jdbc.update("INSERT INTO inbox (structure_id, id, type, family_id, payload, created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                    structureId, UUID.randomUUID(), type, familyId, json.writeValueAsString(payload),
                    Timestamp.from(clock.instant()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Outils -------------------------------------------------------------------------------

    private static String text(JsonNode p, String field) {
        var node = p.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID uuid(JsonNode p, String field) {
        var value = text(p, field);
        if (value == null) {
            throw ApiException.rule("missing_field", "Champ manquant : " + field);
        }
        return UUID.fromString(value);
    }

    private static Instant instant(JsonNode p, String field) {
        var value = text(p, field);
        return value == null ? null : Instant.parse(value);
    }

    private static byte[] bytes(JsonNode p, String field) {
        var value = text(p, field);
        return value == null || value.isEmpty() ? null : Base64.getDecoder().decode(value);
    }

    private static String clip(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
