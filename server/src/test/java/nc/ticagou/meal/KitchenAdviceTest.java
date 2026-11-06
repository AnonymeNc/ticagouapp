package nc.ticagou.meal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nc.ticagou.meal.KitchenAdvice.Course;
import nc.ticagou.meal.KitchenAdvice.DietRule;
import nc.ticagou.meal.KitchenAdvice.Dish;

class KitchenAdviceTest {

    private static final List<Dish> MENU = List.of(
            new Dish(Course.ENTREE, "Carottes râpées au citron", Set.of()),
            new Dish(Course.PLAT, "Poulet au curry doux, riz, haricots verts", Set.of("viande")),
            new Dish(Course.LAITAGE, "Yaourt nature", Set.of("lait")),
            new Dish(Course.GOUTER, "Pain, beurre, banane · lait", Set.of("gluten", "lait")));

    @Test
    void panierRepasPrimeSurTout() {
        assertThat(KitchenAdvice.advise(true, MENU, List.of("Lait"), null))
                .containsExactly("Panier repas fourni par la famille. Ne rien servir d’autre, goûter compris.");
    }

    @Test
    void allergieRapprocheeDesEtiquettes() {
        assertThat(KitchenAdvice.advise(false, MENU, List.of("Lait de vache"), null))
                .containsExactly("Laitage : contient lait, à remplacer.", "Goûter : contient lait, à remplacer.");
        // Hors des 14 allergènes : affichée sur la fiche mais pas contrôlée.
        assertThat(KitchenAdvice.advise(false, MENU, List.of("Kiwi"), null)).containsExactly("Rien à remplacer aujourd’hui.");
    }

    @Test
    void varianteDuRegimeOuVarianteAPrevoir() {
        var vegetarian = new DietRule("Végétarien", Set.of("viande", "poisson", "porc"),
                new Dish(Course.VARIANTE, "Lentilles corail au curry", Set.of()));
        assertThat(KitchenAdvice.advise(false, MENU, List.of(), vegetarian))
                .containsExactly("Poulet au curry doux → Lentilles corail au curry.");
        var withoutVariant = new DietRule("Végétarien", Set.of("viande"), null);
        assertThat(KitchenAdvice.advise(false, MENU, List.of(), withoutVariant))
                .containsExactly("Plat : contient viande, variante à prévoir.");
        var lactoseFree = new DietRule("Sans lactose", Set.of("lait"), null);
        assertThat(KitchenAdvice.advise(false, MENU, List.of(), lactoseFree))
                .containsExactly("Laitage : contient lait, à remplacer.", "Goûter : contient lait, à remplacer.");
    }

    @Test
    void rienARemplacerOuMenuAbsent() {
        var porkFree = new DietRule("Sans porc", Set.of("porc"), null);
        assertThat(KitchenAdvice.advise(false, MENU, List.of(), porkFree)).containsExactly("Rien à remplacer aujourd’hui.");
        assertThat(KitchenAdvice.advise(false, List.of(), List.of("Arachide"), null))
                .containsExactly("Menu du jour non saisi : vérifiez chaque plat.");
    }

    @Test
    void etiquettesNormalisees() {
        assertThat(MealTags.canonical("Oeufs")).isEqualTo("œuf");
        assertThat(MealTags.canonical("fruits a coque")).isEqualTo("fruits à coque");
        assertThat(MealTags.canonical("kiwi")).isNull();
        assertThat(MealTags.format(List.of("lait", "Gluten", "kiwi"))).isEqualTo("gluten,lait");
        assertThat(MealTags.fromAllergy("Fruits à coque (noix, noisettes)")).containsExactly("fruits à coque");
        assertThat(MealTags.fromAllergy("Œufs")).containsExactly("œuf");
        assertThat(MealTags.fromAllergy("Arachide")).containsExactly("arachide");
    }
}
