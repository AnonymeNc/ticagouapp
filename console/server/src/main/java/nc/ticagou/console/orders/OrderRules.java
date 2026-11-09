package nc.ticagou.console.orders;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liste blanche des ordres et contrôle de leurs paramètres, sans Spring : chaque valeur doit tenir dans un alphabet sûr,
 * car `creche.sh appliquer` la passe à une commande du shell (il la revérifie de son côté). La ligne remise à l'hôte est
 * {@code id<TAB>type<TAB>slug<TAB>clé=valeur;clé=valeur}.
 */
public final class OrderRules {

    private OrderRules() {
    }

    public static final List<String> TYPES = List.of("CREER", "DEMO", "SUPPRIMER_DEMO", "ABONNEMENT", "SUSPENDRE", "REPRENDRE", "METTRE_A_JOUR",
            "EXPORTER", "SUPPRIMER");

    public static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");
    private static final Pattern NUMBER = Pattern.compile("^[A-Za-z0-9-]{1,20}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,29}$");
    /** Export d'une crèche écrit par {@code creche.sh exporter} : {@code <slug>-AAAAMMJJ-HHMM.tar.gz.enc}. */
    public static final Pattern EXPORT_FILE = Pattern.compile("^([a-z0-9][a-z0-9-]{1,40})-\\d{8}-\\d{4}\\.tar\\.gz\\.enc$");
    private static final Set<String> OFFERS = Set.of("essentiel", "gestion", "complet");
    private static final Set<String> FORMULAS = Set.of("Essentiel", "Gestion", "Complet");
    private static final Set<String> MODULES = Set.of("INSCRIPTIONS", "FACTURATION", "COMPTABILITE", "PERSONNEL", "REPAS", "PORTAIL_PARENTS");

    /** Paramètre refusé : le message dit lequel et pourquoi. */
    public static final class Invalid extends IllegalArgumentException {
        Invalid(String message) {
            super(message);
        }
    }

    /** Contrôle les paramètres d'un ordre et les rend dans un ordre stable (seulement les clés attendues). */
    public static Map<String, String> check(String type, String slug, Map<String, String> params) {
        if (!TYPES.contains(type)) {
            throw new Invalid("Ordre inconnu : " + type);
        }
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new Invalid("Identifiant de crèche invalide : minuscules, chiffres et tirets.");
        }
        var p = params == null ? Map.<String, String>of() : params;
        var out = new LinkedHashMap<String, String>();
        switch (type) {
            case "CREER" -> optional(out, p, "domaine", DOMAIN, "Domaine invalide.");
            case "DEMO" -> {
                var formule = p.getOrDefault("formule", "Complet");
                if (!FORMULAS.contains(formule)) {
                    throw new Invalid("Formule de démonstration : Essentiel, Gestion ou Complet.");
                }
                out.put("formule", formule);
                optional(out, p, "domaine", DOMAIN, "Domaine invalide.");
            }
            case "ABONNEMENT" -> {
                out.put("numero", required(p, "numero", NUMBER, "Numéro d’abonnement invalide."));
                var offre = p.get("offre");
                if (!OFFERS.contains(offre)) {
                    throw new Invalid("Offre : essentiel, gestion ou complet.");
                }
                out.put("offre", offre);
                for (var key : List.of("places", "pc", "tablettes")) {
                    out.put(key, Integer.toString(positive(p, key)));
                }
                var until = p.get("jusqu_au");
                try {
                    out.put("jusqu_au", LocalDate.parse(until == null ? "" : until).toString());
                } catch (DateTimeParseException e) {
                    throw new Invalid("Échéance invalide (AAAA-MM-JJ).");
                }
                var modules = p.getOrDefault("modules", "");
                if (!modules.isEmpty()) {
                    for (var m : modules.split(",")) {
                        if (!MODULES.contains(m)) {
                            throw new Invalid("Module inconnu : " + m);
                        }
                    }
                }
                out.put("modules", modules);
            }
            case "METTRE_A_JOUR" -> out.put("version", required(p, "version", VERSION, "Version invalide."));
            case "EXPORTER" -> {
                var suite = p.getOrDefault("suite", "");
                if (!suite.isEmpty() && !"supprimer".equals(suite)) {
                    throw new Invalid("Suite de l’export : rien ou « supprimer ».");
                }
                if (!suite.isEmpty()) {
                    out.put("suite", suite);
                }
            }
            case "SUPPRIMER" -> {
                // L'export téléchargé, que l'hôte revérifie avant d'effacer quoi que ce soit.
                var export = p.get("export");
                var matcher = export == null ? null : EXPORT_FILE.matcher(export);
                if (matcher == null || !matcher.matches() || !matcher.group(1).equals(slug)) {
                    throw new Invalid("Suppression sans export de cette crèche : refusée.");
                }
                out.put("export", export);
            }
            default -> {
                // SUSPENDRE, REPRENDRE, SUPPRIMER_DEMO : pas de paramètre.
            }
        }
        return out;
    }

    /** Ligne remise à l'hôte. */
    public static String line(String id, String type, String slug, Map<String, String> params) {
        var joined = String.join(";", params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        return id + "\t" + type + "\t" + slug + "\t" + joined;
    }

    private static void optional(Map<String, String> out, Map<String, String> p, String key, Pattern pattern, String message) {
        var value = p.get(key);
        if (value != null && !value.isBlank()) {
            if (!pattern.matcher(value).matches()) {
                throw new Invalid(message);
            }
            out.put(key, value);
        }
    }

    private static String required(Map<String, String> p, String key, Pattern pattern, String message) {
        var value = p.get(key);
        if (value == null || !pattern.matcher(value).matches()) {
            throw new Invalid(message);
        }
        return value;
    }

    private static int positive(Map<String, String> p, String key) {
        try {
            int value = Integer.parseInt(p.getOrDefault(key, ""));
            if (value < 1 || value > 1000) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new Invalid("« %s » : un nombre de 1 à 1000.".formatted(key));
        }
    }
}
