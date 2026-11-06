package nc.ticagou.billing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.dhatim.fastexcel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.attendance.ChildAttendance;
import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingDtos.FunderRequestDto;
import nc.ticagou.billing.InvoiceCalculator.Kind;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.common.Iban;
import nc.ticagou.family.FamilyRepository;
import nc.ticagou.family.Guardian;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.Structure;
import nc.ticagou.structure.StructureRepository;

/**
 * Pièces de facturation en PDF A4 (ADR 0004, gabarits Thymeleaf {@code templates/facturation-*.html}) : facture,
 * avoir, demande au financeur avec relevé de présence, relance, reçu d'espèces, attestation annuelle ; et l'export
 * Excel des demandes du mois (ADR 0006). Produites à la demande à partir des pièces émises ; rien n'est stocké.
 */
@Service
public class BillingDocuments {

    public record Document(byte[] content, String fileName) {
    }

    /** Ligne affichée d'une pièce. */
    public record Row(String label, String detail, String amount, String tgc) {
    }

    public record Group(String title, List<Row> rows) {
    }

    record DayRow(String day, String arrived, String departed, String duration) {
    }

    record ChildStatement(String name, String beneficiary, int days, String hours, List<DayRow> rows) {
    }

    record RequestRow(String child, String beneficiary, int days, String invoice, String amount) {
    }

    record AttestationRow(String child, String born, String care, String aids, String family) {
    }

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteLineRepository creditNoteLines;
    private final FunderRequestService funderRequests;
    private final FunderRepository funders;
    private final PaymentService payments;
    private final PaymentAllocationRepository allocations;
    private final PaymentRepository paymentRepository;
    private final ReminderService reminders;
    private final BillingSettingsService settings;
    private final ChildRepository children;
    private final FamilyRepository families;
    private final GuardianRepository guardians;
    private final SectionRepository sections;
    private final StructureRepository structures;
    private final ChildAttendanceRepository attendances;
    private final InvoiceBalances balances;
    private final ITemplateEngine templates;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;

    public BillingDocuments(InvoiceService invoiceService, InvoiceRepository invoices, InvoiceLineRepository lines,
                            CreditNoteRepository creditNotes, CreditNoteLineRepository creditNoteLines,
                            FunderRequestService funderRequests, FunderRepository funders, PaymentService payments,
                            PaymentAllocationRepository allocations, PaymentRepository paymentRepository,
                            ReminderService reminders, BillingSettingsService settings, ChildRepository children,
                            FamilyRepository families, GuardianRepository guardians, SectionRepository sections,
                            StructureRepository structures, ChildAttendanceRepository attendances,
                            InvoiceBalances balances, ITemplateEngine templates, AuditService audit, Clock clock) {
        this.invoiceService = invoiceService;
        this.invoices = invoices;
        this.lines = lines;
        this.creditNotes = creditNotes;
        this.creditNoteLines = creditNoteLines;
        this.funderRequests = funderRequests;
        this.funders = funders;
        this.payments = payments;
        this.allocations = allocations;
        this.paymentRepository = paymentRepository;
        this.reminders = reminders;
        this.settings = settings;
        this.children = children;
        this.families = families;
        this.guardians = guardians;
        this.sections = sections;
        this.structures = structures;
        this.attendances = attendances;
        this.balances = balances;
        this.templates = templates;
        this.audit = audit;
        this.clock = clock;
        this.zone = clock.getZone();
    }

    // ---------------------------------------------------------------- facture

    @Transactional(readOnly = true)
    public Document invoice(UUID id) {
        return invoice(invoiceService.load(id));
    }

    /** Facture d'une structure donnée, hors requête utilisateur (envoi au portail parents). */
    @Transactional(readOnly = true)
    public Document invoice(UUID structureId, UUID id) {
        return invoice(invoices.findByIdAndStructureId(id, structureId)
                .orElseThrow(() -> ApiException.notFound("Facture introuvable.")));
    }

    private Document invoice(Invoice invoice) {
        var id = invoice.getId();
        var structure = structures.findById(invoice.getStructureId()).orElseThrow();
        var invoiceLines = lines.findByInvoiceIdOrderByPositionAsc(id);
        var childIds = invoiceLines.stream().map(InvoiceLine::getChildId).filter(Objects::nonNull).distinct().toList();
        var kids = children.findAllById(childIds).stream()
                .sorted(Comparator.comparing(Child::getBirthDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        boolean several = kids.size() > 1;
        boolean withTgc = invoice.getTgcTotal() != 0;
        var care = new ArrayList<Group>();
        var aids = new ArrayList<Row>();
        var fundersById = funders.findByStructureIdOrderByPriorityAscNameAsc(invoice.getStructureId()).stream()
                .collect(Collectors.toMap(Funder::getId, Function.identity()));
        for (var kid : kids) {
            var rows = invoiceLines.stream().filter(l -> kid.getId().equals(l.getChildId()) && l.getKind() != Kind.AIDE)
                    .map(l -> new Row(l.getLabel(), l.getDetail(), Formats.xpf(l.getAmount()),
                            withTgc ? (l.getTgcRate() == 0 ? "—" : Formats.xpf(l.getTgcAmount())) : null))
                    .toList();
            care.add(new Group(several ? kid.getFirstName() : null, rows));
            invoiceLines.stream().filter(l -> kid.getId().equals(l.getChildId()) && l.getKind() == Kind.AIDE)
                    .forEach(l -> aids.add(new Row(fundersById.containsKey(l.getFunderId())
                            ? fundersById.get(l.getFunderId()).getName() : l.getLabel(),
                            (several ? kid.getFirstName() + " · " : "") + aidDetail(fundersById.get(l.getFunderId()), l),
                            Formats.xpf(l.getAmount()), null)));
        }
        var vars = base(structure);
        vars.put("documentTitle", "Facture " + invoice.getNumber());
        vars.put("heading", "Facture de " + String.join(" et ", kids.stream().map(Child::displayName).toList()));
        vars.put("number", invoice.getNumber());
        vars.put("issuedLine", "Émise le " + Formats.date(invoice.getIssuedOn()));
        vars.put("payerName", invoice.getPayerName());
        vars.put("payerAddress", lines(invoice.getPayerAddress()));
        vars.put("childrenLine", String.join(" · ", kids.stream().map(k -> k.displayName()
                + sectionName(k).map(s -> ", " + s).orElse("")).toList()));
        var month = invoice.getMonth();
        vars.put("period", "%s au %s".formatted(Formats.date(month.atDay(1)), Formats.date(month.atEndOfMonth())));
        vars.put("contractText", invoice.getContractText());
        vars.put("care", care);
        vars.put("withTgc", withTgc);
        vars.put("careTotal", Formats.xpf(invoice.getCareTotal() + invoice.getTgcTotal()));
        vars.put("tgcLine", withTgc ? "dont TGC " + Formats.xpf(invoice.getTgcTotal()) : null);
        vars.put("aids", aids);
        vars.put("aidTotal", Formats.xpf(-invoice.getAidTotal()));
        vars.put("rounding", invoice.getRounding() == 0 ? null : Formats.xpf(invoice.getRounding()));
        vars.put("familyTotal", Formats.xpf(invoice.getFamilyTotal()));
        vars.put("dueLine", "À régler avant le " + Formats.date(invoice.getDueOn()));
        vars.put("transferLine", "Référence à indiquer : " + invoice.getNumber());
        vars.put("chequeLine", "À l’ordre de " + structure.getName() + ", à déposer à l’accueil");
        vars.put("footer", "Facture " + invoice.getNumber());
        return render("facturation-facture", vars, "facture-%s.pdf".formatted(invoice.getNumber()));
    }

    private static String aidDetail(Funder funder, InvoiceLine line) {
        if (funder == null) {
            return line.getDetail();
        }
        var base = switch (funder.getCategory()) {
            case EMPLOYEUR -> "Participation prévue par la convention entre l’employeur et la crèche";
            case PROVINCE, COMMUNE -> "Aide publique versée directement à la crèche";
            default -> "Aide à la garde versée directement à la crèche";
        };
        return base + (line.getDetail() == null ? "" : " · " + line.getDetail());
    }

    // ---------------------------------------------------------------- avoir

    @Transactional(readOnly = true)
    public Document creditNote(UUID id) {
        return creditNote(CurrentUser.get().structureId(), id);
    }

    /** Avoir d'une structure donnée, hors requête utilisateur (envoi au portail parents). */
    @Transactional(readOnly = true)
    public Document creditNote(UUID structureId, UUID id) {
        var note = creditNotes.findByIdAndStructureId(id, structureId)
                .orElseThrow(() -> ApiException.notFound("Avoir introuvable."));
        var invoice = invoices.findById(note.getInvoiceId()).orElseThrow();
        var structure = structures.findById(note.getStructureId()).orElseThrow();
        var vars = base(structure);
        vars.put("documentTitle", "Avoir " + note.getNumber());
        vars.put("number", note.getNumber());
        vars.put("issuedLine", "Émis le " + Formats.date(note.getIssuedOn()));
        vars.put("payerName", invoice.getPayerName());
        vars.put("payerAddress", lines(invoice.getPayerAddress()));
        vars.put("invoiceLine", "%s sur la facture %s de %s".formatted(note.isFullCancel() ? "Annulation totale"
                : "Avoir partiel", invoice.getNumber(), Formats.monthYear(invoice.getMonth())));
        vars.put("reason", note.getReason());
        vars.put("rows", creditNoteLines.findByCreditNoteIdOrderByPositionAsc(id).stream()
                .map(l -> new Row(l.getLabel(), null, Formats.xpf(l.getAmount()), null)).toList());
        vars.put("total", Formats.xpf(note.getFamilyTotal()));
        vars.put("aidLine", note.getAidTotal() == 0 ? null
                : "Les aides déduites de la facture (%s) sont également annulées.".formatted(Formats.xpf(note.getAidTotal())));
        vars.put("footer", "Avoir " + note.getNumber());
        return render("facturation-avoir", vars, "avoir-%s.pdf".formatted(note.getNumber()));
    }

    // ---------------------------------------------------------------- demande au financeur

    @Transactional(readOnly = true)
    public Document funderRequest(UUID id) {
        var request = funderRequests.get(id);
        var source = funderRequests.load(id);
        var structure = structures.findById(source.getStructureId()).orElseThrow();
        var funder = funders.findById(request.funderId()).orElseThrow();
        var month = request.month();
        var passages = attendances.findByStructureIdAndDayBetween(source.getStructureId(), month.atDay(1),
                month.atEndOfMonth()).stream().collect(Collectors.groupingBy(ChildAttendance::getChildId));
        var statements = request.lines().stream().map(l -> statement(l.childName(), l.beneficiary(),
                passages.getOrDefault(l.childId(), List.of()))).toList();
        var vars = base(structure);
        vars.put("documentTitle", "Demande de règlement " + request.number());
        vars.put("number", request.number());
        vars.put("issuedLine", "Établie le " + Formats.date(source.getCreatedAt().atZone(zone).toLocalDate()));
        vars.put("funderName", funder.getName());
        vars.put("funderLine", String.join(" · ", Stream.of(
                        funder.getConventionRef() == null ? null : "Convention n° " + funder.getConventionRef(),
                        funder.getContact()).filter(Objects::nonNull).toList()));
        vars.put("monthLabel", Formats.monthYear(month));
        vars.put("rows", request.lines().stream().map(l -> new RequestRow(l.childName(),
                l.beneficiary() == null ? "—" : l.beneficiary(), l.attendanceDays(), l.invoiceNumber(),
                Formats.xpf(l.amount()))).toList());
        vars.put("total", Formats.xpf(request.total()));
        vars.put("statements", statements);
        vars.put("footer", "Demande " + request.number() + " · " + funder.getName());
        return render("facturation-demande", vars, "demande-%s.pdf".formatted(request.number()));
    }

    private ChildStatement statement(String name, String beneficiary, List<ChildAttendance> passages) {
        var sorted = passages.stream().sorted(Comparator.comparing(ChildAttendance::getArrivedAt)).toList();
        long minutes = 0;
        var rows = new ArrayList<DayRow>();
        for (var p : sorted) {
            long m = p.getDepartedAt() == null ? 0 : Duration.between(p.getArrivedAt(), p.getDepartedAt()).toMinutes();
            minutes += m;
            rows.add(new DayRow(Formats.date(p.getDay()), Formats.time(p.getArrivedAt().atZone(zone).toLocalTime()),
                    p.getDepartedAt() == null ? "—" : Formats.time(p.getDepartedAt().atZone(zone).toLocalTime()),
                    Formats.duration(m)));
        }
        int days = (int) sorted.stream().map(ChildAttendance::getDay).distinct().count();
        return new ChildStatement(name, beneficiary, days, Formats.duration(minutes), rows);
    }

    /** Export Excel des demandes d'un mois (maquette « Tout exporter (Excel) »). */
    @Transactional
    public Document funderRequestsXlsx(YearMonth month) {
        var found = funderRequests.list(month);
        var out = new ByteArrayOutputStream();
        try {
            var workbook = new Workbook(out, "Ti'Cagou", "1.0");
            var sheet = workbook.newWorksheet("Demandes " + month);
            String[] titles = {"Financeur", "N° de demande", "Enfant", "Parent bénéficiaire", "Jours de présence",
                    "Facture famille", "Montant de l’aide (F)", "Envoyée le"};
            for (int c = 0; c < titles.length; c++) {
                sheet.value(0, c, titles[c]);
            }
            sheet.range(0, 0, 0, titles.length - 1).style().bold().fillColor("DCEFEE").set();
            int r = 1;
            for (FunderRequestDto request : found) {
                for (var line : request.lines()) {
                    sheet.value(r, 0, request.funderName());
                    sheet.value(r, 1, request.number());
                    sheet.value(r, 2, line.childName());
                    sheet.value(r, 3, line.beneficiary());
                    sheet.value(r, 4, line.attendanceDays());
                    sheet.value(r, 5, line.invoiceNumber());
                    sheet.value(r, 6, line.amount());
                    if (request.sentOn() != null) {
                        sheet.value(r, 7, request.sentOn());
                        sheet.style(r, 7).format("dd/mm/yyyy").set();
                    }
                    r++;
                }
            }
            sheet.value(r, 0, "Total");
            sheet.value(r, 6, found.stream().mapToLong(FunderRequestDto::total).sum());
            sheet.range(r, 0, r, titles.length - 1).style().bold().set();
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.record(AuditAction.FUNDER_REQUEST_EXPORTED, "funder_request", null,
                Map.of("month", month.toString(), "requests", found.size()));
        return new Document(out.toByteArray(), "demandes-financeurs-%s.xlsx".formatted(month));
    }

    // ---------------------------------------------------------------- relance

    @Transactional(readOnly = true)
    public Document reminder(UUID id) {
        var reminder = reminders.load(id);
        var invoice = invoiceService.load(reminder.getInvoiceId());
        var structure = structures.findById(invoice.getStructureId()).orElseThrow();
        var balance = balances.of(invoice.getId());
        var rule = settings.requireRule(invoice.getStructureId(), reminder.getCreatedOn());
        var vars = base(structure);
        vars.put("documentTitle", ReminderService.LEVELS.get(reminder.getLevel() - 1) + " · " + invoice.getNumber());
        vars.put("level", ReminderService.LEVELS.get(reminder.getLevel() - 1));
        vars.put("place", "%s, le %s".formatted(structure.getCity() == null ? "" : structure.getCity(),
                Formats.date(reminder.getCreatedOn())).replaceFirst("^, ", ""));
        vars.put("payerName", invoice.getPayerName());
        vars.put("payerAddress", lines(invoice.getPayerAddress()));
        vars.put("body", "Sauf erreur de notre part, la facture %s de %s, arrivée à échéance le %s, n’est pas encore réglée. Le montant restant dû est de %s."
                .formatted(invoice.getNumber(), Formats.monthYear(invoice.getMonth()), Formats.date(invoice.getDueOn()),
                        Formats.xpf(balance.remaining(invoice.getFamilyTotal()))));
        vars.put("request", "Nous vous remercions de bien vouloir effectuer le règlement avant le %s, par virement en indiquant la référence %s, par chèque ou directement à l’accueil."
                .formatted(Formats.date(reminder.getCreatedOn().plusDays(Math.max(rule.getPaymentDays(), 1))),
                        invoice.getNumber()));
        vars.put("signature", reminder.getAuthor() + " · " + structure.getName());
        vars.put("footer", vars.get("level") + " · facture " + invoice.getNumber());
        return render("facturation-relance", vars, "relance-%d-%s.pdf".formatted(reminder.getLevel(), invoice.getNumber()));
    }

    // ---------------------------------------------------------------- reçu d'espèces

    @Transactional(readOnly = true)
    public Document receipt(UUID paymentId) {
        var payment = payments.load(paymentId);
        if (payment.getReceiptNumber() == null) {
            throw ApiException.rule("no_receipt", "Un reçu numéroté n’est édité que pour un paiement en espèces.");
        }
        var dto = payments.get(paymentId);
        var structure = structures.findById(payment.getStructureId()).orElseThrow();
        var vars = base(structure);
        vars.put("documentTitle", "Reçu " + payment.getReceiptNumber());
        vars.put("number", payment.getReceiptNumber());
        vars.put("issuedLine", "Le " + Formats.date(payment.getPaidOn()));
        vars.put("payerName", payment.getPayerName());
        vars.put("amount", Formats.xpf(payment.getAmount()));
        vars.put("rows", dto.allocations().stream()
                .map(a -> new Row("Facture " + a.number(), null, Formats.xpf(a.amount()), null)).toList());
        vars.put("recordedBy", "Reçu par " + payment.getRecordedBy());
        vars.put("cancelled", payment.isCancelled() ? "Paiement annulé : " + payment.getCancelReason() : null);
        vars.put("footer", "Reçu " + payment.getReceiptNumber());
        return render("facturation-recu", vars, "recu-%s.pdf".formatted(payment.getReceiptNumber()));
    }

    // ---------------------------------------------------------------- attestation annuelle

    /**
     * Attestation de frais de garde d'une année : frais facturés pour les mois de l'année (factures non annulées),
     * aides déduites, part de la famille, et somme effectivement payée par la famille pendant l'année.
     */
    @Transactional
    public Document attestation(UUID familyId, int year) {
        var structureId = CurrentUser.get().structureId();
        var family = families.findByIdAndStructureId(familyId, structureId)
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
        var structure = structures.findById(structureId).orElseThrow();
        var found = invoices.findByFamilyIdOrderByMonthDescNumberDesc(familyId).stream()
                .filter(i -> i.getMonth().getYear() == year).toList();
        var byId = balances.of(found.stream().map(Invoice::getId).toList());
        var live = found.stream().filter(i -> !byId.get(i.getId()).cancelled()).toList();
        if (live.isEmpty()) {
            throw ApiException.rule("attestation_empty",
                    "Aucune facture pour %d : pas d’attestation à établir.".formatted(year));
        }
        var careByChild = new LinkedHashMap<UUID, long[]>();
        for (var line : lines.findByInvoiceIdIn(live.stream().map(Invoice::getId).toList())) {
            if (line.getChildId() == null) {
                continue;
            }
            var sums = careByChild.computeIfAbsent(line.getChildId(), k -> new long[2]);
            if (line.getKind() == Kind.AIDE) {
                sums[1] -= line.getAmount();
            } else {
                sums[0] += line.total();
            }
        }
        var kids = children.findAllById(careByChild.keySet()).stream()
                .sorted(Comparator.comparing(Child::getBirthDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        var rows = kids.stream().map(k -> {
            var s = careByChild.get(k.getId());
            return new AttestationRow(k.displayName(), Formats.date(k.getBirthDate()), Formats.xpf(s[0]),
                    Formats.xpf(s[1]), Formats.xpf(s[0] - s[1]));
        }).toList();
        long credited = live.stream().mapToLong(i -> byId.get(i.getId()).credited()).sum();
        long billed = live.stream().mapToLong(Invoice::getFamilyTotal).sum() - credited;
        var paymentIds = paymentRepository.findByFamilyIdIn(List.of(familyId)).stream()
                .filter(p -> !p.isCancelled() && p.getPaidOn().getYear() == year).map(Payment::getId).toList();
        long paid = allocations.findByPaymentIdIn(paymentIds).stream().filter(a -> a.getInvoiceId() != null)
                .mapToLong(PaymentAllocation::getAmount).sum();
        var payers = guardians.findByFamilyIdOrderByDisplayOrderAscLastNameAsc(familyId).stream()
                .filter(Guardian::isPayer).map(g -> g.getFirstName() + " " + g.getLastName()).toList();
        var user = CurrentUser.get();
        var vars = base(structure);
        vars.put("documentTitle", "Attestation de frais de garde " + year);
        vars.put("year", year);
        vars.put("place", "%s, le %s".formatted(structure.getCity() == null ? "" : structure.getCity(),
                Formats.date(LocalDate.now(clock))).replaceFirst("^, ", ""));
        vars.put("familyLine", payers.isEmpty() ? "Famille " + family.getLabel() : String.join(" et ", payers));
        vars.put("payerAddress", lines(BillingMonthAssembler.address(family)));
        vars.put("rows", rows);
        vars.put("billed", Formats.xpf(billed));
        vars.put("credited", credited == 0 ? null : Formats.xpf(credited));
        vars.put("paid", Formats.xpf(paid));
        vars.put("monthsLine", "Mois facturés : %s.".formatted(String.join(", ", live.stream()
                .sorted(Comparator.comparing(Invoice::getMonth))
                .map(i -> Formats.monthYear(i.getMonth()) + " (" + i.getNumber() + ")").toList())));
        vars.put("statement", "%s certifie que %s a réglé la somme de %s au titre des frais de garde payés en %d pour les enfants ci-dessous, aides des financeurs déduites."
                .formatted(user.displayName() + ", pour " + structure.getName(), vars.get("familyLine"),
                        Formats.xpf(paid), year));
        vars.put("signature", user.displayName() + " · " + structure.getName());
        vars.put("footer", "Attestation " + year + " · " + family.getLabel());
        audit.record(AuditAction.ATTESTATION_ISSUED, "family", familyId, Map.of("year", year, "paid", paid));
        return render("facturation-attestation", vars, "attestation-%d-%s.pdf".formatted(year,
                family.getLabel().replaceAll("[^\\p{L}\\p{N}]+", "-")));
    }

    // ---------------------------------------------------------------- outils

    private Map<String, Object> base(Structure structure) {
        var vars = new HashMap<String, Object>();
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
        vars.put("logo", structure.hasLogo() ? "data:%s;base64,%s".formatted(structure.getLogoContentType(),
                Base64.getEncoder().encodeToString(structure.getLogo())) : null);
        vars.put("invoiceFooter", lines(structure.getInvoiceFooter()));
        vars.put("rib", structure.isPrintRib() && structure.getIban() != null
                ? "RIB · %s%s · IBAN %s%s".formatted(structure.getBankHolder(),
                        structure.getBankName() == null ? "" : " · " + structure.getBankName(),
                        Iban.format(structure.getIban()),
                        structure.getBic() == null ? "" : " · BIC " + structure.getBic())
                : null);
        return vars;
    }

    private Optional<String> sectionName(Child child) {
        return child.getSectionId() == null ? Optional.empty()
                : sections.findById(child.getSectionId()).map(s -> s.getName());
    }

    private static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split("\\R"));
    }

    private Document render(String template, Map<String, Object> vars, String fileName) {
        var html = templates.process(template, new Context(Locale.FRANCE, vars));
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
        return new Document(out.toByteArray(), fileName);
    }
}
