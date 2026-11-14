package nc.ticagou.portail.push;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import nc.ticagou.portail.PortalProperties;
import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.push.natif.ApnsSender;
import nc.ticagou.portail.push.natif.FcmSender;
import nc.ticagou.portail.push.natif.NativePush;

/**
 * Notifications sur le téléphone des parents (ADR 0018, 0026) : abonnements par appareil et envoi des nouveautés reçues
 * de la crèche, en Web Push (PWA) et par APNs / FCM (application Ti’Cagou Parents), sans donnée de santé ni contenu
 * de message.
 */
@Service
public class PushService {

    public enum Category { PHOTOS, MESSAGES, ACTUALITES, DOCUMENTS, FACTURES }

    /** Ce qu'un parent voit : titre (la crèche), phrase courte, page à ouvrir. */
    record Notice(UUID structureId, UUID familyId, Category category, String body, String url, String tag) {
    }

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    /** Services de notification des navigateurs : aucune autre adresse n'est appelée (pas de requête vers le réseau interne). */
    private static final List<String> PUSH_HOSTS = List.of(".googleapis.com", ".mozilla.com", ".push.apple.com",
            ".notify.windows.com");
    private static final long TTL_SECONDS = 24 * 3600;

    private final JdbcTemplate jdbc;
    private final PushTransport transport;
    private final PortalProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile KeyPair keys;
    private final ApnsSender apns;
    private final FcmSender fcm;

    public PushService(JdbcTemplate jdbc, PushTransport transport, PortalProperties properties, ObjectMapper json,
                       Clock clock, ApnsSender apns, FcmSender fcm) {
        this.apns = apns;
        this.fcm = fcm;
        this.jdbc = jdbc;
        this.transport = transport;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    // --- Abonnements --------------------------------------------------------------------------

    public String publicKey() {
        return WebPushCrypto.encode(WebPushCrypto.uncompressed((ECPublicKey) keys().getPublic()));
    }

    /** Catégories choisies pour cet appareil, ou {@code null} s'il n'est pas abonné. */
    public List<String> categories(String email, String endpoint) {
        return jdbc.query("SELECT categories FROM push_subscription WHERE endpoint = ? AND lower(email) = lower(?)",
                (rs, i) -> List.of(rs.getString(1).split(",")), endpoint, email).stream().findFirst()
                .map(l -> l.stream().filter(s -> !s.isEmpty()).toList()).orElse(null);
    }

    @Transactional
    public void subscribe(String email, String endpoint, String p256dh, String auth, Set<Category> categories) {
        checkEndpoint(endpoint);
        try {
            WebPushCrypto.publicKey(WebPushCrypto.decode(p256dh));
            if (WebPushCrypto.decode(auth).length != 16) {
                throw new IllegalArgumentException();
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw ApiException.rule("push_keys", "Abonnement invalide : réessayez depuis le navigateur.");
        }
        var list = String.join(",", categories.stream().sorted().map(Enum::name).toList());
        var now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO push_subscription (id, email, endpoint, p256dh, auth, categories, created_at, updated_at) "
                        + "VALUES (?, lower(?), ?, ?, ?, ?, ?, ?) ON CONFLICT (endpoint) DO UPDATE SET email = excluded.email, "
                        + "p256dh = excluded.p256dh, auth = excluded.auth, categories = excluded.categories, "
                        + "updated_at = excluded.updated_at, failures = 0",
                UUID.randomUUID(), email, endpoint, p256dh.strip(), auth.strip(), list, now, now);
    }

    @Transactional
    public void unsubscribe(String email, String endpoint) {
        jdbc.update("DELETE FROM push_subscription WHERE endpoint = ? AND lower(email) = lower(?)", endpoint, email);
    }

    // --- Application native (ADR 0026) ----------------------------------------------------------

    /** Catégories de cet appareil de l’application, ou {@code null} s’il n’est pas inscrit. */
    public List<String> nativeCategories(String email, String token) {
        return jdbc.query("SELECT categories FROM native_device WHERE token = ? AND lower(email) = lower(?)",
                (rs, i) -> List.of(rs.getString(1).split(",")), token, email).stream().findFirst()
                .map(l -> l.stream().filter(s -> !s.isEmpty()).toList()).orElse(null);
    }

    @Transactional
    public void registerNative(String email, NativePush.Platform platform, String token, Set<Category> categories) {
        var list = String.join(",", categories.stream().sorted().map(Enum::name).toList());
        var now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO native_device (id, email, platform, token, categories, created_at, updated_at) "
                        + "VALUES (?, lower(?), ?, ?, ?, ?, ?) ON CONFLICT (token) DO UPDATE SET email = excluded.email, "
                        + "platform = excluded.platform, categories = excluded.categories, updated_at = excluded.updated_at",
                UUID.randomUUID(), email, platform.name(), token.strip(), list, now, now);
    }

    @Transactional
    public void unregisterNative(String email, String token) {
        jdbc.update("DELETE FROM native_device WHERE token = ? AND lower(email) = lower(?)", token, email);
    }

    /** Suppression du compte (ADR 0024) : plus aucun appareil de cette adresse n’est prévenu. */
    @Transactional
    public void forgetAll(String email) {
        jdbc.update("DELETE FROM push_subscription WHERE lower(email) = lower(?)", email);
        jdbc.update("DELETE FROM native_device WHERE lower(email) = lower(?)", email);
    }

    /** Plus aucun accès pour cette adresse : ses appareils ne sont plus prévenus. */
    public void forgetOrphan(String email) {
        jdbc.update("DELETE FROM native_device WHERE lower(email) = lower(?) "
                + "AND NOT EXISTS (SELECT 1 FROM guardian g WHERE lower(g.email) = lower(?))", email, email);
        jdbc.update("DELETE FROM push_subscription WHERE lower(email) = lower(?) "
                + "AND NOT EXISTS (SELECT 1 FROM guardian g WHERE lower(g.email) = lower(?))", email, email);
    }

    static void checkEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.rule("push_endpoint", "Service de notification inconnu.");
        }
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (!"https".equals(uri.getScheme()) || PUSH_HOSTS.stream().noneMatch(host::endsWith)) {
            throw ApiException.rule("push_endpoint", "Service de notification inconnu.");
        }
    }

    // --- Nouveautés reçues de la crèche -------------------------------------------------------

    /**
     * Élément nouveau d'un lot (ADR 0018) : prépare une notification par famille, envoyée après la validation du lot.
     * {@code p} est le contenu reçu.
     */
    public void onNewItem(UUID structureId, String type, JsonNode p, List<UUID> families, List<UUID> children,
                          boolean hasFile) {
        var notices = new ArrayList<Notice>();
        switch (type) {
            case "ENTRY" -> {
                var kind = p.path("kind").asText();
                if (!("PHOTO".equals(kind) || "VIDEO".equals(kind)) || !hasFile) {
                    return;
                }
                var what = "VIDEO".equals(kind) ? "Nouvelle vidéo de " : "Nouvelle photo de ";
                for (var family : families) {
                    var names = jdbc.queryForList("SELECT first_name FROM child WHERE structure_id = ? AND family_id = ? "
                            + "AND id = ANY (?) ORDER BY first_name", String.class, structureId, family, children.toArray(UUID[]::new));
                    var who = names.isEmpty() ? "votre enfant" : String.join(" et ", names);
                    notices.add(new Notice(structureId, family, Category.PHOTOS, what + who, "/album", "photo"));
                }
            }
            case "MESSAGE" -> families.forEach(f -> notices.add(new Notice(structureId, f, Category.MESSAGES,
                    "Nouveau message de la crèche", "/messages", "message")));
            case "NEWS" -> families.forEach(f -> notices.add(new Notice(structureId, f, Category.ACTUALITES,
                    "Actualité : " + clip(p.path("title").asText(), 80), "/actualites", "news")));
            case "DOCUMENT" -> {
                if ("A_SIGNER".equals(p.path("status").asText())) {
                    families.forEach(f -> notices.add(new Notice(structureId, f, Category.DOCUMENTS,
                            "Document à signer : " + clip(p.path("title").asText(), 80), "/documents", "document")));
                }
            }
            case "INVOICE" -> families.forEach(f -> notices.add(new Notice(structureId, f, Category.FACTURES,
                    ("AVOIR".equals(p.path("kind").asText()) ? "Nouvel avoir " : "Nouvelle facture ") + p.path("number").asText(),
                    "/factures", "invoice")));
            default -> {
                return;
            }
        }
        if (notices.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submit(() -> notices.forEach(PushService.this::deliver));
                }
            });
        } else {
            executor.submit(() -> notices.forEach(this::deliver));
        }
    }

    void deliver(Notice notice) {
        try {
            var structure = jdbc.queryForList("SELECT name FROM portal_structure WHERE id = ?", String.class,
                    notice.structureId()).stream().findFirst().orElse("Ti’Cagou");
            var emails = jdbc.queryForList("SELECT DISTINCT lower(email) FROM guardian WHERE structure_id = ? AND family_id = ?",
                    String.class, notice.structureId(), notice.familyId());
            if (emails.isEmpty()) {
                return;
            }
            var subscriptions = jdbc.queryForList("SELECT id, endpoint, p256dh, auth FROM push_subscription "
                    + "WHERE lower(email) = ANY (?) AND position(? in categories) > 0", emails.toArray(String[]::new),
                    notice.category().name());
            var payload = new LinkedHashMap<String, String>();
            payload.put("title", structure);
            payload.put("body", notice.body());
            payload.put("url", notice.url());
            payload.put("tag", notice.tag() + "-" + notice.familyId());
            var bytes = json.writeValueAsBytes(payload);
            for (var s : subscriptions) {
                send((UUID) s.get("id"), (String) s.get("endpoint"), (String) s.get("p256dh"), (String) s.get("auth"), bytes);
            }
            deliverNative(structure, notice, emails);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Notification non envoyée : {}", e.getClass().getSimpleName());
        }
    }

    /** Application Ti’Cagou Parents : APNs ou FCM, même texte qu’en Web Push ; un jeton refusé est effacé. */
    private void deliverNative(String structure, Notice notice, List<String> emails) {
        var devices = jdbc.queryForList("SELECT token, platform FROM native_device WHERE lower(email) = ANY (?) "
                + "AND position(? in categories) > 0", emails.toArray(String[]::new), notice.category().name());
        var message = new NativePush.Message(structure, notice.body(), notice.url(), notice.category().name(),
                java.time.Duration.ofSeconds(TTL_SECONDS), notice.tag() + "-" + notice.familyId());
        for (var d : devices) {
            var platform = NativePush.Platform.valueOf((String) d.get("platform"));
            NativePush.Sender sender = platform == NativePush.Platform.IOS ? apns : fcm;
            if (!sender.enabled()) {
                continue;
            }
            var token = (String) d.get("token");
            if (sender.send(message, new NativePush.Target(token, platform, "nc.ticagou.parents")) == NativePush.Result.INVALID) {
                jdbc.update("DELETE FROM native_device WHERE token = ?", token);
            }
        }
    }

    private void send(UUID id, String endpoint, String p256dh, String auth, byte[] payload) {
        try {
            var body = WebPushCrypto.encrypt(payload, p256dh, auth);
            var headers = Map.of("Authorization", Vapid.authorization(keys(), endpoint, subject(),
                            clock.instant().getEpochSecond() + 12 * 3600),
                    "Content-Encoding", "aes128gcm", "Content-Type", "application/octet-stream",
                    "TTL", String.valueOf(TTL_SECONDS), "Urgency", "normal");
            int status = transport.post(endpoint, headers, body);
            if (status == 404 || status == 410) {
                jdbc.update("DELETE FROM push_subscription WHERE id = ?", id);
            } else if (status >= 200 && status < 300) {
                jdbc.update("UPDATE push_subscription SET last_success_at = ?, failures = 0 WHERE id = ?",
                        Timestamp.from(clock.instant()), id);
            } else {
                jdbc.update("UPDATE push_subscription SET failures = failures + 1 WHERE id = ?", id);
                log.info("Service de notification : réponse {}", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            jdbc.update("UPDATE push_subscription SET failures = failures + 1 WHERE id = ?", id);
            log.info("Service de notification injoignable : {}", e.getClass().getSimpleName());
        }
    }

    // --- Clés VAPID ---------------------------------------------------------------------------

    /** Clés VAPID : fournies par l'hébergeur ({@code PORTAL_VAPID_KEY}), sinon générées et gardées dans la base. */
    KeyPair keys() {
        var current = keys;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (keys == null) {
                var configured = properties.vapidKey();
                String value = configured != null && !configured.isBlank() ? configured.strip() : stored();
                keys = parse(value);
            }
            return keys;
        }
    }

    private String stored() {
        var existing = jdbc.queryForList("SELECT value FROM portal_secret WHERE name = 'vapid'", String.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        try {
            var pair = WebPushCrypto.newKeyPair();
            var b64 = Base64.getEncoder();
            var value = b64.encodeToString(pair.getPrivate().getEncoded()) + "." + b64.encodeToString(pair.getPublic().getEncoded());
            jdbc.update("INSERT INTO portal_secret (name, value, created_at) VALUES ('vapid', ?, ?) ON CONFLICT (name) DO NOTHING",
                    value, Timestamp.from(clock.instant()));
            return jdbc.queryForObject("SELECT value FROM portal_secret WHERE name = 'vapid'", String.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** « PKCS#8 en Base64 » + « . » + « X.509 en Base64 ». */
    private static KeyPair parse(String value) {
        try {
            var parts = value.split("\\.");
            var b64 = Base64.getDecoder();
            var factory = KeyFactory.getInstance("EC");
            return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(b64.decode(parts[1]))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(b64.decode(parts[0]))));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Clé VAPID illisible (PORTAL_VAPID_KEY)", e);
        }
    }

    private String subject() {
        var url = properties.publicUrl();
        return url != null && url.startsWith("https://") ? url : "mailto:portail@ticagou.nc";
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }

    /** Pour les tests : attend la fin des envois en cours. */
    void drain() throws InterruptedException {
        var done = new java.util.concurrent.CountDownLatch(1);
        executor.submit(done::countDown);
        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static Set<Category> parseCategories(List<String> values) {
        var out = new LinkedHashSet<Category>();
        for (var v : values) {
            try {
                out.add(Category.valueOf(v));
            } catch (IllegalArgumentException e) {
                throw ApiException.rule("push_category", "Catégorie inconnue : " + v);
            }
        }
        return out;
    }
}
