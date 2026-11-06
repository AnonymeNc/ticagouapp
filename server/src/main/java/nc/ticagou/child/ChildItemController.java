package nc.ticagou.child;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

/** Personnes autorisées et documents, adressés par leur propre identifiant. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Enfants")
public class ChildItemController {

    private final AuthorizedPersonService authorizedPersons;
    private final ChildDocumentService documents;

    public ChildItemController(AuthorizedPersonService authorizedPersons, ChildDocumentService documents) {
        this.authorizedPersons = authorizedPersons;
        this.documents = documents;
    }

    @PutMapping("/authorized-persons/{id}")
    @PreAuthorize(ChildController.EDITORS)
    @Operation(summary = "Modifier une personne autorisée (dont la vérification de la pièce d'identité)")
    public AuthorizedPersonDto updateAuthorizedPerson(@PathVariable UUID id,
                                                      @Valid @RequestBody AuthorizedPersonRequest request) {
        return authorizedPersons.update(id, request);
    }

    @DeleteMapping("/authorized-persons/{id}")
    @PreAuthorize(ChildController.EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer une personne autorisée")
    public void deleteAuthorizedPerson(@PathVariable UUID id) {
        authorizedPersons.delete(id);
    }

    @GetMapping("/authorized-persons/{id}/photo")
    @PreAuthorize(ChildController.READERS)
    @Operation(summary = "Photo d'une personne autorisée")
    public ResponseEntity<byte[]> authorizedPersonPhoto(@PathVariable UUID id) {
        var photo = authorizedPersons.photo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore()).body(photo.content());
    }

    @PutMapping(path = "/authorized-persons/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ChildController.EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remplacer la photo d'une personne autorisée")
    public void replaceAuthorizedPersonPhoto(@PathVariable UUID id, @RequestParam("file") MultipartFile file)
            throws IOException {
        authorizedPersons.replacePhoto(id, file.getBytes());
    }

    @GetMapping("/documents/{id}/content")
    @PreAuthorize(ChildController.READERS)
    @Operation(summary = "Télécharger un document (consultation journalisée)")
    public ResponseEntity<byte[]> documentContent(@PathVariable UUID id) {
        var download = documents.content(id);
        return ChildController.attachment(download.content(), download.contentType(), download.fileName());
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize(ChildController.EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un document")
    public void deleteDocument(@PathVariable UUID id) {
        documents.delete(id);
    }
}
