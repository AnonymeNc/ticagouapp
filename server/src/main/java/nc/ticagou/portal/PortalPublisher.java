package nc.ticagou.portal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.journal.DailyEntry;
import nc.ticagou.journal.DailyEntryRepository;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.LicenseService;

/**
 * Ajoute à la file d'envoi les changements susceptibles d'intéresser les parents, dans la transaction qui les
 * produit. Rien n'est noté quand le portail est désactivé, en cours d'effacement ou absent de la licence. Un élément
 * déjà en attente n'est pas dupliqué : son contenu est construit à l'envoi.
 */
@Component
public class PortalPublisher {

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    /** Durée de conservation des transmissions et des photos sur le portail (ADR 0010). */
    static final Period PHOTO_RETENTION = Period.ofDays(60);

    private final PortalSettingsRepository settings;
    private final PortalOutboxRepository outbox;
    private final LicenseService licenses;
    private final DailyEntryRepository entries;
    private final ChildAttendanceRepository attendances;
    private final StructureNewsRepository news;
    private final Clock clock;

    public PortalPublisher(PortalSettingsRepository settings, PortalOutboxRepository outbox, LicenseService licenses,
                           DailyEntryRepository entries, ChildAttendanceRepository attendances,
                           StructureNewsRepository news, Clock clock) {
        this.settings = settings;
        this.outbox = outbox;
        this.licenses = licenses;
        this.entries = entries;
        this.attendances = attendances;
        this.news = news;
        this.clock = clock;
    }

    @EventListener
    public void on(PortalChange change) {
        var current = settings.findById(change.structureId());
        if (current.isEmpty() || !current.get().isActive() || !licenses.allows(LicenseModule.PORTAIL_PARENTS)) {
            return;
        }
        enqueue(change);
        if (change.type() == PortalItemType.CHILD) {
            // L'autorisation photo a pu changer : les photos encore visibles sur le portail sont réexaminées.
            var since = LocalDate.now(clock.withZone(NOUMEA)).minus(PHOTO_RETENTION);
            for (var kind : List.of(DailyEntry.Kind.PHOTO, DailyEntry.Kind.VIDEO)) {
                for (var entryId : entries.idsForChildSince(change.entityId(), kind, since)) {
                    enqueue(new PortalChange(change.structureId(), PortalItemType.ENTRY, entryId));
                }
            }
        }
    }

    /** Famille invitée ou portail réactivé : présences et transmissions encore dans la durée de conservation repartent. */
    void enqueueRecent(UUID structureId, UUID childId) {
        var since = LocalDate.now(clock.withZone(NOUMEA)).minus(PHOTO_RETENTION);
        for (var entryId : entries.idsForChildSince(childId, since)) {
            enqueue(new PortalChange(structureId, PortalItemType.ENTRY, entryId));
        }
        for (var attendance : attendances.findByChildIdAndDayGreaterThanEqual(childId, since)) {
            enqueue(new PortalChange(structureId, PortalItemType.ATTENDANCE, attendance.getId()));
        }
    }

    /** Famille invitée ou portail réactivé : les actualités encore visibles repartent (la liste des familles change). */
    void enqueueVisibleNews(UUID structureId) {
        var today = LocalDate.now(clock.withZone(NOUMEA));
        news.findByStructureIdOrderByPublishedAtDesc(structureId).stream().filter(n -> n.isVisibleOn(today))
                .forEach(n -> enqueue(new PortalChange(structureId, PortalItemType.NEWS, n.getId())));
    }

    /** Ajout sans contrôle de l'état (activation, ordre d'effacement). */
    void enqueue(PortalChange change) {
        if (!outbox.existsByStructureIdAndTypeAndEntityIdAndSentAtIsNull(change.structureId(), change.type(),
                change.entityId())) {
            outbox.save(new PortalOutboxItem(change.structureId(), change.type(), change.entityId(), clock.instant()));
        }
    }
}
