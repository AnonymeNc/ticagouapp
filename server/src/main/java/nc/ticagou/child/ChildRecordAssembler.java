package nc.ticagou.child;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import nc.ticagou.child.ChildDtos.AllergyDto;
import nc.ticagou.child.ChildDtos.AuthorizedPersonDto;
import nc.ticagou.child.ChildDtos.ChildRecord;
import nc.ticagou.child.ChildDtos.ConsentDto;
import nc.ticagou.child.ChildDtos.DocumentDto;
import nc.ticagou.child.ChildDtos.HealthDto;
import nc.ticagou.child.ChildDtos.VaccinationDto;
import nc.ticagou.common.ApiException;
import nc.ticagou.document.StoredFile;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.family.FamilyDtos.FamilyDto;
import nc.ticagou.family.FamilyDtos.SiblingDto;
import nc.ticagou.family.FamilyRepository;
import nc.ticagou.family.GuardianRepository;

/** Construit la fiche complète d'un enfant (consultation et export). Ne journalise rien. */
@Component
public class ChildRecordAssembler {

    private final ChildRepository children;
    private final FamilyRepository families;
    private final GuardianRepository guardians;
    private final AuthorizedPersonRepository authorizedPersons;
    private final HealthInfoRepository healthInfos;
    private final AllergyRepository allergies;
    private final VaccinationRepository vaccinations;
    private final ConsentRepository consents;
    private final ChildDocumentRepository documents;
    private final StoredFileRepository files;

    public ChildRecordAssembler(ChildRepository children, FamilyRepository families, GuardianRepository guardians,
                                AuthorizedPersonRepository authorizedPersons, HealthInfoRepository healthInfos,
                                AllergyRepository allergies, VaccinationRepository vaccinations,
                                ConsentRepository consents, ChildDocumentRepository documents,
                                StoredFileRepository files) {
        this.children = children;
        this.families = families;
        this.guardians = guardians;
        this.authorizedPersons = authorizedPersons;
        this.healthInfos = healthInfos;
        this.allergies = allergies;
        this.vaccinations = vaccinations;
        this.consents = consents;
        this.documents = documents;
        this.files = files;
    }

    public ChildRecord assemble(Child child) {
        return new ChildRecord(child.getId(), child.getFileNumber(), child.getFirstName(), child.getLastName(),
                child.getBirthDate(), child.getSectionId(), child.getEntryDate(), child.getExitDate(),
                child.getHomeLanguages(), child.getPhotoFileId() != null, child.getVersion(),
                family(child.getFamilyId()),
                authorizedPersons.findByChildIdOrderByLastNameAscFirstNameAsc(child.getId()).stream()
                        .map(AuthorizedPersonDto::of).toList(),
                health(child.getId()),
                consents.findByChildId(child.getId()).stream().map(ConsentDto::of).toList(),
                documents(child.getId()));
    }

    public FamilyDto family(UUID familyId) {
        var family = families.findById(familyId).orElseThrow(() -> ApiException.notFound("Famille introuvable."));
        var siblings = children.findByFamilyIdOrderByBirthDateAsc(familyId).stream()
                .map(c -> new SiblingDto(c.getId(), c.getFirstName(), c.getLastName(), c.getBirthDate(),
                        c.getExitDate()))
                .toList();
        return FamilyDto.of(family, guardians.findByFamilyIdOrderByDisplayOrderAscLastNameAsc(familyId), siblings);
    }

    public HealthDto health(UUID childId) {
        var allergyList = allergies.findByChildIdOrderByDisplayOrderAsc(childId).stream()
                .map(a -> new AllergyDto(a.getAllergen(), a.getReaction(), a.getSeverity())).toList();
        var vaccinationList = vaccinations.findByChildIdOrderByDisplayOrderAsc(childId).stream()
                .map(v -> new VaccinationDto(v.getVaccine(), v.getLastDoseOn())).toList();
        return healthInfos.findById(childId)
                .map(h -> new HealthDto(h.getDiet(), h.getDietDetails(), h.isPaiActive(), h.getPaiReason(),
                        h.getPaiValidFrom(), h.getPaiValidUntil(), h.getPaiKitLocation(), h.getPaiKitDetails(),
                        h.getPaiDocumentId(), h.getEmergencyProtocol(), h.getDoctorName(), h.getDoctorPhone(),
                        h.getDoctorAddress(), h.getInstructions(), h.getVaccinationCheckedOn(), allergyList,
                        vaccinationList, h.getDietId(), h.getMealTexture(), h.isPackedLunch()))
                .orElseGet(() -> new HealthDto(null, null, false, null, null, null, null, null, null, null, null,
                        null, null, null, null, allergyList, vaccinationList, null, "NORMALE", false));
    }

    public List<DocumentDto> documents(UUID childId) {
        var list = documents.findByChildIdOrderByCreatedAtDesc(childId);
        Map<UUID, StoredFile> stored = files.findAllById(list.stream().map(ChildDocument::getFileId).toList())
                .stream().collect(Collectors.toMap(StoredFile::getId, Function.identity()));
        return list.stream().map(d -> {
            var file = stored.get(d.getFileId());
            return new DocumentDto(d.getId(), d.getName(), d.getFileName(), d.getCategory(), d.getValidUntil(),
                    d.getCreatedAt(), d.getCreatedBy(), file.getContentType(), file.getSizeBytes());
        }).toList();
    }
}
