package nc.ticagou.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.validation.FieldError;

import jakarta.validation.ConstraintViolation;

/**
 * Messages de validation lisibles par la direction : « Date de naissance : ne peut pas être dans le futur » plutôt
 * que « Certaines informations sont à corriger ». Les messages écrits dans les DTO ({@code message = "…"}) sont repris
 * tels quels ; les contraintes standard (champ vide, trop long, e-mail…) sont formulées ici, en français, avec le nom
 * du champ, quelle que soit la langue du serveur.
 */
public final class ValidationMessages {

    private ValidationMessages() {
    }

    /** Message général quand plusieurs informations sont à corriger. */
    public static final String GENERIC = "Certaines informations sont à corriger.";

    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");

    /** Libellés des champs des formulaires (dernier segment du chemin). */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("firstName", "Prénom"), Map.entry("lastName", "Nom"), Map.entry("name", "Nom"),
            Map.entry("label", "Libellé"), Map.entry("birthDate", "Date de naissance"),
            Map.entry("expectedBirthDate", "Date de naissance prévue"),
            Map.entry("childFirstName", "Prénom de l’enfant"), Map.entry("childLastName", "Nom de l’enfant"),
            Map.entry("guardianFirstName", "Prénom du responsable"), Map.entry("guardianLastName", "Nom du responsable"),
            Map.entry("relationship", "Lien avec l’enfant"), Map.entry("phone", "Téléphone"),
            Map.entry("mobilePhone", "Téléphone portable"), Map.entry("workPhone", "Téléphone professionnel"),
            Map.entry("email", "E-mail"), Map.entry("address", "Adresse"), Map.entry("postalCode", "Code postal"),
            Map.entry("city", "Commune"), Map.entry("desiredStart", "Date d’entrée souhaitée"),
            Map.entry("sectionId", "Section"), Map.entry("careType", "Type d’accueil"), Map.entry("days", "Jours d’accueil"),
            Map.entry("arrivesAt", "Heure d’arrivée"), Map.entry("leavesAt", "Heure de départ"),
            Map.entry("opensAt", "Heure d’ouverture"), Map.entry("closesAt", "Heure de fermeture"),
            Map.entry("startsAt", "Heure de début"), Map.entry("endsAt", "Heure de fin"),
            Map.entry("startDate", "Date de début"), Map.entry("endDate", "Date de fin"),
            Map.entry("startsOn", "Date de début"), Map.entry("endsOn", "Date de fin"),
            Map.entry("validFrom", "Valide à partir du"), Map.entry("validTo", "Valide jusqu’au"),
            Map.entry("effectiveFrom", "Date d’effet"), Map.entry("signedOn", "Date de signature"),
            Map.entry("paidOn", "Date du paiement"), Map.entry("sentOn", "Date d’envoi"),
            Map.entry("notifiedOn", "Date de signalement"), Map.entry("visitAt", "Date de visite"),
            Map.entry("date", "Date"), Map.entry("day", "Jour"), Map.entry("month", "Mois"),
            Map.entry("time", "Heure"), Map.entry("occurredAt", "Date et heure"),
            Map.entry("agrementNumber", "Numéro d’agrément"), Map.entry("agrementDate", "Date d’agrément"),
            Map.entry("capacity", "Nombre de places"), Map.entry("minAgeMonths", "Âge minimum (mois)"),
            Map.entry("maxAgeMonths", "Âge maximum (mois)"), Map.entry("childrenPerAdult", "Enfants par adulte"),
            Map.entry("weeksPerYear", "Semaines d’accueil par an"), Map.entry("registrationFee", "Frais d’inscription"),
            Map.entry("hourlyRate", "Tarif horaire"), Map.entry("lowerBound", "Borne de la tranche"),
            Map.entry("monthlyFee", "Forfait mensuel"), Map.entry("formula", "Formule de facturation"),
            Map.entry("bands", "Tranches du barème"), Map.entry("formulas", "Formules de facturation"),
            Map.entry("familyQuotient", "Quotient familial"), Map.entry("monthlyReservedMinutes", "Heures réservées par mois"),
            Map.entry("planningNoticeDays", "Délai de transmission du planning"), Map.entry("noticeMonths", "Préavis"),
            Map.entry("amount", "Montant"), Map.entry("rate", "Taux"), Map.entry("monthlyCap", "Plafond mensuel"),
            Map.entry("iban", "IBAN"), Map.entry("bankName", "Banque"), Map.entry("holder", "Titulaire du compte"),
            Map.entry("ridet", "RIDET"), Map.entry("cafatNumber", "Numéro CAFAT"),
            Map.entry("login", "Identifiant"), Map.entry("password", "Mot de passe"),
            Map.entry("currentPassword", "Mot de passe actuel"), Map.entry("newPassword", "Nouveau mot de passe"),
            Map.entry("pin", "Code PIN"), Map.entry("directorPin", "Code PIN de la direction"), Map.entry("role", "Rôle"),
            Map.entry("jobTitle", "Fonction"), Map.entry("diploma", "Diplôme"),
            Map.entry("reason", "Motif"), Map.entry("note", "Note"), Map.entry("notes", "Notes"), Map.entry("body", "Texte"),
            Map.entry("text", "Texte"), Map.entry("points", "Points"), Map.entry("displayOrder", "Ordre"),
            Map.entry("allergen", "Allergène"), Map.entry("allergies", "Allergies"), Map.entry("diet", "Régime"),
            Map.entry("reaction", "Réaction"), Map.entry("severity", "Gravité"),
            Map.entry("doctorName", "Médecin"), Map.entry("doctorPhone", "Téléphone du médecin"),
            Map.entry("personName", "Personne"), Map.entry("pickedUpBy", "Personne venue chercher l’enfant"),
            Map.entry("droppedOffBy", "Personne qui a déposé l’enfant"), Map.entry("signatory", "Signataire"),
            Map.entry("reference", "Référence"), Map.entry("method", "Mode de paiement"),
            Map.entry("code", "Code"), Map.entry("color", "Couleur"), Map.entry("course", "Plat"),
            Map.entry("dayOfWeek", "Jour de la semaine"), Map.entry("minutes", "Durée"),
            Map.entry("breakMinutes", "Pause"), Map.entry("weeklyMinutes", "Heures par semaine"),
            Map.entry("vaccinationCheckedOn", "Date de vérification des vaccins"), Map.entry("lastDoseOn", "Date de la dernière dose"),
            Map.entry("medicationDose", "Dose"), Map.entry("instructions", "Consignes"),
            Map.entry("emergencyProtocol", "Protocole d’urgence"), Map.entry("fileNumber", "Numéro de dossier"),
            Map.entry("organization", "Organisme"), Map.entry("employer", "Employeur"), Map.entry("url", "Adresse web"));

    /** Libellé lisible d'un chemin de champ : « contract.planning.days[1].arrivesAt » → « Heure d’arrivée (ligne 2) ». */
    public static String label(String path) {
        var last = path.substring(path.lastIndexOf('.') + 1);
        var matcher = INDEX.matcher(path);
        String line = null;
        while (matcher.find()) {
            line = matcher.group(1);
        }
        var bare = INDEX.matcher(last).replaceAll("");
        var label = LABELS.getOrDefault(bare, bare);
        return line == null ? label : "%s (ligne %d)".formatted(label, Integer.parseInt(line) + 1);
    }

    /** Message d'une erreur de champ, avec le nom du champ quand le message vient d'une contrainte standard. */
    public static String message(FieldError error) {
        ConstraintViolation<?> violation = error.contains(ConstraintViolation.class)
                ? error.unwrap(ConstraintViolation.class) : null;
        if (violation != null && !isStandard(violation.getMessageTemplate())) {
            // Message écrit pour ce formulaire : déjà explicite.
            return withPeriod(violation.getMessage());
        }
        var problem = standard(error.getCode(), violation, error.getRejectedValue());
        return withPeriod(label(error.getField()) + " : " + (problem != null ? problem : error.getDefaultMessage()));
    }

    /** Message général d'un formulaire : l'erreur seule, ou la liste des erreurs. */
    public static String detail(Collection<String> messages) {
        List<String> distinct = messages.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return GENERIC;
        }
        if (distinct.size() == 1) {
            return distinct.getFirst();
        }
        return "Certaines informations sont à corriger : " + String.join(" ", distinct);
    }

    private static boolean isStandard(String template) {
        return template != null && (template.startsWith("{jakarta.validation.constraints.")
                || template.startsWith("{org.hibernate.validator.constraints."));
    }

    private static String standard(String code, ConstraintViolation<?> violation, Object value) {
        if (code == null) {
            return null;
        }
        Map<String, Object> attributes = violation == null ? Map.of() : violation.getConstraintDescriptor().getAttributes();
        return switch (code) {
            case "NotBlank", "NotNull", "NotEmpty" -> value instanceof Collection<?> ? "choisissez au moins un élément" : "à renseigner";
            case "Size" -> value instanceof CharSequence
                    ? "%s caractères au plus".formatted(attributes.getOrDefault("max", "?"))
                    : "entre %s et %s éléments".formatted(attributes.getOrDefault("min", 0), attributes.getOrDefault("max", "?"));
            case "Email" -> "adresse e-mail invalide";
            case "Pattern" -> "format invalide";
            case "Past" -> "doit être dans le passé";
            case "PastOrPresent" -> "ne peut pas être dans le futur";
            case "Future" -> "doit être dans le futur";
            case "FutureOrPresent" -> "ne peut pas être dans le passé";
            case "Positive" -> "doit être supérieur à 0";
            case "PositiveOrZero" -> "ne peut pas être négatif";
            case "Negative", "NegativeOrZero" -> "doit être négatif";
            case "Min", "DecimalMin" -> "%s au minimum".formatted(attributes.getOrDefault("value", "?"));
            case "Max", "DecimalMax" -> "%s au maximum".formatted(attributes.getOrDefault("value", "?"));
            default -> null;
        };
    }

    private static String withPeriod(String message) {
        var trimmed = message.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!") ? trimmed : trimmed + ".";
    }
}
