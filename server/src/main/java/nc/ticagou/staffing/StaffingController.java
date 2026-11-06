package nc.ticagou.staffing;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.auth.CurrentDevice;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.device.DeviceService;
import nc.ticagou.staffing.StaffingDtos.StaffingDto;

/** Taux d'encadrement en temps réel : tableau de bord, tablettes (utilisateur connecté ou appareil). */
@RestController
@RequestMapping("/api/v1/staffing")
@Tag(name = "Encadrement", description = "Taux d'encadrement en temps réel")
@PreAuthorize("isAuthenticated()")
public class StaffingController {

    private final StaffingService staffing;
    private final StaffingBroadcaster broadcaster;
    private final DeviceService devices;

    public StaffingController(StaffingService staffing, StaffingBroadcaster broadcaster, DeviceService devices) {
        this.staffing = staffing;
        this.broadcaster = broadcaster;
        this.devices = devices;
    }

    @GetMapping
    @Operation(summary = "Encadrement à l'instant présent, par section et global")
    public StaffingDto snapshot() {
        return staffing.snapshot(structureId());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Flux SSE : événements ratio, attendance, alert et ping")
    public SseEmitter stream() {
        return broadcaster.subscribe(structureId());
    }

    private UUID structureId() {
        return CurrentDevice.find().isPresent() ? devices.requireCurrent().getStructureId()
                : CurrentUser.get().structureId();
    }
}
