package nc.ticagou.dataimport;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Classeur de reprise (ADR 0016) : feuilles et colonnes, communes au modèle à télécharger et à la lecture. */
final class ImportModel {

    static final String FAMILY_REF = "Réf famille";
    static final String CHILD_REF = "Réf enfant";
    static final String FIRST_NAME = "Prénom";
    static final String LAST_NAME = "Nom";
    static final String RELATIONSHIP = "Lien";

    static final String LABEL = "Libellé";
    static final String ADDRESS = "Adresse";
    static final String POSTAL_CODE = "Code postal";
    static final String CITY = "Ville";
    static final String SITUATION = "Situation";
    static final String NOTES = "Notes";

    static final String LEGAL_GUARDIAN = "Responsable légal";
    static final String PAYER = "Payeur";
    static final String MOBILE_PHONE = "Téléphone mobile";
    static final String WORK_PHONE = "Téléphone travail";
    static final String EMAIL = "E-mail";
    static final String EMPLOYER = "Employeur";

    static final String BIRTH_DATE = "Date de naissance";
    static final String SECTION = "Section";
    static final String ENTRY_DATE = "Date d’entrée";
    static final String EXIT_DATE = "Date de sortie";
    static final String LANGUAGES = "Langues parlées";

    static final String DIET = "Régime";
    static final String DIET_DETAILS = "Précisions sur le régime";
    static final String PAI = "PAI";
    static final String PAI_REASON = "Motif du PAI";
    static final String PAI_FROM = "PAI valable du";
    static final String PAI_UNTIL = "PAI valable jusqu’au";
    static final String PAI_KIT_LOCATION = "Emplacement de la trousse";
    static final String PAI_KIT_DETAILS = "Contenu de la trousse";
    static final String EMERGENCY_PROTOCOL = "Protocole d’urgence";
    static final String DOCTOR_NAME = "Médecin";
    static final String DOCTOR_PHONE = "Téléphone du médecin";
    static final String DOCTOR_ADDRESS = "Adresse du médecin";
    static final String INSTRUCTIONS = "Consignes";

    static final String ALLERGEN = "Allergène";
    static final String REACTION = "Réaction";
    static final String SEVERITY = "Gravité";

    static final String VACCINE = "Vaccin";
    static final String LAST_DOSE = "Dernière dose";

    static final String PHONE = "Téléphone";

    static final String PHOTOS = "Photos";
    static final String OUTINGS = "Sorties";
    static final String EMERGENCY_CARE = "Soins d’urgence";
    static final String SIGNED_ON = "Signées le";
    static final String SIGNED_BY = "Signées par";

    static final SheetModel FAMILIES = new SheetModel("Familles", true, List.of(
            required(FAMILY_REF), required(LABEL), optional(ADDRESS), optional(POSTAL_CODE), optional(CITY),
            optional(SITUATION), optional(NOTES)));
    static final SheetModel GUARDIANS = new SheetModel("Responsables", true, List.of(
            required(FAMILY_REF), required(FIRST_NAME), required(LAST_NAME), required(RELATIONSHIP),
            required(LEGAL_GUARDIAN), required(PAYER), optional(MOBILE_PHONE), optional(WORK_PHONE), optional(EMAIL),
            optional(EMPLOYER)));
    static final SheetModel CHILDREN = new SheetModel("Enfants", true, List.of(
            required(CHILD_REF), required(FAMILY_REF), required(FIRST_NAME), required(LAST_NAME), required(BIRTH_DATE),
            optional(SECTION), optional(ENTRY_DATE), optional(EXIT_DATE), optional(LANGUAGES)));
    static final SheetModel HEALTH = new SheetModel("Santé", false, List.of(
            required(CHILD_REF), optional(DIET), optional(DIET_DETAILS), optional(PAI), optional(PAI_REASON),
            optional(PAI_FROM), optional(PAI_UNTIL), optional(PAI_KIT_LOCATION), optional(PAI_KIT_DETAILS),
            optional(EMERGENCY_PROTOCOL), optional(DOCTOR_NAME), optional(DOCTOR_PHONE), optional(DOCTOR_ADDRESS),
            optional(INSTRUCTIONS)));
    static final SheetModel ALLERGIES = new SheetModel("Allergies", false, List.of(
            required(CHILD_REF), required(ALLERGEN), optional(REACTION), required(SEVERITY)));
    static final SheetModel VACCINES = new SheetModel("Vaccins", false, List.of(
            required(CHILD_REF), required(VACCINE), optional(LAST_DOSE)));
    static final SheetModel AUTHORIZED_PERSONS = new SheetModel("Personnes autorisées", false, List.of(
            required(CHILD_REF), required(FIRST_NAME), required(LAST_NAME), required(RELATIONSHIP), optional(PHONE)));
    static final SheetModel CONSENTS = new SheetModel("Autorisations", false, List.of(
            required(CHILD_REF), optional(PHOTOS), optional(OUTINGS), optional(EMERGENCY_CARE), optional(SIGNED_ON),
            optional(SIGNED_BY)));

    static final List<SheetModel> SHEETS = List.of(FAMILIES, GUARDIANS, CHILDREN, HEALTH, ALLERGIES, VACCINES,
            AUTHORIZED_PERSONS, CONSENTS);

    /** Première feuille du modèle : ignorée à la lecture. */
    static final String README = "Lisez-moi";

    private ImportModel() {
    }

    private static Column required(String title) {
        return new Column(title, true);
    }

    private static Column optional(String title) {
        return new Column(title, false);
    }

    /** Clé de comparaison : ni accents, ni casse, ni ponctuation (« Réf. famille * » vaut « Réf famille »). */
    static String key(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    record Column(String title, boolean required) {

        /** En-tête du modèle : une étoile marque les colonnes obligatoires. */
        String header() {
            return required ? title + " *" : title;
        }
    }

    record SheetModel(String name, boolean required, List<Column> columns) {
    }
}
