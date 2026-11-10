package nc.ticagou.enrollment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.enrollment.EnrollmentDtos.RequestForm;
import nc.ticagou.enrollment.OnlineEnrollmentService.PublicForm;
import nc.ticagou.enrollment.OnlineEnrollmentService.Question;
import nc.ticagou.enrollment.OnlineEnrollmentService.QuestionKind;
import nc.ticagou.enrollment.OnlineEnrollmentService.Settings;

/** Pré-inscription en ligne (lot 3, ADR 0020) : formulaire public et réglages de la direction. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Inscriptions", description = "Pré-inscription en ligne : formulaire public, questions, réglages")
public class OnlineEnrollmentController {

    /** Demande publique : {@code website} est un champ piège laissé vide par les humains. */
    public record PublicSubmission(@Valid @NotNull RequestForm request, Map<UUID, @Size(max = 1000) String> answers,
                                   boolean consent, String website, Long startedAt) {
    }

    public record QuestionRequest(@NotNull @Size(max = 200) String label, @NotNull QuestionKind kind, List<String> options,
                                  boolean required, @Min(0) int displayOrder) {
    }

    private static final String DIRECTION = "hasRole('DIRECTION')";

    private final OnlineEnrollmentService online;

    public OnlineEnrollmentController(OnlineEnrollmentService online) {
        this.online = online;
    }

    @GetMapping("/public/preinscription")
    @Operation(summary = "Formulaire public : crèche, texte d'accueil, critères et questions en vigueur (sans connexion)")
    public PublicForm form() {
        return online.publicForm();
    }

    @PostMapping("/public/preinscription")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Déposer une pré-inscription (sans connexion) ; même réponse si l'anti-abus l'écarte")
    public Map<String, String> submit(@Valid @RequestBody PublicSubmission submission, HttpServletRequest http) {
        online.submit(new OnlineEnrollmentService.Submission(submission.request(), submission.answers(), submission.consent(),
                submission.website(), submission.startedAt()), http.getRemoteAddr());
        return Map.of("message", "Votre demande est bien envoyée : la crèche vous recontactera.");
    }

    @GetMapping("/enrollment/online")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Ouverture du formulaire public et texte d'accueil")
    public Settings settings() {
        return online.settings();
    }

    @PutMapping("/enrollment/online")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Ouvrir ou fermer le formulaire public, changer le texte d'accueil")
    public Settings update(@RequestBody Settings settings) {
        return online.updateSettings(settings);
    }

    @GetMapping("/enrollment/questions")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Questions posées aux familles, en vigueur ou closes")
    public List<Question> questions() {
        return online.allQuestions();
    }

    @PostMapping("/enrollment/questions")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une question au formulaire public, dès aujourd'hui")
    public Question add(@Valid @RequestBody QuestionRequest q) {
        return online.addQuestion(q.label(), q.kind(), q.options(), q.required(), q.displayOrder());
    }

    @DeleteMapping("/enrollment/questions/{id}")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ne plus poser cette question (les réponses reçues restent)")
    public void retire(@PathVariable UUID id) {
        online.retireQuestion(id);
    }
}
