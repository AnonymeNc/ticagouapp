package nc.ticagou.console.parc;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.console.parc.ParcService.ClientForm;
import nc.ticagou.console.parc.ParcService.FicheForm;
import nc.ticagou.console.parc.ParcViews.Detail;
import nc.ticagou.console.parc.ParcViews.Parc;
import nc.ticagou.console.parc.ParcViews.SearchHit;

/** Parc des crèches (plan console, phase 2). */
@RestController
@RequestMapping("/api/v1")
class ParcController {

    static final String SLUG = "^[a-z0-9][a-z0-9-]{1,40}$";

    record ClientRequest(
            @NotBlank(message = "La raison sociale est obligatoire.") @Size(max = 150) String name,
            @NotNull @Pattern(regexp = "PRIVE|ASSOCIATION|COLLECTIVITE|ENTREPRISE", message = "Type de client inconnu.") String kind,
            @Size(max = 20, message = "RIDET : 20 caractères au plus.") String ridet,
            @Size(max = 400) String address,
            @Size(max = 150) String directorName,
            @Email(message = "E-mail de la direction invalide.") @Size(max = 200) String directorEmail,
            @Size(max = 40) String directorPhone,
            @Email(message = "E-mail de facturation invalide.") @Size(max = 200) String billingEmail,
            @Email(message = "E-mail de relance invalide.") @Size(max = 200) String reminderEmail,
            @Min(value = 0, message = "Délai de paiement : 0 à 120 jours.") @Max(value = 120, message = "Délai de paiement : 0 à 120 jours.") int paymentDays) {
    }

    record FicheRequest(
            @NotBlank(message = "Le nom de la crèche est obligatoire.") @Size(max = 150) String name,
            @Size(max = 80) String city,
            @NotNull @Pattern(regexp = "PROSPECT|CLIENT|RESILIE|DEMO", message = "Statut inconnu.") String lifecycle,
            LocalDate clientSince,
            @NotNull @Valid ClientRequest client) {
    }

    record NoteRequest(
            @NotNull @Pattern(regexp = "NOTE|APPEL") String kind,
            @NotBlank(message = "La note est vide.") @Size(max = 2000, message = "2 000 caractères au plus.") String text,
            @jakarta.validation.constraints.Min(value = 1, message = "Durée : 1 minute au moins.")
            @jakarta.validation.constraints.Max(value = 1440, message = "Durée : 24 heures au plus.") Integer minutes) {
    }

    record ProspectRequest(
            @NotNull @Pattern(regexp = SLUG, message = "Identifiant : minuscules, chiffres et tirets (2 à 41 caractères).") String slug,
            @NotBlank(message = "Le nom est obligatoire.") @Size(max = 150) String name,
            @Size(max = 80) String city) {
    }

    private final ParcService service;

    ParcController(ParcService service) {
        this.service = service;
    }

    @GetMapping("/parc")
    Parc parc() {
        return service.parc();
    }

    @GetMapping("/parc/{slug}")
    Detail detail(@PathVariable String slug) {
        return service.detail(slug);
    }

    @PostMapping("/parc")
    @ResponseStatus(HttpStatus.CREATED)
    Detail prospect(@Valid @RequestBody ProspectRequest request) {
        return service.createProspect(request.slug(), request.name(), request.city());
    }

    @PutMapping("/parc/{slug}")
    Detail update(@PathVariable String slug, @Valid @RequestBody FicheRequest request) {
        var c = request.client();
        return service.update(slug, new FicheForm(request.name(), request.city(), request.lifecycle(), request.clientSince(),
                new ClientForm(c.name(), c.kind(), c.ridet(), c.address(), c.directorName(), c.directorEmail(), c.directorPhone(),
                        c.billingEmail(), c.reminderEmail(), c.paymentDays())));
    }

    @PostMapping("/parc/{slug}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    Detail note(@PathVariable String slug, @Valid @RequestBody NoteRequest request) {
        return service.note(slug, request.kind(), request.text(), request.minutes());
    }

    @GetMapping("/recherche")
    List<SearchHit> search(@RequestParam(name = "q", defaultValue = "") String query) {
        return service.search(query);
    }
}
