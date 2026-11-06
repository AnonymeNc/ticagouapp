package nc.ticagou.portail.sync;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.common.ApiException;

/**
 * Point d'entrée des serveurs de crèche (ADR 0010). Toutes les requêtes, sauf l'activation, sont signées : le corps
 * est lu brut pour vérifier la signature avant toute interprétation.
 */
@RestController
@RequestMapping("/sync/v1")
public class SyncController {

    /** Taille maximale d'un lot (8 Mo de contenu, encodé en Base64). */
    static final int MAX_BODY = 12 * 1024 * 1024;

    private final SyncAuthenticator authenticator;
    private final SyncService sync;
    private final ObjectMapper json;

    public SyncController(SyncAuthenticator authenticator, SyncService sync, ObjectMapper json) {
        this.authenticator = authenticator;
        this.sync = sync;
        this.json = json;
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody Map<String, String> request) {
        return Map.of("structureId", sync.activate(request.get("activationCode"), request.get("publicKey"),
                request.get("structureName")));
    }

    @PostMapping("/batch")
    public Map<String, Object> batch(@RequestHeader(value = SyncAuthenticator.HEADER_STRUCTURE, required = false) String structure,
                                     @RequestHeader(value = SyncAuthenticator.HEADER_DATE, required = false) String date,
                                     @RequestHeader(value = SyncAuthenticator.HEADER_SIGNATURE, required = false) String signature,
                                     @RequestBody byte[] body) {
        if (body.length > MAX_BODY) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Lot trop volumineux.");
        }
        var structureId = authenticator.authenticate("POST", "/sync/v1/batch", structure, date, signature, body);
        return Map.of("acceptedUpTo", sync.apply(structureId, read(body)));
    }

    @GetMapping("/inbox")
    public Map<String, Object> inbox(@RequestHeader(value = SyncAuthenticator.HEADER_STRUCTURE, required = false) String structure,
                                     @RequestHeader(value = SyncAuthenticator.HEADER_DATE, required = false) String date,
                                     @RequestHeader(value = SyncAuthenticator.HEADER_SIGNATURE, required = false) String signature,
                                     @RequestParam long after) {
        var structureId = authenticator.authenticate("GET", "/sync/v1/inbox?after=" + after, structure, date, signature,
                new byte[0]);
        return Map.of("items", sync.inbox(structureId, after));
    }

    @PostMapping("/inbox/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@RequestHeader(value = SyncAuthenticator.HEADER_STRUCTURE, required = false) String structure,
                    @RequestHeader(value = SyncAuthenticator.HEADER_DATE, required = false) String date,
                    @RequestHeader(value = SyncAuthenticator.HEADER_SIGNATURE, required = false) String signature,
                    @RequestBody byte[] body) {
        var structureId = authenticator.authenticate("POST", "/sync/v1/inbox/ack", structure, date, signature, body);
        sync.ack(structureId, read(body).path("upTo").asLong());
    }

    private JsonNode read(byte[] body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw ApiException.rule("unreadable", "Requête illisible.");
        }
    }
}
