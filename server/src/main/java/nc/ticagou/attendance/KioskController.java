package nc.ticagou.attendance;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.attendance.AttendanceDtos.BatchResult;
import nc.ticagou.attendance.AttendanceDtos.KioskToday;
import nc.ticagou.attendance.AttendanceDtos.OperationBatch;
import nc.ticagou.attendance.AttendanceDtos.OperationResult;
import nc.ticagou.attendance.AttendanceDtos.StaffPunchRequest;
import nc.ticagou.attendance.AttendanceDtos.StaffPunchResult;
import nc.ticagou.auth.CurrentDevice;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.device.DeviceService;
import nc.ticagou.journal.JournalPhotos;
import nc.ticagou.journal.JournalDtos.KioskJournal;
import nc.ticagou.journal.JournalService;

/** Tablette d'accueil : instantané du jour, file d'opérations, pointage du personnel par code. */
@RestController
@RequestMapping("/api/v1/kiosk")
@Tag(name = "Kiosque", description = "Pointage sur la tablette d'accueil, en ligne et hors connexion")
public class KioskController {

    private final AttendanceQueries queries;
    private final KioskService kiosk;
    private final DeviceService devices;
    private final JournalService journal;

    public KioskController(AttendanceQueries queries, KioskService kiosk, DeviceService devices,
                           JournalService journal) {
        this.queries = queries;
        this.kiosk = kiosk;
        this.devices = devices;
        this.journal = journal;
    }

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('APPAREIL', 'DIRECTION', 'EDUCATRICE', 'CUISINE')")
    @Operation(summary = "Enfants, personnel et pointages du jour, mis en cache par la tablette")
    public KioskToday today() {
        var structureId = CurrentDevice.find().isPresent() ? devices.requireCurrent().getStructureId()
                : CurrentUser.get().structureId();
        return queries.kioskToday(structureId);
    }

    @PostMapping("/operations")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Appliquer, dans l'ordre et une seule fois, les opérations de la file de la tablette")
    public BatchResult operations(@Valid @RequestBody OperationBatch batch) {
        return kiosk.process(batch.operations());
    }

    @PostMapping("/staff-punch")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Pointage du personnel par son seul code : arrivée ou départ")
    public StaffPunchResult staffPunch(@Valid @RequestBody StaffPunchRequest request) {
        return kiosk.staffPunch(request);
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAnyRole('APPAREIL', 'DIRECTION', 'EDUCATRICE')")
    @Operation(summary = "Transmissions du jour, autorisations photo et absences signalées par les parents")
    public KioskJournal journal() {
        var structureId = CurrentDevice.find().isPresent() ? devices.requireCurrent().getStructureId()
                : CurrentUser.get().structureId();
        return journal.kioskDay(structureId);
    }

    @PostMapping(path = "/journal/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Photo prise sur la tablette, avec son opération TRANSMISSION (en ligne seulement)")
    public OperationResult photo(@Valid @RequestPart("operation") nc.ticagou.attendance.AttendanceDtos.Operation operation,
                                 @RequestPart("photo") MultipartFile photo) throws IOException {
        return kiosk.processPhoto(operation, photo.getBytes());
    }

    @GetMapping("/journal/{id}/photo")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Vignette d'une photo du jour")
    public ResponseEntity<byte[]> entryPhoto(@PathVariable UUID id) {
        return JournalPhotos.response(journal.photo(devices.requireCurrent().getStructureId(), id));
    }
}
