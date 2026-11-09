package nc.ticagou.portail.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;

import org.junit.jupiter.api.Test;

/** Chiffrement Web Push conforme au RFC 8291 (vecteur de l'annexe A) et jeton VAPID ES256 vérifiable. */
class WebPushCryptoTest {

    @Test
    void vecteurDuRfc8291() throws Exception {
        var asPrivate = WebPushCrypto.privateKey(WebPushCrypto.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        var asPublic = WebPushCrypto.publicKey(WebPushCrypto.decode(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"));
        var body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
                WebPushCrypto.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg"), new KeyPair(asPublic, asPrivate),
                WebPushCrypto.decode("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(WebPushCrypto.encode(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlml"
                + "MoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void jetonVapidSigneParLaCleDuPortail() throws Exception {
        var keys = WebPushCrypto.newKeyPair();
        var jwt = Vapid.token(keys, "https://fcm.googleapis.com", "mailto:portail@ticagou.nc", 1_800_000_000L);
        var parts = jwt.split("\\.");
        assertThat(new String(WebPushCrypto.decode(parts[0]), StandardCharsets.UTF_8)).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        assertThat(new String(WebPushCrypto.decode(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1800000000,\"sub\":\"mailto:portail@ticagou.nc\"}");
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(WebPushCrypto.decode(parts[2])).hasSize(64);
        assertThat(verifier.verify(WebPushCrypto.decode(parts[2]))).isTrue();
    }
}
