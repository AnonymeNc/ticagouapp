package nc.ticagou.portail.supervision;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.sync.SyncAuthenticator;

/**
 * Supervision des serveurs de crèche (ADR 0012) : activation par code, puis état technique signé toutes les
 * 15 minutes. Le serveur de la crèche ouvre toujours la connexion ; rien n'est renvoyé vers lui.
 */
@RestController
@RequestMapping("/supervision/v1")
public class SupervisionController {

    static final int MAX_BODY = 64 * 1024;

    private final SyncAuthenticator authenticator;
    private final SupervisionService supervision;
    private final ObjectMapper json;

    public SupervisionController(SyncAuthenticator authenticator, SupervisionService supervision, ObjectMapper json) {
        this.authenticator = authenticator;
        this.supervision = supervision;
        this.json = json;
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody Map<String, String> request) {
        return Map.of("siteId", supervision.activate(request.get("activationCode"), request.get("publicKey"), request.get("name")));
    }

    /** Vue groupe (lot 7) : crèches du même groupe que le site signataire. */
    @org.springframework.web.bind.annotation.GetMapping("/group")
    public java.util.List<com.fasterxml.jackson.databind.JsonNode> group(
            @RequestHeader(value = SyncAuthenticator.HEADER_STRUCTURE, required = false) String site,
            @RequestHeader(value = SyncAuthenticator.HEADER_DATE, required = false) String date,
            @RequestHeader(value = SyncAuthenticator.HEADER_SIGNATURE, required = false) String signature) {
        var siteId = authenticator.authenticateSite("GET", "/supervision/v1/group", site, date, signature, new byte[0]);
        return supervision.group(siteId);
    }

    @PostMapping("/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@RequestHeader(value = SyncAuthenticator.HEADER_STRUCTURE, required = false) String site,
                       @RequestHeader(value = SyncAuthenticator.HEADER_DATE, required = false) String date,
                       @RequestHeader(value = SyncAuthenticator.HEADER_SIGNATURE, required = false) String signature,
                       @RequestBody byte[] body) {
        if (body.length > MAX_BODY) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "État trop volumineux.");
        }
        var siteId = authenticator.authenticateSite("POST", "/supervision/v1/status", site, date, signature, body);
        try {
            supervision.record(siteId, json.readTree(body));
        } catch (IOException e) {
            throw ApiException.rule("bad_status", "État illisible.");
        }
    }
}
