package nc.ticagou.backup;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Mode en ligne de commande du serveur pour la reprise après sinistre, sans démarrer l'application :
 *
 * <pre>java -jar ticagou-server.jar restaurer --archive &lt;fichier.tcb&gt; [--remplacer] [--pg-bin &lt;dossier&gt;]</pre>
 *
 * La connexion à la base et le dossier des documents viennent des mêmes variables que l'instance
 * ({@code TICAGOU_DB_URL}, {@code TICAGOU_DB_USER}, {@code TICAGOU_DB_PASSWORD}, {@code TICAGOU_STORAGE_DIR},
 * {@code TICAGOU_BACKUP_PGBIN}) ; la clé de secours vient de {@code TICAGOU_RECOVERY_KEY} ou est demandée au clavier.
 * L'instance doit être arrêtée. Voir deploy/creche.sh restaurer, qui fait tout cela.
 */
public final class RestoreCli {

    private RestoreCli() {
    }

    public static void main(String[] args) {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            System.exit(run(args, out));
        } catch (Exception e) {
            out.println("ÉCHEC : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            System.exit(1);
        }
    }

    static int run(String[] args, PrintStream out) throws Exception {
        Path archive = null;
        boolean replace = false;
        Path pgBin = ArchiveRestorer.env("TICAGOU_BACKUP_PGBIN").map(Path::of).orElse(null);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--archive" -> archive = Path.of(args[++i]);
                case "--remplacer" -> replace = true;
                case "--pg-bin" -> pgBin = Path.of(args[++i]);
                default -> {
                    out.println("Option inconnue : " + args[i]);
                    usage(out);
                    return 2;
                }
            }
        }
        if (archive == null) {
            usage(out);
            return 2;
        }
        var key = ArchiveRestorer.env("TICAGOU_RECOVERY_KEY").orElse(null);
        if (key == null && System.console() != null) {
            var typed = System.console().readPassword("Clé de secours (fiche de secours) : ");
            key = typed == null ? null : new String(typed);
        }
        if (key == null || key.isBlank()) {
            out.println("Clé de secours manquante (variable TICAGOU_RECOVERY_KEY).");
            return 2;
        }
        var options = new ArchiveRestorer.Options(archive, key,
                ArchiveRestorer.env("TICAGOU_DB_URL").orElse("jdbc:postgresql://localhost:5435/ticagou"),
                ArchiveRestorer.env("TICAGOU_DB_USER").orElse("ticagou"),
                ArchiveRestorer.env("TICAGOU_DB_PASSWORD").orElse("ticagou"),
                pgBin,
                Path.of(ArchiveRestorer.env("TICAGOU_STORAGE_DIR").orElse("data/documents")),
                replace);
        var result = new ArchiveRestorer(out).restore(options);
        out.println();
        out.println("RESTAURATION RÉUSSIE : sauvegarde du %s (schéma V%s), %d tables, %d lignes, %d document(s)."
                .formatted(result.createdAt(), result.schema(), result.tables(), result.rows(), result.documents()));
        if (result.previousDocuments() != null) {
            out.println("Anciens documents gardés dans : " + result.previousDocuments());
        }
        return 0;
    }

    private static void usage(PrintStream out) {
        out.println("Usage : java -jar ticagou-server.jar restaurer --archive <fichier.tcb> [--remplacer] [--pg-bin <dossier>]");
    }
}
