package nc.ticagou;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import nc.ticagou.backup.RestoreCli;

// Pas d'utilisateur en mémoire généré : l'authentification réelle arrive en phase 1.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class TiCagouApplication {

    public static void main(String[] args) {
        // Reprise après sinistre : restauration d'une sauvegarde, sans démarrer le serveur.
        if (args.length > 0 && "restaurer".equals(args[0])) {
            RestoreCli.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(TiCagouApplication.class, args);
    }
}
