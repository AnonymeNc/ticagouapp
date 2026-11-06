package nc.ticagou.child;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.ChildDtos.AuthorizedPersonDto;
import nc.ticagou.child.ChildDtos.AuthorizedPersonRequest;
import nc.ticagou.common.ApiException;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;

/** Personnes autorisées à venir chercher un enfant. */
@Service
public class AuthorizedPersonService {

    private final ChildService children;
    private final AuthorizedPersonRepository persons;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final AuditService audit;
    private final Clock clock;

    public AuthorizedPersonService(ChildService children, AuthorizedPersonRepository persons,
                                   EncryptedFileStore fileStore, StoredFileRepository files, AuditService audit,
                                   Clock clock) {
        this.children = children;
        this.persons = persons;
        this.fileStore = fileStore;
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuthorizedPersonDto create(UUID childId, AuthorizedPersonRequest request) {
        var child = children.load(childId);
        var person = new AuthorizedPerson(child.getStructureId(), childId);
        apply(person, request);
        persons.saveAndFlush(person);
        audit.record(AuditAction.AUTHORIZED_PERSON_CREATED, "authorized_person", person.getId(),
                Map.of("child", childId));
        return AuthorizedPersonDto.of(person);
    }

    @Transactional
    public AuthorizedPersonDto update(UUID id, AuthorizedPersonRequest request) {
        var person = load(id);
        apply(person, request);
        persons.saveAndFlush(person);
        audit.record(AuditAction.AUTHORIZED_PERSON_UPDATED, "authorized_person", id,
                Map.of("child", person.getChildId(), "idVerified", request.idVerified()));
        return AuthorizedPersonDto.of(person);
    }

    @Transactional
    public void delete(UUID id) {
        var person = load(id);
        var photo = person.getPhotoFileId();
        persons.delete(person);
        persons.flush();
        if (photo != null) {
            files.findById(photo).ifPresent(fileStore::delete);
        }
        audit.record(AuditAction.AUTHORIZED_PERSON_DELETED, "authorized_person", id,
                Map.of("child", person.getChildId()));
    }

    @Transactional(readOnly = true)
    public ChildService.Photo photo(UUID id) {
        var person = load(id);
        if (person.getPhotoFileId() == null) {
            throw ApiException.notFound("Aucune photo enregistrée.");
        }
        var file = files.findById(person.getPhotoFileId()).orElseThrow();
        return new ChildService.Photo(fileStore.read(file), file.getContentType());
    }

    @Transactional
    public void replacePhoto(UUID id, byte[] content) {
        var person = load(id);
        var type = Uploads.checkPhoto(content);
        var previous = person.getPhotoFileId();
        person.setPhotoFileId(fileStore.store(person.getStructureId(), content, type).getId());
        persons.saveAndFlush(person);
        if (previous != null) {
            files.findById(previous).ifPresent(fileStore::delete);
        }
        audit.record(AuditAction.AUTHORIZED_PERSON_UPDATED, "authorized_person", id, Map.of("photo", true));
    }

    private void apply(AuthorizedPerson person, AuthorizedPersonRequest r) {
        person.update(r.firstName().trim(), r.lastName().trim(), r.relationship().trim(),
                ChildService.blankToNull(r.phone()));
        if (!r.idVerified()) {
            person.clearIdVerification();
        } else if (person.getIdVerifiedOn() == null) {
            // Vérification constatée maintenant, par l'utilisateur connecté.
            person.markIdVerified(LocalDate.now(clock), CurrentUser.get().displayName());
        }
    }

    private AuthorizedPerson load(UUID id) {
        return persons.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Personne autorisée introuvable."));
    }
}
