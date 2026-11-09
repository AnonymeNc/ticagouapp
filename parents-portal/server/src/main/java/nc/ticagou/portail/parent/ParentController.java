package nc.ticagou.portail.parent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.portail.auth.ParentSessionInterceptor;
import nc.ticagou.portail.parent.ParentService.Content;
import nc.ticagou.portail.push.PushService;

/** API de l'application parents : tout est restreint aux familles de la session. */
@RestController
@RequestMapping("/api/v1/me")
public class ParentController {

    public record MessageRequest(@NotNull UUID structureId, @NotNull UUID familyId,
                                 @NotBlank(message = "Écrivez votre message") @Size(max = 2000) String body) {
    }

    public record AbsenceRequest(@NotNull UUID structureId, @NotNull UUID childId, @NotNull LocalDate fromDay,
                                 @NotNull LocalDate toDay, @Size(max = 300) String reason) {
    }

    /**
     * Réponse à un document : {@code decision} ACCEPTE ou REFUSE (une autorisation seulement), {@code certified} :
     * le parent certifie être responsable légal. Le nom du signataire est celui du compte.
     */
    public record SignRequest(@NotNull @Pattern(regexp = "ACCEPTE|REFUSE", message = "Choisissez votre réponse") String decision,
                              boolean certified) {

        @AssertTrue(message = "Certifiez être responsable légal de l’enfant")
        public boolean isCertifiedChecked() {
            return certified;
        }
    }

    /** Abonnement de cet appareil aux notifications (ADR 0018) : clés fournies par le navigateur, catégories choisies. */
    public record PushRequest(@NotBlank @Size(max = 1000) String endpoint, @NotBlank @Size(max = 120) String p256dh,
                              @NotBlank @Size(max = 40) String auth, @NotNull List<String> categories) {
    }

    public record PushEndpoint(@NotBlank @Size(max = 1000) String endpoint) {
    }

    private final ParentService parents;
    private final PushService push;

    public ParentController(ParentService parents, PushService push) {
        this.parents = parents;
        this.push = push;
    }

    @GetMapping
    public Map<String, Object> me(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email) {
        return parents.me(email);
    }

    @GetMapping("/children/{structureId}/{childId}/photo")
    public ResponseEntity<org.springframework.core.io.Resource> childPhoto(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                             @PathVariable UUID structureId, @PathVariable UUID childId) {
        return file(parents.childPhoto(email, structureId, childId));
    }

    @GetMapping("/children/{structureId}/{childId}/day")
    public Map<String, Object> day(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                   @PathVariable UUID structureId, @PathVariable UUID childId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return parents.day(email, structureId, childId, date);
    }

    @GetMapping("/children/{structureId}/{childId}/photos")
    public List<Map<String, Object>> photos(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                            @PathVariable UUID structureId, @PathVariable UUID childId) {
        return parents.photos(email, structureId, childId);
    }

    @GetMapping("/news")
    public List<Map<String, Object>> news(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email) {
        return parents.news(email);
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email) {
        return parents.items(email, "INVOICE");
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email) {
        return parents.items(email, "DOCUMENT");
    }

    @GetMapping("/absences")
    public List<Map<String, Object>> absences(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email) {
        return parents.items(email, "ABSENCE");
    }

    @GetMapping("/files/{structureId}/{itemId}")
    public ResponseEntity<org.springframework.core.io.Resource> file(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                       @PathVariable UUID structureId, @PathVariable UUID itemId) {
        return file(parents.file(email, structureId, itemId));
    }

    @PostMapping("/documents/{structureId}/{documentId}/sign")
    public Map<String, Object> sign(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                    @PathVariable UUID structureId, @PathVariable UUID documentId,
                                    @Valid @RequestBody SignRequest request, HttpServletRequest http) {
        return parents.sign(email, structureId, documentId, "REFUSE".equals(request.decision()), http.getRemoteAddr());
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> messages(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                              @RequestParam UUID structureId, @RequestParam UUID familyId) {
        return parents.messages(email, structureId, familyId);
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> send(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                    @Valid @RequestBody MessageRequest request) {
        return parents.send(email, request.structureId(), request.familyId(), request.body());
    }

    @PostMapping("/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> absence(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                       @Valid @RequestBody AbsenceRequest request) {
        return parents.reportAbsence(email, request.structureId(), request.childId(), request.fromDay(), request.toDay(),
                request.reason());
    }

    /** Clé publique VAPID et catégories choisies pour cet appareil ({@code categories} vide : pas abonné). */
    @GetMapping("/push")
    public Map<String, Object> pushState(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                         @RequestParam(required = false) String endpoint) {
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("publicKey", push.publicKey());
        state.put("categories", endpoint == null ? null : push.categories(email, endpoint));
        return state;
    }

    @PostMapping("/push")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                          @Valid @RequestBody PushRequest request) {
        push.subscribe(email, request.endpoint(), request.p256dh(), request.auth(),
                nc.ticagou.portail.push.PushService.parseCategories(request.categories()));
    }

    @PostMapping("/push/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                            @Valid @RequestBody PushEndpoint request) {
        push.unsubscribe(email, request.endpoint());
    }

    /**
     * Fichier servi par plages ({@code Range}) : Safari sur iPhone ne lit une vidéo qu'ainsi (ADR 0019). Le contenu est
     * une {@link org.springframework.core.io.Resource}, que Spring découpe selon la plage demandée.
     */
    private static ResponseEntity<org.springframework.core.io.Resource> file(Content content) {
        var type = content.type() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(content.type());
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header("Accept-Ranges", "bytes")
                .body(new org.springframework.core.io.ByteArrayResource(content.bytes()));
    }
}
