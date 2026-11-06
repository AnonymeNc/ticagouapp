package nc.ticagou.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class PortalSignerTest {

    private static final Instant DATE = Instant.parse("2026-11-02T01:00:00Z");

    @Test
    void signatureVerifieeEtLieeALaRequete() {
        var pair = PortalSigner.newKeyPair();
        var body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        var signature = PortalSigner.sign(pair.getPrivate(), "POST", "/sync/v1/batch", DATE, body);

        assertThat(PortalSigner.verify(pair.getPublic(), "POST", "/sync/v1/batch", DATE, body, signature)).isTrue();
        // Corps, chemin, date ou méthode changés : refusé.
        assertThat(PortalSigner.verify(pair.getPublic(), "POST", "/sync/v1/batch", DATE,
                "{\"items\":[1]}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(PortalSigner.verify(pair.getPublic(), "POST", "/sync/v1/inbox/ack", DATE, body, signature)).isFalse();
        assertThat(PortalSigner.verify(pair.getPublic(), "POST", "/sync/v1/batch", DATE.plusSeconds(1), body, signature)).isFalse();
        assertThat(PortalSigner.verify(pair.getPublic(), "GET", "/sync/v1/batch", DATE, body, signature)).isFalse();
    }

    @Test
    void autreCleRefusee() {
        var signature = PortalSigner.sign(PortalSigner.newKeyPair().getPrivate(), "GET", "/sync/v1/inbox?after=0", DATE, null);
        assertThat(PortalSigner.verify(PortalSigner.newKeyPair().getPublic(), "GET", "/sync/v1/inbox?after=0", DATE,
                new byte[0], signature)).isFalse();
        assertThat(PortalSigner.verify(PortalSigner.newKeyPair().getPublic(), "GET", "/", DATE, null, "pas-du-base64!")).isFalse();
    }

    @Test
    void chaineCanoniqueDocumentee() {
        // Chaîne décrite dans l'ADR 0010 et parents-portal/contract/README.md : le portail la reconstruit à l'identique.
        assertThat(PortalSigner.canonical("get", "/sync/v1/inbox?after=3", DATE, new byte[0])).isEqualTo(
                "GET\n/sync/v1/inbox?after=3\n2026-11-02T01:00:00Z\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
