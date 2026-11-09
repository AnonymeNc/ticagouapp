package nc.ticagou.portail.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import nc.ticagou.portail.PortalTest;

/**
 * Notifications des parents (ADR 0018) : un appareil s'abonne à des catégories ; une nouveauté de la crèche part
 * chiffrée, une seule fois, sans contenu sensible ; un abonnement expiré ou une famille retirée ne reçoit plus rien.
 */
class PushIT extends PortalTest {

    private static final String MARIE = "marie.wamytan@example.nc";
    private static final UUID FAMILY = UUID.fromString("0b9a3c1e-0000-4000-8000-000000000100");
    private static final UUID CHILD = UUID.fromString("0b9a3c1e-0000-4000-8000-000000001000");
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/appareil-de-marie";

    @Autowired
    private PushService push;

    private MvcResult call(MockHttpServletRequestBuilder request, Cookie cookie, Object body) throws Exception {
        request.header("X-Ticagou-Portail", "1").cookie(cookie);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(request).andReturn();
    }

    private Map<String, Object> subscription(String endpoint, KeyPair device, byte[] auth, List<String> categories) {
        return Map.of("endpoint", endpoint,
                "p256dh", WebPushCrypto.encode(WebPushCrypto.uncompressed((ECPublicKey) device.getPublic())),
                "auth", WebPushCrypto.encode(auth), "categories", categories);
    }

    private static Map<String, Object> photo(long seq, UUID id) {
        return Map.of("seq", seq, "type", "ENTRY", "id", id, "deleted", false, "payload", Map.of(
                "familyIds", List.of(FAMILY), "childIds", List.of(CHILD), "day", "2026-11-02", "at", "2026-11-02T02:00:00Z",
                "kind", "PHOTO", "text", "Atelier peinture", "details", Map.of(),
                "photo", Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
                "photoType", "image/jpeg"));
    }

    @Test
    void nouveautesNotifieesUneFoisSelonLesCategories() throws Exception {
        var creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var cookie = login(MARIE);
        var device = WebPushCrypto.newKeyPair();
        var auth = new byte[16];
        Arrays.fill(auth, (byte) 7);

        var state = body(call(get("/api/v1/me/push?endpoint=" + ENDPOINT), cookie, null));
        assertThat(WebPushCrypto.decode(state.get("publicKey").asText())).hasSize(65);
        assertThat(state.get("categories").isNull()).isTrue();

        // Seuls les services de notification des navigateurs sont appelés.
        var internal = call(post("/api/v1/me/push"), cookie, subscription("https://169.254.169.254/latest", device, auth, List.of("PHOTOS")));
        assertThat(body(internal).get("code").asText()).isEqualTo("push_endpoint");
        assertThat(status(call(post("/api/v1/me/push"), cookie, subscription(ENDPOINT, device, auth, List.of("PHOTOS", "MESSAGES")))))
                .isEqualTo(204);
        assertThat(status(call(post("/api/v1/me/push"), cookie,
                subscription("https://fcm.googleapis.com/fcm/send/expire", WebPushCrypto.newKeyPair(), auth, List.of("PHOTOS")))))
                .isEqualTo(204);
        assertThat(body(call(get("/api/v1/me/push?endpoint=" + ENDPOINT), cookie, null)).get("categories"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("MESSAGES", "PHOTOS");

        var photoId = UUID.randomUUID();
        batch(creche, Map.of("items", List.of(
                photo(20, photoId),
                Map.of("seq", 21, "type", "MESSAGE", "id", UUID.randomUUID(), "deleted", false, "payload",
                        Map.of("familyId", FAMILY, "author", "Sandrine", "body", "Léa a de la fièvre", "sentAt", "2026-11-02T03:00:00Z")),
                Map.of("seq", 22, "type", "INVOICE", "id", UUID.randomUUID(), "deleted", false, "payload", Map.of(
                        "familyId", FAMILY, "number", "FA-2026-0050", "kind", "FACTURE", "month", "2026-11", "issuedOn", "2026-11-30",
                        "total", 1000, "remaining", 1000, "lines", List.of())))));
        push.drain();

        // Photo : deux appareils (dont l'expiré) ; message : un ; facture : catégorie non choisie.
        var toMarie = PUSHES.stream().filter(p -> p.endpoint().equals(ENDPOINT)).toList();
        assertThat(toMarie).hasSize(2);
        assertThat(toMarie.getFirst().headers()).containsEntry("Content-Encoding", "aes128gcm").containsEntry("TTL", "86400");
        assertThat(toMarie.getFirst().headers().get("Authorization")).startsWith("vapid t=").contains(", k=");
        var photoNotice = json.readTree(decrypt(toMarie.get(0).body(), device, auth));
        assertThat(photoNotice.get("title").asText()).isEqualTo("Les P'tits Cagous");
        assertThat(photoNotice.get("body").asText()).isEqualTo("Nouvelle photo de Léa");
        assertThat(photoNotice.get("url").asText()).isEqualTo("/album");
        // Le contenu d'un message ne sort jamais dans la notification.
        var messageNotice = new String(decrypt(toMarie.get(1).body(), device, auth), StandardCharsets.UTF_8);
        assertThat(messageNotice).contains("Nouveau message de la crèche").doesNotContain("fièvre");

        // Abonnement expiré : effacé. Photo renvoyée (même identifiant) : pas de nouvelle notification.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscription", Long.class)).isEqualTo(1);
        PUSHES.clear();
        batch(creche, Map.of("items", List.of(photo(23, photoId))));
        push.drain();
        assertThat(PUSHES).isEmpty();

        // Vidéo partagée : notifiée comme une photo (ADR 0019).
        batch(creche, Map.of("items", List.of(Map.of("seq", 24, "type", "ENTRY", "id", UUID.randomUUID(), "deleted", false,
                "payload", Map.of("familyIds", List.of(FAMILY), "childIds", List.of(CHILD), "day", "2026-11-02",
                        "at", "2026-11-02T04:00:00Z", "kind", "VIDEO", "text", "Premiers pas", "details", Map.of(),
                        "photo", Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'}),
                        "photoType", "video/mp4")))));
        push.drain();
        assertThat(PUSHES).hasSize(1);
        assertThat(json.readTree(decrypt(PUSHES.getFirst().body(), device, auth)).get("body").asText()).isEqualTo("Nouvelle vidéo de Léa");

        // Famille retirée par la crèche : l'appareil n'est plus abonné.
        batch(creche, Map.of("items", List.of(Map.of("seq", 30, "type", "PURGE_FAMILY", "id", FAMILY, "deleted", true))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscription", Long.class)).isZero();
    }

    @Test
    void desabonnementDeLAppareil() throws Exception {
        var creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var cookie = login(MARIE);
        var auth = new byte[16];
        call(post("/api/v1/me/push"), cookie, subscription(ENDPOINT, WebPushCrypto.newKeyPair(), auth, List.of("PHOTOS")));
        assertThat(status(call(post("/api/v1/me/push/unsubscribe"), cookie, Map.of("endpoint", ENDPOINT)))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscription", Long.class)).isZero();
        batch(creche, Map.of("items", List.of(photo(20, UUID.randomUUID()))));
        push.drain();
        assertThat(PUSHES).isEmpty();
    }

    /** Déchiffrement côté appareil (RFC 8291), pour lire ce que le parent verra. */
    private static byte[] decrypt(byte[] body, KeyPair device, byte[] auth) throws Exception {
        var buffer = ByteBuffer.wrap(body);
        var salt = new byte[16];
        buffer.get(salt);
        buffer.getInt();
        var asPublic = new byte[buffer.get()];
        buffer.get(asPublic);
        var encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(device.getPrivate());
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        var uaPublic = WebPushCrypto.uncompressed((ECPublicKey) device.getPublic());
        var ikm = WebPushCrypto.hkdf(auth, agreement.generateSecret(),
                WebPushCrypto.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic), 32);
        var cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        var nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        var padded = cipher.doFinal(encrypted);
        return Arrays.copyOf(padded, padded.length - 1);
    }
}
