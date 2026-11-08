package nc.ticagou.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Routes de l'interface (React Router) : renvoyées vers index.html, sauf l'API et les fichiers. */
@Controller
public class SpaController {

    @GetMapping({"/", "/connexion", "/premier-compte", "/parc/**", "/factures/**", "/envoi/**", "/ordres/**", "/reporting/**",
            "/parametres/**"})
    public String index() {
        return "forward:/index.html";
    }
}
