package nc.ticagou.dataimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.dhatim.fastexcel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.Validator;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.AuthorizedPersonService;
import nc.ticagou.child.ChildDtos.CreateChildRequest;
import nc.ticagou.child.ChildHealthService;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.ChildService;
import nc.ticagou.common.ApiException;
import nc.ticagou.dataimport.ImportDtos.ChildReview;
import nc.ticagou.dataimport.ImportDtos.Counts;
import nc.ticagou.dataimport.ImportDtos.Preview;
import nc.ticagou.dataimport.ImportPlanner.Plan;
import nc.ticagou.dataimport.ImportPlanner.PlannedChild;
import nc.ticagou.dataimport.ImportPlanner.PlannedFamily;
import nc.ticagou.family.FamilyDtos.GuardianRequest;
import nc.ticagou.family.FamilyService;
import nc.ticagou.structure.Section;
import nc.ticagou.structure.SectionRepository;

/**
 * Reprise des données d'une crèche (ADR 0016) : modèle à remplir, aperçu sans écriture, puis import tout ou rien à travers les
 * services de saisie. Le fichier reste en mémoire le temps de la requête.
 */
@Service
public class ImportService {

    public record Template(byte[] content, String fileName) {
    }

    private final SectionRepository sections;
    private final ChildRepository childRepository;
    private final FamilyService families;
    private final ChildService children;
    private final ChildHealthService health;
    private final AuthorizedPersonService authorizedPersons;
    private final AuditService audit;
    private final Validator validator;
    private final Clock clock;

    public ImportService(SectionRepository sections, ChildRepository childRepository, FamilyService families,
                         ChildService children, ChildHealthService health, AuthorizedPersonService authorizedPersons,
                         AuditService audit, Validator validator, Clock clock) {
        this.sections = sections;
        this.childRepository = childRepository;
        this.families = families;
        this.children = children;
        this.health = health;
        this.authorizedPersons = authorizedPersons;
        this.audit = audit;
        this.validator = validator;
        this.clock = clock;
    }

    /** Classeur vide, une feuille par type de données ; rappelle les sections de la structure. */
    @Transactional(readOnly = true)
    public Template template() {
        var names = structureSections().stream().map(Section::getName).toList();
        var out = new ByteArrayOutputStream();
        try {
            var workbook = new Workbook(out, "Ti'Cagou", "1.0");
            var readme = workbook.newWorksheet(ImportModel.README);
            var lines = List.of(
                    "Reprise des données dans Ti’Cagou",
                    "",
                    "1. Remplissez les feuilles, une ligne par famille, responsable, enfant, allergie… Les colonnes marquées d’une étoile sont obligatoires.",
                    "2. Choisissez une référence libre pour chaque famille et chaque enfant (F1, E1…) : elle relie les feuilles entre elles et n’est pas conservée.",
                    "3. Écrivez les dates au format JJ/MM/AAAA, et oui ou non dans les colonnes concernées.",
                    "4. Une allergie par ligne, avec sa gravité : Légère, Modérée ou Sévère. N’écrivez rien pour un enfant sans allergie.",
                    "5. Déposez le fichier dans Paramètres › Reprise des données : rien n’est importé avant votre relecture.",
                    "",
                    "Sections de votre crèche : " + String.join(", ", names),
                    "",
                    "Ce fichier contient des données de santé d’enfants : ne l’envoyez pas par e-mail, et supprimez-le après la reprise.");
            for (int i = 0; i < lines.size(); i++) {
                readme.value(i, 0, lines.get(i));
            }
            readme.style(0, 0).bold().fontSize(13).set();
            readme.width(0, 120);
            for (var model : ImportModel.SHEETS) {
                var sheet = workbook.newWorksheet(model.name());
                var columns = model.columns();
                for (int c = 0; c < columns.size(); c++) {
                    sheet.value(0, c, columns.get(c).header());
                    sheet.width(c, Math.max(14, columns.get(c).header().length() + 3));
                }
                sheet.range(0, 0, 0, columns.size() - 1).style().bold().fillColor("DCEFEE").set();
                sheet.freezePane(0, 1);
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Template(out.toByteArray(), "reprise-ticagou.xlsx");
    }

    /** Contrôle le fichier sans rien écrire. */
    @Transactional(readOnly = true)
    public Preview preview(byte[] content) {
        var plan = analyse(content);
        return new Preview(hash(content), counts(plan.families()), plan.errors(), plan.warnings(), reviews(plan.families()));
    }

    /** Importe le fichier relu : il doit être celui de l'aperçu, et sans erreur. */
    @Transactional
    public Counts commit(byte[] content, String fileHash, boolean healthReviewed) {
        if (!healthReviewed) {
            throw ApiException.rule("import_not_reviewed",
                    "Relisez les allergies, les PAI et les personnes autorisées avant d’importer.");
        }
        var hash = hash(content);
        if (!hash.equals(fileHash)) {
            throw ApiException.conflict("import_file_changed", "Le fichier a changé depuis l’aperçu : analysez-le de nouveau.");
        }
        var plan = analyse(content);
        if (!plan.errors().isEmpty()) {
            throw ApiException.rule("import_invalid", "Le fichier contient des erreurs : analysez-le de nouveau.");
        }
        plan.families().forEach(this::create);
        var counts = counts(plan.families());
        audit.record(AuditAction.DATA_IMPORTED, "structure", CurrentUser.get().structureId(), Map.of(
                "sha256", hash, "families", counts.families(), "children", counts.children(),
                "allergies", counts.allergies(), "pais", counts.pais(),
                "authorizedPersons", counts.authorizedPersons(), "healthReviewed", true));
        return counts;
    }

    private void create(PlannedFamily planned) {
        var family = families.create(planned.family(), planned.guardians().getFirst());
        planned.guardians().stream().skip(1).forEach(g -> families.addGuardian(family.getId(), g));
        for (var child : planned.children()) {
            var childId = children.create(new CreateChildRequest(child.identity(), family.getId(), null));
            if (child.health() != null) {
                health.update(childId, child.health());
            }
            if (!child.consents().isEmpty()) {
                health.updateConsents(childId, child.consents());
            }
            child.authorizedPersons().forEach(person -> authorizedPersons.create(childId, person));
        }
    }

    private Plan analyse(byte[] content) {
        var file = ImportReader.read(content);
        Map<String, UUID> sectionIds = structureSections().stream()
                .collect(Collectors.toMap(s -> ImportModel.key(s.getName()), Section::getId, (first, second) -> first));
        var existing = childRepository.findByStructureId(CurrentUser.get().structureId()).stream()
                .map(c -> ImportPlanner.childKey(c.getFirstName(), c.getLastName(), c.getBirthDate()))
                .collect(Collectors.toSet());
        return new ImportPlanner(validator, sectionIds, existing, LocalDate.now(clock)).plan(file);
    }

    private List<Section> structureSections() {
        return sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(CurrentUser.get().structureId());
    }

    private static Counts counts(List<PlannedFamily> planned) {
        var all = planned.stream().flatMap(f -> f.children().stream()).toList();
        return new Counts(planned.size(), planned.stream().mapToInt(f -> f.guardians().size()).sum(), all.size(),
                all.stream().filter(c -> c.health() != null).mapToInt(c -> c.health().allergies().size()).sum(),
                (int) all.stream().filter(c -> c.health() != null && c.health().paiActive()).count(),
                all.stream().mapToInt(c -> c.authorizedPersons().size()).sum());
    }

    private static List<ChildReview> reviews(List<PlannedFamily> planned) {
        var reviews = new ArrayList<ChildReview>();
        for (var family : planned) {
            var legal = family.guardians().stream().filter(GuardianRequest::legalGuardian)
                    .map(g -> "%s %s (%s)".formatted(g.firstName(), g.lastName(), g.relationship())).toList();
            family.children().forEach(child -> reviews.add(review(child, legal)));
        }
        return reviews;
    }

    private static ChildReview review(PlannedChild child, List<String> legalGuardians) {
        var pickup = new ArrayList<>(legalGuardians);
        child.authorizedPersons().forEach(p -> pickup.add("%s %s (%s)".formatted(p.firstName(), p.lastName(), p.relationship())));
        var h = child.health();
        var identity = child.identity();
        return new ChildReview(child.reference(), identity.firstName(), identity.lastName(), identity.birthDate(),
                child.section(), h == null ? List.of() : h.allergies(), h != null && h.paiActive(),
                h == null ? null : h.paiReason(), h == null ? null : h.diet(), pickup);
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
