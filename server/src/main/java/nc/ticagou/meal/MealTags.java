package nc.ticagou.meal;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Étiquettes des plats : les 14 allergènes à déclaration obligatoire, plus porc, viande et poisson pour les
 * régimes. Stockées en texte séparé par des virgules ; une allergie saisie en clair sur la fiche de l'enfant
 * (« Arachide », « Fruits à coque ») est rapprochée d'une étiquette par son nom.
 */
public final class MealTags {

    private MealTags() {
    }

    public static final List<String> ALLERGENS = List.of("gluten", "crustacés", "œuf", "poisson", "arachide", "soja",
            "lait", "fruits à coque", "céleri", "moutarde", "sésame", "sulfites", "lupin", "mollusques");

    /** Étiquettes pour les régimes, en plus des allergènes. */
    public static final List<String> INGREDIENTS = List.of("porc", "viande");

    public static final List<String> ALL = java.util.stream.Stream.concat(ALLERGENS.stream(), INGREDIENTS.stream())
            .toList();

    /** « lait, œuf » → {lait, œuf} ; les étiquettes inconnues sont ignorées. */
    public static Set<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(stored.split(",")).map(String::trim).map(MealTags::canonical)
                .filter(t -> t != null).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Étiquettes dans l'ordre de la liste de référence, séparées par des virgules. */
    public static String format(Collection<String> tags) {
        var canonical = tags.stream().map(MealTags::canonical).filter(t -> t != null).collect(Collectors.toSet());
        return ALL.stream().filter(canonical::contains).collect(Collectors.joining(","));
    }

    /** Étiquette de référence pour une saisie (« Oeuf », « oeufs » → « œuf ») ; null si inconnue. */
    public static String canonical(String value) {
        if (value == null) {
            return null;
        }
        var key = normalize(value);
        return ALL.stream().filter(t -> {
            var tag = normalize(t);
            return key.equals(tag) || key.equals(tag + "s") || key.equals(tag + "x");
        }).findFirst().orElse(null);
    }

    /**
     * Allergènes reconnus dans une allergie saisie en clair : « Arachide » → arachide, « Lait de vache » → lait.
     * Une allergie hors de la liste réglementaire (kiwi) ne donne aucune étiquette.
     */
    public static Set<String> fromAllergy(String allergy) {
        var text = " " + normalize(allergy) + " ";
        return ALLERGENS.stream()
                .filter(tag -> {
                    var key = normalize(tag);
                    return text.contains(" " + key + " ") || text.contains(" " + key + "s ") || text.contains(" " + key + "x ");
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Minuscules, sans accents ni ligatures, espaces simples. */
    static String normalize(String value) {
        var lower = value.toLowerCase(java.util.Locale.ROOT).replace("œ", "oe").replace("æ", "ae");
        var stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.replaceAll("[^a-z]+", " ").trim();
    }
}
