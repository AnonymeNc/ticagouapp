package nc.ticagou.portal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portal.SyncDtos.Ack;
import nc.ticagou.portal.SyncDtos.ActivateRequest;
import nc.ticagou.portal.SyncDtos.ActivateResponse;
import nc.ticagou.portal.SyncDtos.Batch;
import nc.ticagou.portal.SyncDtos.BatchResponse;
import nc.ticagou.portal.SyncDtos.Inbox;

/**
 * Client HTTP du portail : toujours une connexion sortante de la crèche vers le portail (ADR 0010). Les requêtes de
 * synchronisation sont signées en Ed25519 ; seule l'activation, qui enregistre la clé publique, ne l'est pas.
 */
@Component
public class PortalClient {

    /** Échec de la synchronisation : message lisible pour la direction. */
    public static class PortalException extends RuntimeException {

        private final int status;

        public PortalException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final Clock clock;

    public PortalClient(ObjectMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    public ActivateResponse activate(String baseUrl, ActivateRequest request) {
        return send(baseUrl, "POST", "/sync/v1/activate", request, null, null, ActivateResponse.class);
    }

    public BatchResponse batch(String baseUrl, UUID portalStructureId, PrivateKey key, Batch batch) {
        return send(baseUrl, "POST", "/sync/v1/batch", batch, portalStructureId, key, BatchResponse.class);
    }

    /** Lot déjà sérialisé (envoi de la file : chaque élément n'est sérialisé qu'une fois). */
    public BatchResponse batch(String baseUrl, UUID portalStructureId, PrivateKey key, byte[] json) {
        return send(baseUrl, "POST", "/sync/v1/batch", new Raw(json), portalStructureId, key, BatchResponse.class);
    }

    private record Raw(byte[] json) {
    }

    public Inbox inbox(String baseUrl, UUID portalStructureId, PrivateKey key, long after) {
        return send(baseUrl, "GET", "/sync/v1/inbox?after=" + after, null, portalStructureId, key, Inbox.class);
    }

    public void ack(String baseUrl, UUID portalStructureId, PrivateKey key, long upTo) {
        send(baseUrl, "POST", "/sync/v1/inbox/ack", new Ack(upTo), portalStructureId, key, null);
    }

    /** Supervision (ADR 0012) : activation par code, puis état signé. */
    public record SupervisionActivation(String activationCode, String publicKey, String name) {
    }

    public record SupervisionSite(UUID siteId) {
    }

    public SupervisionSite activateSupervision(String baseUrl, SupervisionActivation request) {
        return send(baseUrl, "POST", "/supervision/v1/activate", request, null, null, SupervisionSite.class);
    }

    public void sendStatus(String baseUrl, UUID siteId, PrivateKey key, Object status) {
        send(baseUrl, "POST", "/supervision/v1/status", status, siteId, key, null);
    }

    private <T> T send(String baseUrl, String method, String path, Object body, UUID portalStructureId, PrivateKey key,
                       Class<T> type) {
        try {
            var bytes = body == null ? new byte[0] : body instanceof Raw raw ? raw.json() : json.writeValueAsBytes(body);
            var builder = HttpRequest.newBuilder(URI.create(trim(baseUrl) + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json");
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            if (key != null) {
                var date = clock.instant();
                builder.header(PortalSigner.HEADER_STRUCTURE, portalStructureId.toString())
                        .header(PortalSigner.HEADER_DATE, date.toString())
                        .header(PortalSigner.HEADER_SIGNATURE, PortalSigner.sign(key, method, path, date, bytes));
            }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bytes));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new PortalException(problem(response), response.statusCode());
            }
            return type == null || response.body().length == 0 ? null : json.readValue(response.body(), type);
        } catch (IOException e) {
            throw new PortalException("Portail injoignable (" + e.getClass().getSimpleName() + ") : vérifiez la connexion internet.", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortalException("Synchronisation interrompue.", 0);
        } catch (IllegalArgumentException e) {
            throw new PortalException("Adresse du portail invalide.", 0);
        }
    }

    private String problem(HttpResponse<byte[]> response) {
        try {
            var node = json.readTree(response.body());
            if (node != null && node.hasNonNull("detail")) {
                return "Portail : " + node.get("detail").asText() + " (" + response.statusCode() + ")";
            }
        } catch (IOException ignored) {
            // Réponse non JSON (proxy, page d'erreur) : le code HTTP suffit.
        }
        return "Le portail a répondu " + response.statusCode() + ".";
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
