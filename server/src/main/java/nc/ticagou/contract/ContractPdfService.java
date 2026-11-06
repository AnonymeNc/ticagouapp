package nc.ticagou.contract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.auth.CurrentUser;
import nc.ticagou.auth.Role;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.Formats;
import nc.ticagou.contract.ContractVersion.PlanningMode;
import nc.ticagou.contract.ForfaitCalculator.AdaptationBilling;
import nc.ticagou.family.Guardian;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.funder.TariffCriterion;
import nc.ticagou.funder.TariffScheduleRepository;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/**
 * PDF du contrat initial ou d'un avenant (ADR 0004) : gabarit Thymeleaf {@code templates/contrat.html}, converti par
 * openhtmltopdf. Produit à la demande à partir des données figées de la version ; rien n'est stocké.
 */
@Service
public class ContractPdfService {

    private final ContractService contracts;
    private final ChildRepository children;
    private final GuardianRepository guardians;
    private final SectionRepository sections;
    private final StructureRepository structures;
    private final TariffScheduleRepository tariffs;
    private final ITemplateEngine templates;
    private final Clock clock;

    public ContractPdfService(ContractService contracts, ChildRepository children, GuardianRepository guardians,
                              SectionRepository sections, StructureRepository structures,
                              TariffScheduleRepository tariffs, ITemplateEngine templates, Clock clock) {
        this.contracts = contracts;
        this.children = children;
        this.guardians = guardians;
        this.sections = sections;
        this.structures = structures;
        this.tariffs = tariffs;
        this.templates = templates;
        this.clock = clock;
    }

    public record Pdf(byte[] content, String fileName) {
    }

    record DayLine(String name, String arrives, String leaves, String duration) {
    }

    @Transactional(readOnly = true)
    public Pdf render(UUID contractId, int number) {
        return render(contractId, number, false);
    }

    /** {@code forSignature} : exemplaire proposé à la signature électronique (portail parents), sans « brouillon ». */
    @Transactional(readOnly = true)
    public Pdf render(UUID contractId, int number, boolean forSignature) {
        var contract = contracts.load(contractId);
        var version = contracts.version(contract, number);
        var vars = variables(contract, version);
        if (forSignature) {
            vars.put("draft", false);
        }
        vars.put("forSignature", forSignature);
        var html = templates.process("contrat", new Context(Locale.FRANCE, vars));
        var out = new ByteArrayOutputStream();
        try {
            var builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var name = number == 0 ? "contrat-%s.pdf".formatted(contract.getNumber())
                : "contrat-%s-avenant-%d.pdf".formatted(contract.getNumber(), number);
        return new Pdf(out.toByteArray(), name);
    }

    Map<String, Object> variables(Contract contract, ContractVersion version) {
        var structure = structures.findById(contract.getStructureId()).orElseThrow();
        var child = children.findById(contract.getChildId()).orElseThrow();
        var legal = guardians.findByFamilyIdOrderByDisplayOrderAscLastNameAsc(child.getFamilyId()).stream()
                .filter(Guardian::isLegalGuardian).toList();
        boolean amendment = version.getNumber() > 0;
        var vars = new HashMap<String, Object>();

        vars.put("title", amendment ? "Avenant %d au contrat d'accueil".formatted(version.getNumber()) : "Contrat d'accueil");
        vars.put("structureName", structure.getName());
        var line = new StringBuilder();
        if (structure.getCity() != null) {
            line.append(structure.getCity());
        }
        if (structure.getRidet() != null) {
            line.append(line.isEmpty() ? "" : " · ").append("RIDET ").append(structure.getRidet());
        }
        if (structure.getAgrementNumber() != null) {
            line.append(line.isEmpty() ? "" : " · ").append("Agrément n° ").append(structure.getAgrementNumber());
        }
        vars.put("structureLine", line.isEmpty() ? null : line.toString());
        vars.put("city", structure.getCity() != null ? structure.getCity() : "______________");
        vars.put("reference", "N° %s · version du %s".formatted(contract.getNumber(),
                Formats.date(version.isSigned() ? version.getSignedOn() : LocalDate.now(clock))));
        vars.put("draft", !version.isSigned());
        vars.put("footer", (amendment ? "Avenant %d · ".formatted(version.getNumber()) : "") + "Contrat N° " + contract.getNumber());

        var born = child.getBirthDate();
        vars.put("childLine", "%s %s, né(e) le %s".formatted(child.getFirstName(), child.getLastName(), Formats.date(born)));
        vars.put("sectionName", child.getSectionId() == null ? null
                : sections.findById(child.getSectionId()).map(s -> s.getName()).orElse(null));
        vars.put("guardians", legal.stream()
                .map(g -> "%s %s, %s".formatted(g.getFirstName(), g.getLastName(), g.getRelationship().toLowerCase(Locale.FRANCE)))
                .toList());
        vars.put("familySignatories", String.join(" · ", legal.stream().map(g -> g.getFirstName() + " " + g.getLastName()).toList()));
        var user = CurrentUser.find();
        vars.put("signatory", user.filter(u -> u.role() == Role.DIRECTION).map(u -> u.displayName() + ", direction")
                .orElse("La direction"));

        vars.put("amendment", amendment);
        vars.put("amendmentLine", "Le présent avenant modifie le contrat n° %s à compter du %s ; les autres dispositions du contrat restent inchangées."
                .formatted(contract.getNumber(), Formats.date(version.getEffectiveFrom())));
        vars.put("reason", version.getReason());
        vars.put("periodLine", "Le présent contrat prend effet le %s et prend fin le %s."
                .formatted(Formats.date(contract.getStartDate()), Formats.date(contract.getEndDate())));
        var sessions = contract.getAdaptationSessions();
        vars.put("adaptationLine", sessions.isEmpty() ? null
                : "Il est précédé d'une période d'adaptation du %s au %s (%d séances).".formatted(
                Formats.date(sessions.getFirst().sessionDate()), Formats.date(sessions.getLast().sessionDate()), sessions.size()));

        boolean fixed = version.getPlanningMode() == PlanningMode.FIXE;
        vars.put("fixed", fixed);
        vars.put("days", version.getDays().stream().map(d -> new DayLine(ForfaitCalculator.DAY_NAMES[d.dayOfWeek() - 1],
                Formats.time(d.arrivesAt()), Formats.time(d.leavesAt()),
                Formats.duration(Duration.between(d.arrivesAt(), d.leavesAt()).toMinutes()))).toList());
        vars.put("weekly", Formats.duration(version.getWeeklyMinutes()));
        vars.put("variableLine", fixed ? null : "Planning variable : %s réservées par mois. La famille transmet son planning %s avant chaque mois ; les heures réservées non utilisées restent dues, les heures en plus sont facturées au tarif horaire du contrat."
                .formatted(Formats.duration(version.getMonthlyMinutes()),
                        version.getPlanningNoticeDays() == null ? "au plus tôt" : version.getPlanningNoticeDays() + " jours"));

        var tariff = tariffs.findById(version.getTariffScheduleId()).orElseThrow();
        vars.put("tariffLine", (fixed ? "Le forfait est mensualisé sur %d semaines d'accueil par an, ".formatted(version.getWeeksPerYear())
                : "Le forfait correspond aux heures réservées, ")
                + "selon le barème « %s » de la structure, en vigueur au %s%s.".formatted(tariff.getLabel(),
                Formats.date(tariff.getValidFrom()), version.getTariffCriterion() == TariffCriterion.QUOTIENT_FAMILIAL
                        && version.getFamilyQuotient() != null
                        ? ", pour un quotient familial de " + Formats.xpf(version.getFamilyQuotient()) : ""));
        vars.put("feeLabel", "Forfait mensuel, %s à %s de l'heure".formatted(Formats.duration(version.getMonthlyMinutes()),
                Formats.xpf(version.getHourlyRate())));
        vars.put("monthlyFee", Formats.xpf(version.getMonthlyFee()));
        vars.put("registrationFee", version.getRegistrationFee() > 0 ? Formats.xpf(version.getRegistrationFee()) : null);
        boolean adaptationBilled = !amendment && !sessions.isEmpty() && contract.getAdaptationBilling() != AdaptationBilling.OFFERTE;
        vars.put("adaptationCost", adaptationBilled ? Formats.xpf(contract.getAdaptationCost()) : null);
        vars.put("adaptationCostLabel", contract.getAdaptationBilling() == AdaptationBilling.PREMIERE_HEURE_OFFERTE
                ? "Adaptation, au temps de présence (première heure offerte) à %s de l'heure".formatted(Formats.xpf(contract.getAdaptationRate()))
                : "Adaptation, au temps de présence à %s de l'heure".formatted(Formats.xpf(contract.getAdaptationRate())));
        vars.put("noticeLine", contract.getNoticeMonths() == 0
                ? "Chaque partie peut mettre fin au contrat par écrit."
                : "Chaque partie peut mettre fin au contrat par écrit, avec un préavis de %d mois.".formatted(contract.getNoticeMonths()));
        return vars;
    }
}
