package nc.ticagou.child;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.child.ChildDtos.AuthorizedPersonDto;
import nc.ticagou.child.ChildDtos.AuthorizedPersonRequest;
import nc.ticagou.child.ChildDtos.ChildIdentityRequest;
import nc.ticagou.child.ChildDtos.ChildListItem;
import nc.ticagou.child.ChildDtos.ChildRecord;
import nc.ticagou.child.ChildDtos.ConsentDto;
import nc.ticagou.child.ChildDtos.ConsentsRequest;
import nc.ticagou.child.ChildDtos.CreateChildRequest;
import nc.ticagou.child.ChildDtos.Created;
import nc.ticagou.child.ChildDtos.DocumentDto;
import nc.ticagou.child.ChildDtos.HealthDto;

/**
 * Module Enfants. Droits (maquette Paramètres › Droits par rôle) : la direction modifie,
 * l'éducatrice consulte, la cuisine ne voit que la liste (allergies et régimes), la comptabilité n'a pas accès.
 */
@RestController
@RequestMapping("/api/v1/children")
@Tag(name = "Enfants", description = "Identité, santé, autorisations, personnes autorisées, documents")
public class ChildController {

    static final String READERS = "hasAnyRole('DIRECTION', 'EDUCATRICE')";
    static final String EDITORS = "hasRole('DIRECTION')";

    private final ChildService children;
    private final ChildHealthService health;
    private final AuthorizedPersonService authorizedPersons;
    private final ChildDocumentService documents;

    public ChildController(ChildService children, ChildHealthService health, AuthorizedPersonService authorizedPersons,
                           ChildDocumentService documents) {
        this.children = children;
        this.health = health;
        this.authorizedPersons = authorizedPersons;
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE')")
    @Operation(summary = "Liste des enfants avec leurs informations de santé critiques")
    public List<ChildListItem> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) UUID sectionId,
                                    @RequestParam(defaultValue = "false") boolean withAllergy,
                                    @RequestParam(defaultValue = "false") boolean includeExited) {
        return children.list(q, sectionId, withAllergy, includeExited);
    }

    @PostMapping
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un enfant, rattaché à une famille existante ou nouvelle")
    public Created create(@Valid @RequestBody CreateChildRequest request) {
        return new Created(children.create(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    @Operation(summary = "Fiche complète (consultation journalisée)")
    public ChildRecord record(@PathVariable UUID id) {
        return children.record(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Modifier l'identité et l'accueil")
    public void update(@PathVariable UUID id, @Valid @RequestBody ChildIdentityRequest request) {
        children.updateIdentity(id, request);
    }

    @GetMapping("/{id}/photo")
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE')")
    @Operation(summary = "Photo de l'enfant")
    public ResponseEntity<byte[]> photo(@PathVariable UUID id) {
        var photo = children.photo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore()).body(photo.content());
    }

    @PutMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remplacer la photo (JPEG ou PNG, 2 Mo maximum)")
    public void replacePhoto(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        children.replacePhoto(id, file.getBytes());
    }

    @DeleteMapping("/{id}/photo")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer la photo")
    public void removePhoto(@PathVariable UUID id) {
        children.removePhoto(id);
    }

    @PutMapping("/{id}/health")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Remplacer les informations de santé (allergies, régime, PAI, vaccins, médecin)")
    public HealthDto updateHealth(@PathVariable UUID id, @Valid @RequestBody HealthDto request) {
        return health.update(id, request);
    }

    @PutMapping("/{id}/consents")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Enregistrer les autorisations parentales")
    public List<ConsentDto> updateConsents(@PathVariable UUID id, @Valid @RequestBody ConsentsRequest request) {
        return health.updateConsents(id, request.consents());
    }

    @PostMapping("/{id}/authorized-persons")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une personne autorisée à venir chercher l'enfant")
    public AuthorizedPersonDto addAuthorizedPerson(@PathVariable UUID id,
                                                   @Valid @RequestBody AuthorizedPersonRequest request) {
        return authorizedPersons.create(id, request);
    }

    @PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Joindre un document (PDF, JPEG ou PNG, 10 Mo maximum ; chiffré sur le serveur)")
    public DocumentDto addDocument(@PathVariable UUID id,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam("name") String name,
                                   @RequestParam("category") ChildDocument.Category category,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate validUntil) throws IOException {
        return documents.add(id, file.getBytes(), file.getOriginalFilename(), name, category, validUntil);
    }

    static ResponseEntity<byte[]> attachment(byte[] content, String contentType, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }
}
