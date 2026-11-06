package nc.ticagou.family;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import nc.ticagou.portal.PortalFamilyData;
import nc.ticagou.attendance.AttendanceArchive;
import nc.ticagou.attendance.AttendanceArchive.AttendanceRecord;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.meal.MealAdjustmentRepository;
import nc.ticagou.meal.MealNoteRepository;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.ChildAccessContext;
import nc.ticagou.audit.ChildAccessLogger;
import nc.ticagou.child.AllergyRepository;
import nc.ticagou.child.AuthorizedPerson;
import nc.ticagou.child.AuthorizedPersonRepository;
import nc.ticagou.billing.BillingFamilyData;
import nc.ticagou.billing.BillingFamilyData.BillingExport;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildDocument;
import nc.ticagou.child.ChildDocumentRepository;
import nc.ticagou.child.ChildDtos.ChildRecord;
import nc.ticagou.child.ChildRecordAssembler;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.ConsentRepository;
import nc.ticagou.child.HealthInfoRepository;
import nc.ticagou.child.VaccinationRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.contract.Contract;
import nc.ticagou.contract.ContractDtos.ContractDto;
import nc.ticagou.contract.ContractRepository;
import nc.ticagou.contract.ContractService;
import nc.ticagou.contract.ContractVersionRepository;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;
import nc.ticagou.enrollment.EnrollmentDtos.RequestRecord;
import nc.ticagou.enrollment.EnrollmentEventRepository;
import nc.ticagou.enrollment.EnrollmentNoteRepository;
import nc.ticagou.enrollment.EnrollmentRequest;
import nc.ticagou.enrollment.EnrollmentRequestRepository;
import nc.ticagou.enrollment.EnrollmentService;
import nc.ticagou.family.FamilyDtos.FamilyDto;

/**
 * Droits des familles sur leurs données : export complet (ZIP lisible : JSON + documents déchiffrés)
 * et purge définitive à la sortie, demandes d'inscription, contrats et pointages compris. Les deux opérations sont tracées.
 */
@Service
public class FamilyDataService {

    private final FamilyService families;
    private final FamilyRepository familyRepository;
    private final GuardianRepository guardians;
    private final ChildRepository children;
    private final AuthorizedPersonRepository authorizedPersons;
    private final ChildDocumentRepository documents;
    private final HealthInfoRepository healthInfos;
    private final AllergyRepository allergies;
    private final VaccinationRepository vaccinations;
    private final ConsentRepository consents;
    private final StoredFileRepository files;
    private final EncryptedFileStore fileStore;
    private final ChildRecordAssembler assembler;
    private final AuditService audit;
    private final ChildAccessLogger accessLog;
    private final ContractRepository contracts;
    private final ContractVersionRepository contractVersions;
    private final ContractService contractService;
    private final EnrollmentRequestRepository enrollmentRequests;
    private final EnrollmentNoteRepository enrollmentNotes;
    private final EnrollmentEventRepository enrollmentEvents;
    private final EnrollmentService enrollmentService;
    private final AttendanceArchive attendanceArchive;
    private final MealAdjustmentRepository mealAdjustments;
    private final MealNoteRepository mealNotes;
    private final BillingFamilyData billing;
    private final PortalFamilyData portalData;
    private final ObjectMapper json;
    private final Clock clock;

    public FamilyDataService(FamilyService families, FamilyRepository familyRepository, GuardianRepository guardians,
                             ChildRepository children, AuthorizedPersonRepository authorizedPersons,
                             ChildDocumentRepository documents, HealthInfoRepository healthInfos,
                             AllergyRepository allergies, VaccinationRepository vaccinations,
                             ConsentRepository consents, StoredFileRepository files, EncryptedFileStore fileStore,
                             ChildRecordAssembler assembler, AuditService audit, ChildAccessLogger accessLog,
                             ContractRepository contracts, ContractVersionRepository contractVersions,
                             ContractService contractService, EnrollmentRequestRepository enrollmentRequests,
                             EnrollmentNoteRepository enrollmentNotes, EnrollmentEventRepository enrollmentEvents,
                             EnrollmentService enrollmentService, AttendanceArchive attendanceArchive,
                             MealAdjustmentRepository mealAdjustments, MealNoteRepository mealNotes,
                             BillingFamilyData billing, PortalFamilyData portalData,
                             ObjectMapper json, Clock clock) {
        this.mealAdjustments = mealAdjustments;
        this.mealNotes = mealNotes;
        this.billing = billing;
        this.portalData = portalData;
        this.families = families;
        this.familyRepository = familyRepository;
        this.guardians = guardians;
        this.children = children;
        this.authorizedPersons = authorizedPersons;
        this.documents = documents;
        this.healthInfos = healthInfos;
        this.allergies = allergies;
        this.vaccinations = vaccinations;
        this.consents = consents;
        this.files = files;
        this.fileStore = fileStore;
        this.assembler = assembler;
        this.audit = audit;
        this.accessLog = accessLog;
        this.contracts = contracts;
        this.contractVersions = contractVersions;
        this.contractService = contractService;
        this.enrollmentRequests = enrollmentRequests;
        this.enrollmentNotes = enrollmentNotes;
        this.enrollmentEvents = enrollmentEvents;
        this.enrollmentService = enrollmentService;
        this.attendanceArchive = attendanceArchive;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
    }

    /** Contenu de l'export, sérialisé dans famille.json. */
    public record FamilyExport(Instant exportedAt, FamilyDto family, List<ChildRecord> children,
                               List<ContractDto> contracts, List<RequestRecord> enrollmentRequests,
                               List<AttendanceRecord> attendances, List<MealCorrection> mealCorrections,
                               BillingExport billing) {
    }

    /** Correction manuelle du comptage des repas d'un enfant. */
    public record MealCorrection(UUID childId, java.time.LocalDate day, String meal, boolean served, String reason) {
    }

    public record Archive(byte[] content, String fileName) {
    }

    @Transactional
    public Archive export(UUID familyId) {
        var family = families.load(familyId);
        var familyChildren = children.findByFamilyIdOrderByBirthDateAsc(familyId);
        var records = familyChildren.stream().map(assembler::assemble).toList();
        var familyContracts = familyChildren.stream().flatMap(c -> contractService.forChild(c.getId()).stream()).toList();
        var requests = enrollmentRequests.findByFamilyOrChildren(familyId, idsOrNone(familyChildren.stream().map(Child::getId).toList()))
                .stream().map(r -> enrollmentService.record(r.getId())).toList();
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            write(zip, "famille.json", json.writeValueAsBytes(
                    new FamilyExport(clock.instant(), assembler.family(familyId), records, familyContracts, requests,
                            attendanceArchive.export(familyChildren.stream().map(Child::getId).toList()),
                            mealAdjustments.findByChildIdInOrderByDayAsc(idsOrNone(familyChildren.stream().map(Child::getId).toList()))
                                    .stream().map(a -> new MealCorrection(a.getChildId(), a.getDay(), a.getMeal().name(),
                                            a.isServed(), a.getReason())).toList(),
                            billing.export(familyId, familyChildren.stream().map(Child::getId).toList()))));
            write(zip, "portail.json", json.writeValueAsBytes(portalData.export(family.getStructureId(), familyId,
                    familyChildren.stream().map(Child::getId).toList())));
            for (var child : familyChildren) {
                var folder = child.getFileNumber() + "/";
                if (child.getPhotoFileId() != null) {
                    var photo = files.findById(child.getPhotoFileId()).orElseThrow();
                    write(zip, folder + "photo." + Uploads.extension(photo.getContentType()), fileStore.read(photo));
                }
                for (var person : authorizedPersons.findByChildIdOrderByLastNameAscFirstNameAsc(child.getId())) {
                    if (person.getPhotoFileId() != null) {
                        var photo = files.findById(person.getPhotoFileId()).orElseThrow();
                        write(zip, folder + "personnes-autorisees/" + person.getId() + "."
                                + Uploads.extension(photo.getContentType()), fileStore.read(photo));
                    }
                }
                int index = 1;
                for (var document : documents.findByChildIdOrderByCreatedAtDesc(child.getId())) {
                    var file = files.findById(document.getFileId()).orElseThrow();
                    write(zip, folder + "documents/%02d-%s".formatted(index++, document.getFileName()),
                            fileStore.read(file));
                }
                accessLog.log(child.getId(), ChildAccessContext.EXPORT);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.record(AuditAction.FAMILY_EXPORTED, "family", familyId, Map.of("children", familyChildren.size()));
        var name = "export-" + FamilyService.normalize(family.getLabel()).replaceAll("[^a-z0-9]+", "-")
                + "-" + LocalDate.now(clock) + ".zip";
        return new Archive(buffer.toByteArray(), name);
    }

    /**
     * Supprime définitivement le foyer, ses responsables, ses enfants et tout ce qui s'y rattache
     * (santé, autorisations, personnes autorisées, documents et photos chiffrés). Exige la saisie du
     * libellé de la famille et que tous les enfants soient sortis. Les journaux d'audit restent : ils ne
     * contiennent que des identifiants.
     */
    @Transactional
    public void purge(UUID familyId, String confirmation) {
        var family = families.load(familyId);
        if (!family.getLabel().trim().equalsIgnoreCase(confirmation.trim())) {
            throw ApiException.rule("confirmation_mismatch",
                    "Pour confirmer, saisissez exactement le nom de la famille : « %s ».".formatted(family.getLabel()));
        }
        // Obligation de conservation comptable : factures et paiements ne se suppriment jamais.
        if (billing.hasAccountingRecords(familyId)) {
            throw ApiException.rule("family_has_invoices",
                    "Cette famille a des factures ou des paiements : ce sont des pièces comptables à conserver. Exportez ses données ; la suppression n’est pas possible.");
        }
        var today = LocalDate.now(clock);
        var familyChildren = children.findByFamilyIdOrderByBirthDateAsc(familyId);
        if (familyChildren.stream().anyMatch(c -> c.isEnrolledOn(today))) {
            throw ApiException.rule("family_active",
                    "Enregistrez d’abord la date de sortie de chaque enfant : seules les familles sorties peuvent être supprimées.");
        }

        var childIds = familyChildren.stream().map(Child::getId).toList();
        var persons = authorizedPersons.findByChildIdIn(childIds);
        var childDocuments = documents.findByChildIdIn(childIds);
        var fileIds = Stream.of(
                        familyChildren.stream().map(Child::getPhotoFileId),
                        persons.stream().map(AuthorizedPerson::getPhotoFileId),
                        childDocuments.stream().map(ChildDocument::getFileId))
                .flatMap(s -> s).filter(Objects::nonNull).toList();
        var storedFiles = new ArrayList<>(files.findAllById(fileIds));

        // Demandes et contrats d'abord : ils référencent les enfants, la famille et les contrats signés numérisés.
        var requests = enrollmentRequests.findByFamilyOrChildren(familyId, idsOrNone(childIds));
        var requestIds = requests.stream().map(EnrollmentRequest::getId).toList();
        var familyContracts = new ArrayList<>(contracts.findByChildIdIn(idsOrNone(childIds)));
        contracts.findByRequestIdIn(idsOrNone(requestIds)).stream()
                .filter(c -> familyContracts.stream().noneMatch(f -> f.getId().equals(c.getId())))
                .forEach(familyContracts::add);
        var contractIds = familyContracts.stream().map(Contract::getId).toList();
        if (!contractIds.isEmpty()) {
            contractVersions.deleteAll(contractVersions.findByContractIdIn(contractIds));
            contractVersions.flush();
            contracts.deleteAll(familyContracts);
            contracts.flush();
        }
        if (!requestIds.isEmpty()) {
            enrollmentNotes.deleteByRequestIdIn(requestIds);
            enrollmentEvents.deleteByRequestIdIn(requestIds);
            enrollmentRequests.deleteAll(enrollmentRequests.findAllById(requestIds));
            enrollmentRequests.flush();
        }

        portalData.purge(family.getStructureId(), familyId, childIds);
        int attendanceCount = attendanceArchive.purge(childIds);
        if (!childIds.isEmpty()) {
            mealAdjustments.deleteByChildIdIn(childIds);
            mealNotes.deleteByChildIdIn(childIds);
            billing.purge(childIds);
        }

        // Ordre imposé par les clés étrangères (le PAI référence un document, les documents des fichiers…).
        if (!childIds.isEmpty()) {
            healthInfos.deleteByChildIdIn(childIds);
            allergies.deleteByChildIdIn(childIds);
            vaccinations.deleteByChildIdIn(childIds);
            consents.deleteByChildIdIn(childIds);
            authorizedPersons.deleteByChildIdIn(childIds);
            documents.deleteByChildIdIn(childIds);
            children.deleteByIdIn(childIds);
        }
        guardians.deleteByFamilyId(familyId);
        familyRepository.deleteById(familyId);
        familyRepository.flush();
        fileStore.delete(storedFiles);

        audit.record(AuditAction.FAMILY_PURGED, "family", familyId, Map.of(
                "children", childIds.size(), "documents", childDocuments.size(), "files", storedFiles.size(),
                "contracts", contractIds.size(), "enrollmentRequests", requestIds.size(), "attendances", attendanceCount));
    }

    /** Une liste vide dans une clause « in » n'est pas portable : on la remplace par un identifiant inexistant. */
    private static List<UUID> idsOrNone(List<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }

    private static void write(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
