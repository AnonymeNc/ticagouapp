package nc.ticagou.console.orders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.console.ConsoleProperties;
import nc.ticagou.console.auth.SensitiveAction;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository;

/** Ordres aux instances : côté éditeur (/api/v1, code redemandé) et côté hôte (/interne/v1, jeton de l'hôte). */
@RestController
class OrderController {

    record OrderRequest(@NotNull String type, @Size(max = 41) String slug, Map<String, String> params, @Size(max = 300) String reason) {
    }

    record Board(List<OrderService.View> orders, List<OrderService.Misaligned> misaligned, List<String> images, java.time.Instant hostAt) {
    }

    private final OrderService service;
    private final ParcRepository parc;
    private final SensitiveAction sensitive;
    private final ConsoleProperties properties;

    OrderController(OrderService service, ParcRepository parc, SensitiveAction sensitive, ConsoleProperties properties) {
        this.service = service;
        this.parc = parc;
        this.sensitive = sensitive;
        this.properties = properties;
    }

    // --- Éditeur

    @GetMapping("/api/v1/ordres")
    Board board() {
        var host = parc.collector("HOTE");
        var images = new ArrayList<String>();
        if (host.data() != null && host.data().path("images").isArray()) {
            host.data().get("images").forEach(n -> images.add(n.asText()));
        }
        return new Board(service.latest(), service.misaligned(), images, host.lastSuccessAt());
    }

    @GetMapping("/api/v1/parc/{slug}/ordres")
    List<OrderService.View> forSlug(@PathVariable String slug) {
        return service.forSlug(slug);
    }

    /** Ce que l'ordre « abonnement » reporterait dans l'instance, tiré du contrat et des périodes payées. */
    @GetMapping("/api/v1/parc/{slug}/ordres/abonnement")
    Map<String, String> subscriptionPreview(@PathVariable String slug) {
        return service.subscriptionParams(slug);
    }

    @PostMapping("/api/v1/ordres")
    ResponseEntity<OrderService.View> give(@Valid @RequestBody OrderRequest request, HttpSession session) {
        sensitive.require(session);
        var params = "ABONNEMENT".equals(request.type()) && (request.params() == null || request.params().isEmpty())
                ? service.subscriptionParams(request.slug()) : request.params();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.give(request.type(), request.slug(), params, request.reason()));
    }

    @PostMapping("/api/v1/ordres/{id}/confirmer")
    OrderService.View confirm(@PathVariable UUID id, HttpSession session) {
        sensitive.require(session);
        return service.confirm(id);
    }

    @PostMapping("/api/v1/ordres/{id}/annuler")
    OrderService.View cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    /**
     * Export d'une crèche (archive chiffrée écrite par l'hôte), lu en entier par le navigateur. Sans effet par lui-même :
     * la suppression ne suit qu'après {@code export/recu}, qui exige cette lecture complète.
     */
    @GetMapping("/api/v1/ordres/{id}/export")
    void download(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        var file = service.exportFile(id);
        var dir = Path.of(properties.exportsDir() == null ? "exports" : properties.exportsDir()).toAbsolutePath().normalize();
        var path = dir.resolve(file).normalize();
        if (!path.getParent().equals(dir) || !Files.isRegularFile(path)) {
            throw ApiException.gone("export_absent", "L’export n’est plus sur le serveur (gardé 7 jours) : relancez-le.");
        }
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString());
        response.setContentLengthLong(Files.size(path));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // Une lecture interrompue lève une exception : l'export n'est alors pas compté comme lu.
        try (var out = response.getOutputStream()) {
            Files.copy(path, out);
            out.flush();
        }
        service.exportStreamed(id);
    }

    @PostMapping("/api/v1/ordres/{id}/export/recu")
    OrderService.View exportReceived(@PathVariable UUID id) {
        return service.exportReceived(id);
    }

    // --- Hôte

    @GetMapping(value = "/interne/v1/ordres", produces = MediaType.TEXT_PLAIN_VALUE)
    String pending(@RequestHeader(value = "Authorization", required = false) String authorization) {
        host(authorization);
        return service.pendingLines();
    }

    @PostMapping("/interne/v1/ordres/{id}/debut")
    ResponseEntity<Void> claim(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
        host(authorization);
        return service.claim(id) ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping(value = "/interne/v1/ordres/{id}/fin", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> finish(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id,
                                @RequestParam("ok") boolean ok, @RequestBody(required = false) byte[] output) {
        host(authorization);
        var text = output == null ? "" : new String(output, StandardCharsets.UTF_8);
        return service.finish(id, ok, text) ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    private void host(String authorization) {
        var expected = properties.hostToken();
        if (expected == null || expected.isBlank() || authorization == null
                || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8), ("Bearer " + expected).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("host_token", "Jeton de l’hôte requis.");
        }
    }
}
