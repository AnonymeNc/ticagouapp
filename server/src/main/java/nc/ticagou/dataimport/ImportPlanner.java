package nc.ticagou.dataimport;

import static nc.ticagou.dataimport.ImportModel.key;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import nc.ticagou.child.Allergy;
import nc.ticagou.child.ChildDtos.AllergyDto;
import nc.ticagou.child.ChildDtos.AuthorizedPersonRequest;
import nc.ticagou.child.ChildDtos.ChildIdentityRequest;
import nc.ticagou.child.ChildDtos.ConsentDto;
import nc.ticagou.child.ChildDtos.HealthDto;
import nc.ticagou.child.ChildDtos.VaccinationDto;
import nc.ticagou.child.Consent;
import nc.ticagou.common.Formats;
import nc.ticagou.dataimport.ImportDtos.Issue;
import nc.ticagou.dataimport.ImportModel.SheetModel;
import nc.ticagou.family.FamilyDtos.FamilyRequest;
import nc.ticagou.family.FamilyDtos.GuardianRequest;

/**
 * Contrôle un fichier de reprise et en tire ce qu'il faut créer, sans rien écrire (ADR 0016). Les règles de forme sont celles
 * des formulaires (mêmes DTO). Rien n'est deviné sur la santé : un doute est une erreur, à corriger dans le fichier.
 */
final class ImportPlanner {

    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter.ofPattern("d/M/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> YES = Set.of("oui", "o", "x", "vrai", "true", "yes", "1");
    private static final Set<String> NO = Set.of("non", "n", "faux", "false", "no", "0");
    /** Mentions qui disent l'absence d'allergie : en faire une allergie afficherait une fausse alerte partout. */
    private static final Set<String> NO_ALLERGY = Set.of("aucune", "aucun", "neant", "ras", "non", "nc", "sansobjet", "");
    private static final Map<String, Allergy.Severity> SEVERITIES = Map.of(
            "legere", Allergy.Severity.LEGERE, "leger", Allergy.Severity.LEGERE,
            "moderee", Allergy.Severity.MODEREE, "modere", Allergy.Severity.MODEREE,
            "severe", Allergy.Severity.SEVERE, "grave", Allergy.Severity.SEVERE);
    /** Numéros de série Excel plausibles : 1927 à 2173. Un nombre plus petit est une année ou une faute de frappe. */
    private static final int MIN_SERIAL = 10_000;
    private static final int MAX_SERIAL = 100_000;

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("label", ImportModel.LABEL), Map.entry("address", ImportModel.ADDRESS),
            Map.entry("postalCode", ImportModel.POSTAL_CODE), Map.entry("city", ImportModel.CITY),
            Map.entry("situation", ImportModel.SITUATION), Map.entry("notes", ImportModel.NOTES),
            Map.entry("firstName", ImportModel.FIRST_NAME), Map.entry("lastName", ImportModel.LAST_NAME),
            Map.entry("relationship", ImportModel.RELATIONSHIP), Map.entry("mobilePhone", ImportModel.MOBILE_PHONE),
            Map.entry("workPhone", ImportModel.WORK_PHONE), Map.entry("email", ImportModel.EMAIL),
            Map.entry("employer", ImportModel.EMPLOYER), Map.entry("birthDate", ImportModel.BIRTH_DATE),
            Map.entry("homeLanguages", ImportModel.LANGUAGES), Map.entry("diet", ImportModel.DIET),
            Map.entry("dietDetails", ImportModel.DIET_DETAILS), Map.entry("paiReason", ImportModel.PAI_REASON),
            Map.entry("paiKitLocation", ImportModel.PAI_KIT_LOCATION),
            Map.entry("paiKitDetails", ImportModel.PAI_KIT_DETAILS),
            Map.entry("emergencyProtocol", ImportModel.EMERGENCY_PROTOCOL),
            Map.entry("doctorName", ImportModel.DOCTOR_NAME), Map.entry("doctorPhone", ImportModel.DOCTOR_PHONE),
            Map.entry("doctorAddress", ImportModel.DOCTOR_ADDRESS), Map.entry("instructions", ImportModel.INSTRUCTIONS),
            Map.entry("allergen", ImportModel.ALLERGEN), Map.entry("reaction", ImportModel.REACTION),
            Map.entry("vaccine", ImportModel.VACCINE), Map.entry("lastDoseOn", ImportModel.LAST_DOSE),
            Map.entry("phone", ImportModel.PHONE), Map.entry("signedOn", ImportModel.SIGNED_ON),
            Map.entry("signedBy", ImportModel.SIGNED_BY));

    private final Validator validator;
    /** Identifiant des sections de la structure, par clé de nom. */
    private final Map<String, UUID> sections;
    /** Enfants déjà présents ({@link #childKey}). */
    private final Set<String> existingChildren;
    private final LocalDate today;

    private final List<Issue> errors = new ArrayList<>();
    private final List<Issue> warnings = new ArrayList<>();
    private final Map<String, FamilyDraft> families = new LinkedHashMap<>();
    private final Map<String, ChildDraft> children = new LinkedHashMap<>();
    private LocalDate dateOrigin;

    ImportPlanner(Validator validator, Map<String, UUID> sections, Set<String> existingChildren, LocalDate today) {
        this.validator = validator;
        this.sections = sections;
        this.existingChildren = existingChildren;
        this.today = today;
    }

    static String childKey(String firstName, String lastName, LocalDate birthDate) {
        return key(firstName) + "|" + key(lastName) + "|" + birthDate;
    }

    record PlannedChild(String reference, ChildIdentityRequest identity, String section, HealthDto health,
                        List<ConsentDto> consents, List<AuthorizedPersonRequest> authorizedPersons) {
    }

    /** {@code guardians} : responsables légaux d'abord, le premier crée le foyer. */
    record PlannedFamily(FamilyRequest family, List<GuardianRequest> guardians, List<PlannedChild> children) {
    }

    record Plan(List<PlannedFamily> families, List<Issue> errors, List<Issue> warnings) {
    }

    Plan plan(ImportFile file) {
        dateOrigin = file.dateOrigin();
        checkLayout(file);
        if (!errors.isEmpty()) {
            return new Plan(List.of(), errors, warnings);
        }
        lines(file, ImportModel.FAMILIES).forEach(this::readFamily);
        lines(file, ImportModel.GUARDIANS).forEach(this::readGuardian);
        var seen = new HashSet<String>();
        lines(file, ImportModel.CHILDREN).forEach(line -> readChild(line, seen));
        lines(file, ImportModel.HEALTH).forEach(this::readHealth);
        lines(file, ImportModel.ALLERGIES).forEach(this::readAllergy);
        lines(file, ImportModel.VACCINES).forEach(this::readVaccine);
        lines(file, ImportModel.AUTHORIZED_PERSONS).forEach(this::readAuthorizedPerson);
        lines(file, ImportModel.CONSENTS).forEach(this::readConsents);
        families.values().forEach(this::checkFamily);
        if (children.isEmpty() && errors.isEmpty()) {
            errors.add(new Issue(ImportModel.CHILDREN.name(), null, "Aucun enfant dans cette feuille."));
        }
        return new Plan(errors.isEmpty() ? families.values().stream().map(FamilyDraft::planned).toList() : List.of(),
                errors, warnings);
    }

    // --- Forme du classeur ---------------------------------------------------------

    private void checkLayout(ImportFile file) {
        for (var model : ImportModel.SHEETS) {
            var sheet = file.sheet(model.name());
            if (sheet.isEmpty()) {
                if (model.required()) {
                    errors.add(new Issue(model.name(), null, "Feuille manquante."));
                }
                continue;
            }
            var found = sheet.get().columns().stream().map(ImportModel::key).collect(java.util.stream.Collectors.toSet());
            if (sheet.get().columns().isEmpty() && !model.required()) {
                continue;
            }
            for (var column : model.columns()) {
                if (column.required() && !found.contains(key(column.title()))) {
                    errors.add(new Issue(model.name(), null, "Colonne manquante : %s.".formatted(column.title())));
                }
            }
            var known = model.columns().stream().map(c -> key(c.title())).collect(java.util.stream.Collectors.toSet());
            for (var column : sheet.get().columns()) {
                if (!known.contains(key(column))) {
                    warnings.add(new Issue(model.name(), null, "Colonne ignorée, non reprise : %s.".formatted(column)));
                }
            }
        }
        var sheetNames = ImportModel.SHEETS.stream().map(s -> key(s.name())).collect(java.util.stream.Collectors.toSet());
        sheetNames.add(key(ImportModel.README));
        for (var sheet : file.sheets()) {
            if (!sheetNames.contains(key(sheet.name())) && !sheet.rows().isEmpty()) {
                warnings.add(new Issue(sheet.name(), null, "Feuille ignorée, non reprise."));
            }
        }
    }

    private List<Line> lines(ImportFile file, SheetModel model) {
        return file.sheet(model.name()).map(s -> s.rows().stream().map(r -> new Line(model.name(), r)).toList())
                .orElse(List.of());
    }

    // --- Lecture des feuilles ------------------------------------------------------

    private void readFamily(Line line) {
        var reference = line.required(ImportModel.FAMILY_REF);
        var request = new FamilyRequest(line.required(ImportModel.LABEL), line.text(ImportModel.ADDRESS),
                line.text(ImportModel.POSTAL_CODE), line.text(ImportModel.CITY), line.text(ImportModel.SITUATION),
                line.text(ImportModel.NOTES));
        line.check(request);
        if (reference != null && families.putIfAbsent(key(reference), new FamilyDraft(line, request)) != null) {
            line.error("Référence déjà utilisée : %s.".formatted(reference));
        }
    }

    private void readGuardian(Line line) {
        var family = familyOf(line);
        var request = new GuardianRequest(line.required(ImportModel.FIRST_NAME), line.required(ImportModel.LAST_NAME),
                line.required(ImportModel.RELATIONSHIP), line.requiredYesNo(ImportModel.LEGAL_GUARDIAN),
                line.requiredYesNo(ImportModel.PAYER), line.text(ImportModel.MOBILE_PHONE),
                line.text(ImportModel.WORK_PHONE), line.text(ImportModel.EMAIL), line.text(ImportModel.EMPLOYER), 0);
        line.check(request);
        if (family != null) {
            family.guardians.add(request);
        }
    }

    private void readChild(Line line, Set<String> seen) {
        var reference = line.required(ImportModel.CHILD_REF);
        var family = familyOf(line);
        var firstName = line.required(ImportModel.FIRST_NAME);
        var lastName = line.required(ImportModel.LAST_NAME);
        var birthDate = line.requiredDate(ImportModel.BIRTH_DATE);
        var section = line.text(ImportModel.SECTION);
        UUID sectionId = null;
        if (section == null) {
            line.warning("Aucune section : à compléter sur la fiche de l’enfant.");
        } else {
            sectionId = sections.get(key(section));
            if (sectionId == null) {
                line.error("Section inconnue : %s. Les sections se règlent dans Paramètres.".formatted(section));
            }
        }
        var identity = new ChildIdentityRequest(firstName, lastName, birthDate, sectionId,
                line.date(ImportModel.ENTRY_DATE), line.date(ImportModel.EXIT_DATE), line.text(ImportModel.LANGUAGES));
        line.check(identity);
        if (firstName != null && lastName != null && birthDate != null) {
            var childKey = childKey(firstName, lastName, birthDate);
            if (existingChildren.contains(childKey)) {
                line.error("Cet enfant existe déjà dans Ti’Cagou : %s %s.".formatted(firstName, lastName));
            } else if (!seen.add(childKey)) {
                line.error("Cet enfant figure deux fois dans le fichier : %s %s.".formatted(firstName, lastName));
            }
        }
        if (reference == null) {
            return;
        }
        var draft = new ChildDraft(reference, identity, section);
        if (children.putIfAbsent(key(reference), draft) != null) {
            line.error("Référence déjà utilisée : %s.".formatted(reference));
        } else if (family != null) {
            family.children.add(draft);
        }
    }

    private void readHealth(Line line) {
        var child = childOf(line);
        var pai = line.yesNo(ImportModel.PAI);
        var reason = line.text(ImportModel.PAI_REASON);
        var from = line.date(ImportModel.PAI_FROM);
        var until = line.date(ImportModel.PAI_UNTIL);
        var kitLocation = line.text(ImportModel.PAI_KIT_LOCATION);
        var kitDetails = line.text(ImportModel.PAI_KIT_DETAILS);
        // Un PAI décrit mais pas activé n'apparaîtrait nulle part : on ne présume ni oui ni non.
        if (pai == null && (reason != null || from != null || until != null || kitLocation != null || kitDetails != null)) {
            line.error("PAI : indiquez oui ou non, cette ligne décrit un PAI.");
        }
        boolean active = Boolean.TRUE.equals(pai);
        if (active && until != null && until.isBefore(today)) {
            line.warning("PAI expiré le %s : à renouveler.".formatted(Formats.date(until)));
        }
        var health = new HealthDto(line.text(ImportModel.DIET), line.text(ImportModel.DIET_DETAILS), active, reason, from,
                until, kitLocation, kitDetails, null, line.text(ImportModel.EMERGENCY_PROTOCOL),
                line.text(ImportModel.DOCTOR_NAME), line.text(ImportModel.DOCTOR_PHONE),
                line.text(ImportModel.DOCTOR_ADDRESS), line.text(ImportModel.INSTRUCTIONS), null, List.of(), List.of(),
                null, null, null);
        line.check(health);
        if (child != null) {
            if (child.health != null) {
                line.error("Une seule ligne par enfant dans cette feuille.");
            }
            child.health = health;
        }
    }

    private void readAllergy(Line line) {
        var child = childOf(line);
        var allergen = line.required(ImportModel.ALLERGEN);
        if (allergen != null && NO_ALLERGY.contains(key(allergen))) {
            line.error("« %s » n’est pas une allergie : supprimez cette ligne.".formatted(allergen));
        } else if (allergen != null && allergen.matches(".*([,;/+]| et ).*")) {
            line.warning("Une allergie par ligne : « %s » est repris tel quel.".formatted(allergen));
        }
        // Pas de gravité par défaut : sous-estimer une allergie est le pire des défauts.
        var severity = line.required(ImportModel.SEVERITY);
        var level = severity == null ? null : SEVERITIES.get(key(severity));
        if (severity != null && level == null) {
            line.error("Gravité inconnue : %s. Indiquez Légère, Modérée ou Sévère.".formatted(severity));
        }
        var allergy = new AllergyDto(allergen, line.text(ImportModel.REACTION), level);
        line.check(allergy);
        if (child != null) {
            child.allergies.add(allergy);
        }
    }

    private void readVaccine(Line line) {
        var child = childOf(line);
        var vaccine = new VaccinationDto(line.required(ImportModel.VACCINE), line.date(ImportModel.LAST_DOSE));
        line.check(vaccine);
        if (child != null) {
            child.vaccinations.add(vaccine);
        }
    }

    private void readAuthorizedPerson(Line line) {
        var child = childOf(line);
        // Pièce d'identité jamais marquée vérifiée : cette vérification se fait à la crèche.
        var person = new AuthorizedPersonRequest(line.required(ImportModel.FIRST_NAME), line.required(ImportModel.LAST_NAME),
                line.required(ImportModel.RELATIONSHIP), line.text(ImportModel.PHONE), false);
        line.check(person);
        if (child != null) {
            child.authorizedPersons.add(person);
        }
    }

    private void readConsents(Line line) {
        var child = childOf(line);
        var signedOn = line.date(ImportModel.SIGNED_ON);
        var signedBy = line.text(ImportModel.SIGNED_BY);
        var consents = new ArrayList<ConsentDto>();
        Map<Consent.Type, String> columns = new LinkedHashMap<>();
        columns.put(Consent.Type.PHOTOS, ImportModel.PHOTOS);
        columns.put(Consent.Type.SORTIES, ImportModel.OUTINGS);
        columns.put(Consent.Type.SOINS_URGENCE, ImportModel.EMERGENCY_CARE);
        columns.forEach((type, column) -> {
            var granted = line.yesNo(column);
            if (granted != null) {
                var consent = new ConsentDto(type, granted, null, signedOn, signedBy);
                line.check(consent);
                consents.add(consent);
            }
        });
        if (child != null) {
            if (!child.consents.isEmpty()) {
                line.error("Une seule ligne par enfant dans cette feuille.");
            }
            child.consents.addAll(consents);
        }
    }

    private FamilyDraft familyOf(Line line) {
        var reference = line.required(ImportModel.FAMILY_REF);
        if (reference == null) {
            return null;
        }
        var family = families.get(key(reference));
        if (family == null) {
            line.error("Famille inconnue : %s. Elle doit figurer dans la feuille Familles.".formatted(reference));
        }
        return family;
    }

    private ChildDraft childOf(Line line) {
        var reference = line.required(ImportModel.CHILD_REF);
        if (reference == null) {
            return null;
        }
        var child = children.get(key(reference));
        if (child == null) {
            line.error("Enfant inconnu : %s. Il doit figurer dans la feuille Enfants.".formatted(reference));
        }
        return child;
    }

    private void checkFamily(FamilyDraft family) {
        if (family.children.isEmpty()) {
            family.line.error("Aucun enfant ne se rattache à cette famille.");
        }
        // Sans responsable légal, personne ne pourrait venir chercher l'enfant sans validation de la direction.
        if (family.guardians.stream().noneMatch(GuardianRequest::legalGuardian)) {
            family.line.error("Cette famille n’a aucun responsable légal dans la feuille Responsables.");
        }
        if (family.guardians.stream().noneMatch(GuardianRequest::payer)) {
            family.line.warning("Aucun payeur : à désigner avant la première facture.");
        }
    }

    // --- Brouillons ----------------------------------------------------------------

    private static final class FamilyDraft {
        private final Line line;
        private final FamilyRequest request;
        private final List<GuardianRequest> guardians = new ArrayList<>();
        private final List<ChildDraft> children = new ArrayList<>();

        private FamilyDraft(Line line, FamilyRequest request) {
            this.line = line;
            this.request = request;
        }

        private PlannedFamily planned() {
            var sorted = guardians.stream()
                    .sorted(Comparator.comparing(GuardianRequest::legalGuardian).reversed()).toList();
            var ordered = IntStream.range(0, sorted.size()).mapToObj(i -> {
                var g = sorted.get(i);
                return new GuardianRequest(g.firstName(), g.lastName(), g.relationship(), g.legalGuardian(), g.payer(),
                        g.mobilePhone(), g.workPhone(), g.email(), g.employer(), i);
            }).toList();
            return new PlannedFamily(request, ordered, children.stream().map(ChildDraft::planned).toList());
        }
    }

    private static final class ChildDraft {
        private final String reference;
        private final ChildIdentityRequest identity;
        private final String section;
        private HealthDto health;
        private final List<AllergyDto> allergies = new ArrayList<>();
        private final List<VaccinationDto> vaccinations = new ArrayList<>();
        private final List<AuthorizedPersonRequest> authorizedPersons = new ArrayList<>();
        private final List<ConsentDto> consents = new ArrayList<>();

        private ChildDraft(String reference, ChildIdentityRequest identity, String section) {
            this.reference = reference;
            this.identity = identity;
            this.section = section;
        }

        private PlannedChild planned() {
            return new PlannedChild(reference, identity, section, mergedHealth(), consents, authorizedPersons);
        }

        /** Santé complète, allergies et vaccins compris ; {@code null} si le fichier n'en dit rien. */
        private HealthDto mergedHealth() {
            if (health == null && allergies.isEmpty() && vaccinations.isEmpty()) {
                return null;
            }
            var h = health != null ? health : new HealthDto(null, null, false, null, null, null, null, null, null, null,
                    null, null, null, null, null, List.of(), List.of(), null, null, null);
            return new HealthDto(h.diet(), h.dietDetails(), h.paiActive(), h.paiReason(), h.paiValidFrom(),
                    h.paiValidUntil(), h.paiKitLocation(), h.paiKitDetails(), null, h.emergencyProtocol(), h.doctorName(),
                    h.doctorPhone(), h.doctorAddress(), h.instructions(), null, allergies, vaccinations, null, null, null);
        }
    }

    // --- Une ligne du fichier ------------------------------------------------------

    private final class Line {
        private final String sheet;
        private final ImportFile.Row row;
        private boolean failed;

        private Line(String sheet, ImportFile.Row row) {
            this.sheet = sheet;
            this.row = row;
        }

        private void error(String message) {
            failed = true;
            errors.add(new Issue(sheet, row.number(), message));
        }

        private void warning(String message) {
            warnings.add(new Issue(sheet, row.number(), message));
        }

        private String text(String column) {
            return row.values().get(key(column));
        }

        private String required(String column) {
            var value = text(column);
            if (value == null) {
                error("%s : obligatoire.".formatted(column));
            }
            return value;
        }

        private LocalDate requiredDate(String column) {
            if (text(column) == null) {
                error("%s : obligatoire.".formatted(column));
                return null;
            }
            return date(column);
        }

        private LocalDate date(String column) {
            var value = text(column);
            if (value == null) {
                return null;
            }
            try {
                if (value.matches("\\d+(\\.\\d+)?")) {
                    int serial = new BigDecimal(value).intValue();
                    if (serial >= MIN_SERIAL && serial <= MAX_SERIAL) {
                        return dateOrigin.plusDays(serial);
                    }
                } else if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    return LocalDate.parse(value);
                } else {
                    return LocalDate.parse(value.replace('-', '/').replace('.', '/'), FRENCH_DATE);
                }
            } catch (DateTimeParseException e) {
                // Message commun ci-dessous.
            }
            error("%s : date invalide (%s). Écrivez JJ/MM/AAAA.".formatted(column, value));
            return null;
        }

        /** {@code null} si la cellule est vide. */
        private Boolean yesNo(String column) {
            var value = text(column);
            if (value == null) {
                return null;
            }
            var normalized = key(value);
            if (YES.contains(normalized)) {
                return true;
            }
            if (NO.contains(normalized)) {
                return false;
            }
            error("%s : indiquez oui ou non (%s).".formatted(column, value));
            return null;
        }

        private boolean requiredYesNo(String column) {
            if (text(column) == null) {
                error("%s : obligatoire.".formatted(column));
                return false;
            }
            return Boolean.TRUE.equals(yesNo(column));
        }

        /** Mêmes contrôles que les formulaires ; une ligne déjà en erreur n'en ajoute pas d'autres sur les mêmes champs. */
        private <T> void check(T request) {
            if (failed) {
                return;
            }
            validator.validate(request).stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .forEach(v -> error(message(v)));
        }

        private String message(ConstraintViolation<?> violation) {
            var label = FIELD_LABELS.get(violation.getPropertyPath().toString());
            return label == null ? violation.getMessage() : "%s : %s.".formatted(label, violation.getMessage());
        }
    }
}
