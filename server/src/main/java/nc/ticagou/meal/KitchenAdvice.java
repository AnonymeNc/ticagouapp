package nc.ticagou.meal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * « Ce qu'il faut faire » pour un enfant, d'après le menu du jour : panier repas, allergènes des plats, variante
 * de son régime. Sans dépendance à Spring.
 */
public final class KitchenAdvice {

    private KitchenAdvice() {
    }

    public enum Course { ENTREE, PLAT, VARIANTE, LAITAGE, DESSERT, GOUTER }

    public static String label(Course course) {
        return switch (course) {
            case ENTREE -> "Entrée";
            case PLAT -> "Plat";
            case VARIANTE -> "Variante";
            case LAITAGE -> "Laitage";
            case DESSERT -> "Dessert";
            case GOUTER -> "Goûter";
        };
    }

    public record Dish(Course course, String label, Set<String> tags) {
    }

    /** Régime de l'enfant : ce qu'il exclut et la variante prévue ce jour-là (nulle sans variante). */
    public record DietRule(String label, Set<String> excludes, Dish variant) {
    }

    /**
     * @param dishes      plats du jour hors variantes ; vide si le menu n'est pas saisi
     * @param allergies   allergies de l'enfant, saisies en clair
     */
    public static List<String> advise(boolean packedLunch, List<Dish> dishes, List<String> allergies, DietRule diet) {
        if (packedLunch) {
            return List.of("Panier repas fourni par la famille. Ne rien servir d’autre, goûter compris.");
        }
        if (dishes.isEmpty()) {
            return List.of("Menu du jour non saisi : vérifiez chaque plat.");
        }
        var advice = new ArrayList<String>();
        var allergens = new LinkedHashSet<String>();
        allergies.forEach(a -> allergens.addAll(MealTags.fromAllergy(a)));
        for (var dish : dishes) {
            var hit = intersect(dish.tags(), allergens);
            if (!hit.isEmpty()) {
                advice.add("%s : contient %s, à remplacer.".formatted(label(dish.course()), String.join(", ", hit)));
            }
        }
        if (diet != null) {
            for (var dish : dishes) {
                var excluded = intersect(dish.tags(), diet.excludes());
                if (excluded.isEmpty()) {
                    continue;
                }
                if (dish.course() == Course.PLAT && diet.variant() != null) {
                    advice.add("%s → %s.".formatted(shortLabel(dish.label()), diet.variant().label()));
                } else {
                    advice.add("%s : contient %s, %s.".formatted(label(dish.course()), String.join(", ", excluded),
                            dish.course() == Course.PLAT ? "variante à prévoir" : "à remplacer"));
                }
            }
        }
        if (advice.isEmpty()) {
            advice.add("Rien à remplacer aujourd’hui.");
        }
        return List.copyOf(new LinkedHashSet<>(advice));
    }

    /** Premier élément d'un plat composé : « Poulet au curry doux, riz » → « Poulet au curry doux ». */
    static String shortLabel(String label) {
        int comma = label.indexOf(',');
        return comma > 0 ? label.substring(0, comma) : label;
    }

    private static List<String> intersect(Set<String> tags, Set<String> wanted) {
        return tags.stream().filter(wanted::contains).toList();
    }
}
