package nc.ticagou.portail;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Routes de la PWA des parents : la page d'accueil de l'application, qui gère elle-même la navigation. */
@Controller
public class SpaController {

    @GetMapping({"/connexion", "/messages", "/absence", "/factures", "/documents", "/documents/{structureId}/{documentId}"})
    public String app() {
        return "forward:/index.html";
    }
}
