package nc.ticagou.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.Funder.Kind;
import nc.ticagou.billing.FunderDtos.ChildFundingDto;
import nc.ticagou.billing.FunderDtos.ChildFundingRequest;
import nc.ticagou.billing.FunderDtos.FunderDto;
import nc.ticagou.billing.FunderDtos.FunderRequest;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;

/**
 * Financeurs et aides accordées aux enfants. Une aide aux familles se déduit des factures, puis se réclame au
 * financeur ; une subvention à la structure est seulement enregistrée (hors factures).
 */
@Service
public class FunderService {

    private final FunderRepository funders;
    private final ChildFundingRepository fundings;
    private final ChildRepository children;
    private final AuditService audit;
    private final Clock clock;

    public FunderService(FunderRepository funders, ChildFundingRepository fundings, ChildRepository children,
                         AuditService audit, Clock clock) {
        this.funders = funders;
        this.fundings = fundings;
        this.children = children;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FunderDto> list() {
        return funders.findByStructureIdOrderByPriorityAscNameAsc(CurrentUser.get().structureId()).stream()
                .map(f -> FunderDto.from(f, fundings.countByFunderId(f.getId())))
                .toList();
    }

    @Transactional
    public FunderDto create(FunderRequest request) {
        var funder = new Funder(CurrentUser.get().structureId());
        apply(funder, request);
        audit.record(AuditAction.FUNDER_CREATED, "funder", funder.getId(), Map.of("name", funder.getName()));
        return FunderDto.from(funder, 0);
    }

    /** Les factures émises gardent l'aide calculée ; la modification vaut pour les mois à venir. */
    @Transactional
    public FunderDto update(UUID id, FunderRequest request) {
        var funder = load(id);
        if (funder.getKind() != request.kind() && fundings.existsByFunderId(id)) {
            throw ApiException.rule("funder_kind_in_use",
                    "Des enfants bénéficient de cette aide : son type ne peut plus changer.");
        }
        apply(funder, request);
        audit.record(AuditAction.FUNDER_UPDATED, "funder", id, Map.of("name", funder.getName()));
        return FunderDto.from(funder, fundings.countByFunderId(id));
    }

    @Transactional
    public void delete(UUID id) {
        var funder = load(id);
        if (fundings.existsByFunderId(id)) {
            throw ApiException.conflict("funder_in_use",
                    "Des enfants bénéficient de cette aide : fixez plutôt sa date de fin.");
        }
        funders.delete(funder);
        try {
            funders.flush();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("funder_in_use",
                    "Ce financeur figure sur des factures : fixez plutôt sa date de fin.");
        }
        audit.record(AuditAction.FUNDER_DELETED, "funder", id, Map.of("name", funder.getName()));
    }

    private void apply(Funder funder, FunderRequest r) {
        var name = r.name().trim();
        boolean duplicate = funders.findByStructureIdOrderByPriorityAscNameAsc(funder.getStructureId()).stream()
                .anyMatch(o -> !o.getId().equals(funder.getId()) && o.getName().equalsIgnoreCase(name));
        if (duplicate) {
            throw ApiException.conflict("funder_exists", "Ce financeur existe déjà.");
        }
        boolean fixed = r.calculation() == InvoiceCalculator.Calculation.MONTANT_FIXE;
        funder.update(name, r.kind(), r.category(), r.calculation(), fixed ? r.amount() : 0, fixed ? 0 : r.rate(),
                r.rateBase(), r.monthlyCap(), r.priority(), r.validFrom(), r.validTo(), blankToNull(r.conventionRef()),
                blankToNull(r.contact()));
        funders.saveAndFlush(funder);
    }

    Funder load(UUID id) {
        return funders.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Financeur introuvable."));
    }

    // ---------------------------------------------------------------- aides accordées

    @Transactional(readOnly = true)
    public List<ChildFundingDto> listForChild(UUID childId) {
        var child = loadChild(childId);
        var byId = funders.findByStructureIdOrderByPriorityAscNameAsc(child.getStructureId()).stream()
                .collect(Collectors.toMap(Funder::getId, Function.identity()));
        return fundings.findByChildIdOrderByFromDayDesc(childId).stream()
                .map(c -> ChildFundingDto.from(c, byId.get(c.getFunderId())))
                .toList();
    }

    @Transactional
    public ChildFundingDto create(UUID childId, ChildFundingRequest request) {
        var child = loadChild(childId);
        var funding = new ChildFunding(child.getStructureId(), childId, Instant.now(clock));
        var funder = apply(funding, request);
        audit.record(AuditAction.CHILD_FUNDING_CREATED, "child_funding", funding.getId(),
                Map.of("child", child.displayName(), "funder", funder.getName()));
        return ChildFundingDto.from(funding, funder);
    }

    @Transactional
    public ChildFundingDto update(UUID childId, UUID id, ChildFundingRequest request) {
        var child = loadChild(childId);
        var funding = loadFunding(childId, id);
        var funder = apply(funding, request);
        audit.record(AuditAction.CHILD_FUNDING_UPDATED, "child_funding", id,
                Map.of("child", child.displayName(), "funder", funder.getName()));
        return ChildFundingDto.from(funding, funder);
    }

    @Transactional
    public void delete(UUID childId, UUID id) {
        var child = loadChild(childId);
        var funding = loadFunding(childId, id);
        fundings.delete(funding);
        audit.record(AuditAction.CHILD_FUNDING_DELETED, "child_funding", id,
                Map.of("child", child.displayName(), "funder", load(funding.getFunderId()).getName()));
    }

    private Funder apply(ChildFunding funding, ChildFundingRequest r) {
        var funder = load(r.funderId());
        if (funder.getKind() != Kind.AIDE_FAMILLE) {
            throw ApiException.rule("funder_not_family_aid",
                    "« %s » est une subvention à la structure : elle ne s’accorde pas à un enfant."
                            .formatted(funder.getName()));
        }
        // Une aide sans date de fin vaut jusqu'à la fin de validité du financeur (le calcul s'y limite).
        boolean startsOutside = r.fromDay().isBefore(funder.getValidFrom())
                || (funder.getValidTo() != null && r.fromDay().isAfter(funder.getValidTo()));
        boolean endsOutside = funder.getValidTo() != null && r.toDay() != null && r.toDay().isAfter(funder.getValidTo());
        if (startsOutside || endsOutside) {
            throw ApiException.rule("funding_outside_funder",
                    "L’aide « %s » est valable du %s%s : la période accordée doit y être comprise."
                            .formatted(funder.getName(), Formats.date(funder.getValidFrom()),
                                    funder.getValidTo() == null ? "" : " au " + Formats.date(funder.getValidTo())));
        }
        boolean overlap = fundings.findByChildIdOrderByFromDayDesc(funding.getChildId()).stream()
                .filter(o -> !o.getId().equals(funding.getId()) && o.getFunderId().equals(funder.getId()))
                .anyMatch(o -> BillingSettingsService.overlaps(o.getFromDay(), o.getToDay(), r.fromDay(), r.toDay()));
        if (overlap) {
            throw ApiException.rule("funding_overlap",
                    "L’enfant bénéficie déjà de « %s » sur cette période.".formatted(funder.getName()));
        }
        boolean fixed = funder.getCalculation() == InvoiceCalculator.Calculation.MONTANT_FIXE;
        funding.update(funder.getId(), r.fromDay(), r.toDay(), blankToNull(r.beneficiary()),
                blankToNull(r.fileNumber()), fixed ? r.amountOverride() : null, fixed ? null : r.rateOverride());
        fundings.saveAndFlush(funding);
        return funder;
    }

    private ChildFunding loadFunding(UUID childId, UUID id) {
        return fundings.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .filter(f -> f.getChildId().equals(childId))
                .orElseThrow(() -> ApiException.notFound("Aide introuvable."));
    }

    private Child loadChild(UUID childId) {
        return children.findByIdAndStructureId(childId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
