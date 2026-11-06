package nc.ticagou.funder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.contract.ContractVersionRepository;
import nc.ticagou.funder.TariffDtos.DuplicateRequest;
import nc.ticagou.funder.TariffDtos.TariffScheduleDto;
import nc.ticagou.funder.TariffDtos.TariffScheduleRequest;

/**
 * Barèmes datés. Deux barèmes d'une structure ne se chevauchent jamais : à une date donnée, un seul
 * s'applique. Les contrats figent le tarif de leur version : modifier un barème ne change pas un contrat signé.
 */
@Service
public class TariffService {

    private final TariffScheduleRepository schedules;
    private final ContractVersionRepository contractVersions;
    private final AuditService audit;

    public TariffService(TariffScheduleRepository schedules, ContractVersionRepository contractVersions,
                         AuditService audit) {
        this.schedules = schedules;
        this.contractVersions = contractVersions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TariffScheduleDto> list() {
        return schedules.findByStructureIdOrderByValidFromDesc(CurrentUser.get().structureId()).stream()
                .map(s -> TariffScheduleDto.from(s, contractVersions.existsByTariffScheduleId(s.getId())))
                .toList();
    }

    /** Barème applicable à une date, pour la structure de l'utilisateur. */
    @Transactional(readOnly = true)
    public Optional<TariffSchedule> applicable(LocalDate day) {
        return schedules.findByStructureIdOrderByValidFromDesc(CurrentUser.get().structureId()).stream()
                .filter(s -> s.isValidOn(day))
                .findFirst();
    }

    public TariffSchedule requireApplicable(LocalDate day) {
        return applicable(day).orElseThrow(() -> ApiException.rule("no_tariff",
                "Aucun barème ne s’applique au %s. Ajoutez-le dans Paramètres › Barème et facturation."
                        .formatted(Formats.date(day))));
    }

    @Transactional
    public TariffScheduleDto create(TariffScheduleRequest request) {
        var schedule = new TariffSchedule(CurrentUser.get().structureId());
        apply(schedule, request);
        audit.record(AuditAction.TARIFF_CREATED, "tariff_schedule", schedule.getId(), Map.of("label", schedule.getLabel()));
        return TariffScheduleDto.from(schedule, false);
    }

    @Transactional
    public TariffScheduleDto update(UUID id, TariffScheduleRequest request) {
        var schedule = load(id);
        apply(schedule, request);
        audit.record(AuditAction.TARIFF_UPDATED, "tariff_schedule", id, Map.of("label", schedule.getLabel()));
        return TariffScheduleDto.from(schedule, contractVersions.existsByTariffScheduleId(id));
    }

    /** Copie d'un barème pour une nouvelle période (« Dupliquer pour 2027 ») ; l'ancien se termine la veille. */
    @Transactional
    public TariffScheduleDto duplicate(UUID id, DuplicateRequest request) {
        var source = load(id);
        if (!request.validFrom().isAfter(source.getValidFrom())) {
            throw ApiException.rule("tariff_period",
                    "Le nouveau barème doit commencer après le début de celui qu’il remplace.");
        }
        if (source.getValidTo() == null || !source.getValidTo().isBefore(request.validFrom())) {
            source.update(source.getLabel(), source.getCriterion(), source.getWeeksPerYear(),
                    source.getRegistrationFee(), source.getValidFrom(), request.validFrom().minusDays(1),
                    source.getBands());
            schedules.saveAndFlush(source);
        }
        var copy = new TariffSchedule(source.getStructureId());
        checkOverlap(copy, request.validFrom(), null);
        copy.update(request.label().trim(), source.getCriterion(), source.getWeeksPerYear(),
                source.getRegistrationFee(), request.validFrom(), null, source.getBands());
        schedules.saveAndFlush(copy);
        audit.record(AuditAction.TARIFF_CREATED, "tariff_schedule", copy.getId(),
                Map.of("label", copy.getLabel(), "duplicatedFrom", id.toString()));
        return TariffScheduleDto.from(copy, false);
    }

    @Transactional
    public void delete(UUID id) {
        var schedule = load(id);
        if (contractVersions.existsByTariffScheduleId(id)) {
            throw ApiException.conflict("tariff_in_use",
                    "Ce barème est utilisé par des contrats : fixez plutôt sa date de fin.");
        }
        schedules.delete(schedule);
        audit.record(AuditAction.TARIFF_DELETED, "tariff_schedule", id, Map.of("label", schedule.getLabel()));
    }

    private void apply(TariffSchedule schedule, TariffScheduleRequest r) {
        checkOverlap(schedule, r.validFrom(), r.validTo());
        schedule.update(r.label().trim(), r.criterion(), r.weeksPerYear(), r.registrationFee(), r.validFrom(),
                r.validTo(), r.bands().stream().map(b -> new TariffBand(b.lowerBound(), b.hourlyRate())).toList());
        schedules.saveAndFlush(schedule);
    }

    private void checkOverlap(TariffSchedule schedule, LocalDate from, LocalDate to) {
        schedules.findByStructureIdOrderByValidFromDesc(schedule.getStructureId()).stream()
                .filter(other -> !other.getId().equals(schedule.getId()))
                .filter(other -> other.overlaps(from, to))
                .findFirst()
                .ifPresent(other -> {
                    throw ApiException.rule("tariff_overlap",
                            "Cette période chevauche le barème « %s ». Fixez d’abord sa date de fin."
                                    .formatted(other.getLabel()));
                });
    }

    private TariffSchedule load(UUID id) {
        return schedules.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Barème introuvable."));
    }
}
