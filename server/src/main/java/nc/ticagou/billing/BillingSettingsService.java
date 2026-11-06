package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingSettingsDtos.AbsenceReasonDto;
import nc.ticagou.billing.BillingSettingsDtos.AbsenceReasonRequest;
import nc.ticagou.billing.BillingSettingsDtos.BillingRuleDto;
import nc.ticagou.billing.BillingSettingsDtos.BillingRuleRequest;
import nc.ticagou.billing.BillingSettingsDtos.TgcRateDto;
import nc.ticagou.billing.BillingSettingsDtos.TgcRateRequest;
import nc.ticagou.billing.InvoiceCalculator.ExtraRateMode;
import nc.ticagou.billing.Xpf.Mode;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;

/**
 * Paramètres de facturation datés : règles (heures complémentaires, dépassements, absences, échéance, arrondi),
 * motifs d'absence et taux de TGC. Deux règles, ou deux taux d'une même prestation, ne se chevauchent jamais.
 * Les factures émises gardent les montants calculés : modifier un paramètre ne change que les mois à venir.
 */
@Service
public class BillingSettingsService {

    /** Motifs créés au premier lancement : libellé, déductible, justificatif, carence, préavis. */
    static final List<Object[]> DEFAULT_REASONS = List.of(
            new Object[] {"Maladie avec certificat médical", true, true, true, false},
            new Object[] {"Hospitalisation de l’enfant", true, true, false, false},
            new Object[] {"Fermeture exceptionnelle de la structure", true, false, false, false},
            new Object[] {"Éviction décidée par la direction", true, false, false, false},
            new Object[] {"Congés de la famille prévenus à l’avance", true, false, false, true},
            new Object[] {"Autre absence (non déduite)", false, false, false, false});

    private final BillingRuleRepository rules;
    private final AbsenceReasonRepository reasons;
    private final TgcRateRepository tgcRates;
    private final ChildAbsenceRepository absences;
    private final AuditService audit;

    public BillingSettingsService(BillingRuleRepository rules, AbsenceReasonRepository reasons,
                                  TgcRateRepository tgcRates, ChildAbsenceRepository absences, AuditService audit) {
        this.rules = rules;
        this.reasons = reasons;
        this.tgcRates = tgcRates;
        this.absences = absences;
        this.audit = audit;
    }

    /** Valeurs par défaut d'une nouvelle structure (comme la migration V7 pour les structures existantes). */
    @Transactional
    public void createDefaults(UUID structureId, LocalDate today) {
        // Dès l'année précédente : une structure qui s'équipe en janvier peut facturer décembre.
        var from = today.minusYears(1).withDayOfYear(1);
        var rule = new BillingRule(structureId);
        rule.update(from, null, ExtraRateMode.CONTRAT, 0, 10, 15, 1000, 10, 1, 15, 10, 1, Mode.PROCHE);
        rules.save(rule);
        for (int i = 0; i < DEFAULT_REASONS.size(); i++) {
            var d = DEFAULT_REASONS.get(i);
            var reason = new AbsenceReason(structureId);
            reason.update((String) d[0], (boolean) d[1], (boolean) d[2], (boolean) d[3], (boolean) d[4], i, true);
            reasons.save(reason);
        }
        for (var service : InvoiceCalculator.Service.values()) {
            var rate = new TgcRate(structureId);
            rate.update(service, 0, true, from, null);
            tgcRates.save(rate);
        }
    }

    // ---------------------------------------------------------------- règles

    @Transactional(readOnly = true)
    public List<BillingRuleDto> listRules() {
        return rules.findByStructureIdOrderByValidFromDesc(CurrentUser.get().structureId()).stream()
                .map(BillingRuleDto::from).toList();
    }

    /** Règle applicable à une date ; sans règle, la facturation est bloquée avec un message clair. */
    @Transactional(readOnly = true)
    public BillingRule requireRule(UUID structureId, LocalDate day) {
        return rules.findByStructureIdOrderByValidFromDesc(structureId).stream()
                .filter(r -> r.isValidOn(day))
                .findFirst()
                .orElseThrow(() -> ApiException.rule("no_billing_rule",
                        "Aucune règle de facturation ne s’applique au %s. Ajoutez-la dans Paramètres › Barème et facturation."
                                .formatted(Formats.date(day))));
    }

    @Transactional
    public BillingRuleDto createRule(BillingRuleRequest request) {
        var rule = new BillingRule(CurrentUser.get().structureId());
        apply(rule, request);
        audit.record(AuditAction.BILLING_RULE_CREATED, "billing_rule", rule.getId(),
                Map.of("validFrom", rule.getValidFrom().toString()));
        return BillingRuleDto.from(rule);
    }

    @Transactional
    public BillingRuleDto updateRule(UUID id, BillingRuleRequest request) {
        var rule = loadRule(id);
        apply(rule, request);
        audit.record(AuditAction.BILLING_RULE_UPDATED, "billing_rule", id,
                Map.of("validFrom", rule.getValidFrom().toString()));
        return BillingRuleDto.from(rule);
    }

    @Transactional
    public void deleteRule(UUID id) {
        var rule = loadRule(id);
        if (rules.findByStructureIdOrderByValidFromDesc(rule.getStructureId()).size() == 1) {
            throw ApiException.rule("last_billing_rule",
                    "C’est la seule règle de facturation : modifiez-la plutôt que de la supprimer.");
        }
        rules.delete(rule);
        audit.record(AuditAction.BILLING_RULE_DELETED, "billing_rule", id,
                Map.of("validFrom", rule.getValidFrom().toString()));
    }

    private void apply(BillingRule rule, BillingRuleRequest r) {
        rules.findByStructureIdOrderByValidFromDesc(rule.getStructureId()).stream()
                .filter(other -> !Objects.equals(other.getId(), rule.getId()))
                .filter(other -> overlaps(other.getValidFrom(), other.getValidTo(), r.validFrom(), r.validTo()))
                .findFirst()
                .ifPresent(other -> {
                    throw ApiException.rule("billing_rule_overlap",
                            "Cette période chevauche la règle en vigueur depuis le %s. Fixez d’abord sa date de fin."
                                    .formatted(Formats.date(other.getValidFrom())));
                });
        rule.update(r.validFrom(), r.validTo(), r.extraRateMode(),
                r.extraRateMode() == ExtraRateMode.FIXE ? r.extraHourlyRate() : 0, r.extraToleranceMinutes(),
                r.extraRoundingMinutes(), r.overtimeFee(), r.overtimeToleranceMinutes(), r.sickWaitingDays(),
                r.leaveNoticeDays(), r.paymentDays(), r.totalRoundingStep(), r.totalRoundingMode());
        rule.setDefaultBillingMode(r.defaultBillingMode());
        rules.saveAndFlush(rule);
    }

    private BillingRule loadRule(UUID id) {
        return rules.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Règle de facturation introuvable."));
    }

    // ---------------------------------------------------------------- motifs d'absence

    @Transactional(readOnly = true)
    public List<AbsenceReasonDto> listReasons() {
        return reasons.findByStructureIdOrderByDisplayOrderAscLabelAsc(CurrentUser.get().structureId()).stream()
                .map(r -> AbsenceReasonDto.from(r, absences.existsByReasonId(r.getId())))
                .toList();
    }

    @Transactional
    public AbsenceReasonDto createReason(AbsenceReasonRequest request) {
        var reason = new AbsenceReason(CurrentUser.get().structureId());
        apply(reason, request);
        audit.record(AuditAction.ABSENCE_REASON_CREATED, "absence_reason", reason.getId(),
                Map.of("label", reason.getLabel()));
        return AbsenceReasonDto.from(reason, false);
    }

    /** Les absences déjà facturées gardent leur déduction : la facture émise ne change pas. */
    @Transactional
    public AbsenceReasonDto updateReason(UUID id, AbsenceReasonRequest request) {
        var reason = loadReason(id);
        apply(reason, request);
        audit.record(AuditAction.ABSENCE_REASON_UPDATED, "absence_reason", id, Map.of("label", reason.getLabel()));
        return AbsenceReasonDto.from(reason, absences.existsByReasonId(id));
    }

    @Transactional
    public void deleteReason(UUID id) {
        var reason = loadReason(id);
        if (absences.existsByReasonId(id)) {
            throw ApiException.conflict("absence_reason_in_use",
                    "Des absences portent ce motif : désactivez-le plutôt que de le supprimer.");
        }
        reasons.delete(reason);
        audit.record(AuditAction.ABSENCE_REASON_DELETED, "absence_reason", id, Map.of("label", reason.getLabel()));
    }

    private void apply(AbsenceReason reason, AbsenceReasonRequest r) {
        var label = r.label().trim();
        boolean duplicate = reasons.findByStructureIdOrderByDisplayOrderAscLabelAsc(reason.getStructureId()).stream()
                .anyMatch(o -> !Objects.equals(o.getId(), reason.getId()) && o.getLabel().equalsIgnoreCase(label));
        if (duplicate) {
            throw ApiException.conflict("absence_reason_exists", "Ce motif d’absence existe déjà.");
        }
        // Carence, préavis et justificatif n'ont de sens que pour un motif déductible.
        reason.update(label, r.deductible(), r.deductible() && r.proofRequired(),
                r.deductible() && r.waitingDaysApply(), r.deductible() && r.noticeApplies(), r.displayOrder(),
                r.active());
        reasons.saveAndFlush(reason);
    }

    AbsenceReason loadReason(UUID id) {
        return reasons.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Motif d’absence introuvable."));
    }

    // ---------------------------------------------------------------- TGC

    @Transactional(readOnly = true)
    public List<TgcRateDto> listTgcRates() {
        return tgcRates.findByStructureIdOrderByServiceAscValidFromDesc(CurrentUser.get().structureId()).stream()
                .map(TgcRateDto::from).toList();
    }

    /** Taux en vigueur à une date, par prestation ; une prestation sans taux est à 0 %. */
    @Transactional(readOnly = true)
    public Map<InvoiceCalculator.Service, Integer> tgcRatesOn(UUID structureId, LocalDate day) {
        var result = new EnumMap<InvoiceCalculator.Service, Integer>(InvoiceCalculator.Service.class);
        for (var service : InvoiceCalculator.Service.values()) {
            result.put(service, 0);
        }
        tgcRates.findByStructureIdOrderByServiceAscValidFromDesc(structureId).stream()
                .filter(r -> r.isValidOn(day))
                .forEach(r -> result.put(r.getService(), r.getRate()));
        return result;
    }

    @Transactional
    public TgcRateDto createTgcRate(TgcRateRequest request) {
        var rate = new TgcRate(CurrentUser.get().structureId());
        apply(rate, request);
        audit.record(AuditAction.TGC_RATE_CREATED, "tgc_rate", rate.getId(), tgcDetails(rate));
        return TgcRateDto.from(rate);
    }

    @Transactional
    public TgcRateDto updateTgcRate(UUID id, TgcRateRequest request) {
        var rate = loadTgcRate(id);
        apply(rate, request);
        audit.record(AuditAction.TGC_RATE_UPDATED, "tgc_rate", id, tgcDetails(rate));
        return TgcRateDto.from(rate);
    }

    @Transactional
    public void deleteTgcRate(UUID id) {
        var rate = loadTgcRate(id);
        tgcRates.delete(rate);
        audit.record(AuditAction.TGC_RATE_DELETED, "tgc_rate", id, tgcDetails(rate));
    }

    private void apply(TgcRate rate, TgcRateRequest r) {
        UUID structureId = CurrentUser.get().structureId();
        tgcRates.findByStructureIdOrderByServiceAscValidFromDesc(structureId).stream()
                .filter(other -> !Objects.equals(other.getId(), rate.getId()) && other.getService() == r.service())
                .filter(other -> overlaps(other.getValidFrom(), other.getValidTo(), r.validFrom(), r.validTo()))
                .findFirst()
                .ifPresent(other -> {
                    throw ApiException.rule("tgc_rate_overlap",
                            "Un taux de TGC s’applique déjà à cette prestation depuis le %s. Fixez d’abord sa date de fin."
                                    .formatted(Formats.date(other.getValidFrom())));
                });
        rate.update(r.service(), r.rate(), r.exempt(), r.validFrom(), r.validTo());
        tgcRates.saveAndFlush(rate);
    }

    private static Map<String, Object> tgcDetails(TgcRate rate) {
        return Map.of("service", rate.getService().name(), "rate", rate.getRate(), "exempt", rate.isExempt(),
                "validFrom", rate.getValidFrom().toString());
    }

    private TgcRate loadTgcRate(UUID id) {
        return tgcRates.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Taux de TGC introuvable."));
    }

    static boolean overlaps(LocalDate from1, LocalDate to1, LocalDate from2, LocalDate to2) {
        return (to2 == null || !from1.isAfter(to2)) && (to1 == null || !from2.isAfter(to1));
    }
}
