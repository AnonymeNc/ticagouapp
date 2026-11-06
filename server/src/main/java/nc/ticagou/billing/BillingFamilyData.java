package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.billing.AbsenceDtos.AbsenceDto;
import nc.ticagou.billing.BillingDtos.InvoiceDto;
import nc.ticagou.billing.FunderDtos.ChildFundingDto;
import nc.ticagou.billing.PaymentDtos.PaymentDto;

/** Part « facturation » de l'export et de la purge des données d'une famille. */
@Component
public class BillingFamilyData {

    /** Factures (avec avoirs, lettrage et historique), paiements, absences et aides accordées. */
    public record BillingExport(List<InvoiceDto> invoices, List<PaymentDto> payments, List<AbsenceDto> absences,
                                List<ChildFundingDto> fundings) {
    }

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ChildAbsenceRepository absences;
    private final ChildFundingRepository fundings;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final AbsenceService absenceService;
    private final FunderService funderService;

    public BillingFamilyData(InvoiceRepository invoices, PaymentRepository payments, ChildAbsenceRepository absences,
                             ChildFundingRepository fundings, InvoiceService invoiceService,
                             PaymentService paymentService, AbsenceService absenceService,
                             FunderService funderService) {
        this.invoices = invoices;
        this.payments = payments;
        this.absences = absences;
        this.fundings = fundings;
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
        this.absenceService = absenceService;
        this.funderService = funderService;
    }

    /** Factures ou paiements : pièces comptables à conserver, la famille ne peut pas être purgée. */
    @Transactional(readOnly = true)
    public boolean hasAccountingRecords(UUID familyId) {
        return invoices.existsByFamilyIdIn(List.of(familyId)) || !payments.findByFamilyIdIn(List.of(familyId)).isEmpty();
    }

    @Transactional(readOnly = true)
    public BillingExport export(UUID familyId, Collection<UUID> childIds) {
        return new BillingExport(
                invoices.findByFamilyIdOrderByMonthDescNumberDesc(familyId).stream()
                        .map(i -> invoiceService.get(i.getId())).toList(),
                paymentService.toDtos(payments.findByFamilyIdIn(List.of(familyId))),
                childIds.stream().flatMap(id -> absenceService.listForChild(id).stream()).toList(),
                childIds.stream().flatMap(id -> funderService.listForChild(id).stream()).toList());
    }

    /** Absences et aides accordées des enfants purgés (la purge a vérifié qu'il n'y a aucune facture). */
    @Transactional
    public void purge(Collection<UUID> childIds) {
        absences.deleteAll(absences.findByChildIdIn(childIds));
        fundings.deleteAll(fundings.findByChildIdIn(childIds));
        absences.flush();
        fundings.flush();
    }
}
