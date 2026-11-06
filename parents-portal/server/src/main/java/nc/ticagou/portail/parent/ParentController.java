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

    private final ParentService parents;

    public ParentController(ParentService parents) {
        this.parents = parents;
    }

    @GetMapping
    public Map<String, Object> me(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email) {
        return parents.me(email);
    }

    @GetMapping("/children/{structureId}/{childId}/photo")
    public ResponseEntity<byte[]> childPhoto(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                             @PathVariable UUID structureId, @PathVariable UUID childId) {
        return file(parents.childPhoto(email, structureId, childId));
    }

    @GetMapping("/children/{structureId}/{childId}/day")
    public Map<String, Object> day(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
                                   @PathVariable UUID structureId, @PathVariable UUID childId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return parents.day(email, structureId, childId, date);
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
    public ResponseEntity<byte[]> file(@RequestAttribute(ParentSessionInterceptor.EMAIL) String email,
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

    private static ResponseEntity<byte[]> file(Content content) {
        var type = content.type() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(content.type());
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(content.bytes());
    }
}
