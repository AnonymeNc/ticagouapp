package nc.ticagou.device;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.device.DeviceDtos.DeviceDto;
import nc.ticagou.device.DeviceDtos.DeviceRequest;
import nc.ticagou.device.DeviceDtos.DeviceSession;
import nc.ticagou.device.DeviceDtos.PairRequest;
import nc.ticagou.device.DeviceDtos.PairResponse;
import nc.ticagou.device.DeviceDtos.PairingCode;
import nc.ticagou.device.DeviceDtos.SessionRequest;

/** Tablettes appairées : gestion par la direction, appairage et jeton côté tablette (publics). */
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Appareils", description = "Appairage des tablettes d'accueil")
public class DeviceController {

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @GetMapping
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Tablettes de la structure")
    public List<DeviceDto> list() {
        return devices.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Appairer une tablette : renvoie le code à saisir sur la tablette (10 minutes)")
    public PairingCode create(@Valid @RequestBody DeviceRequest request) {
        return devices.create(request.name());
    }

    @PostMapping("/{id}/pairing-code")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Nouveau code d'appairage (tablette réinitialisée ou code expiré)")
    public PairingCode renewCode(@PathVariable UUID id) {
        return devices.renewCode(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Renommer une tablette")
    public DeviceDto rename(@PathVariable UUID id, @Valid @RequestBody DeviceRequest request) {
        return devices.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Retirer une tablette : son secret cesse de fonctionner")
    public void revoke(@PathVariable UUID id) {
        devices.revoke(id);
    }

    @PostMapping("/pair")
    @Operation(summary = "Tablette : échanger le code d'appairage contre son secret")
    public PairResponse pair(@Valid @RequestBody PairRequest request,
                             @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return devices.pair(request.code(), userAgent);
    }

    @PostMapping("/session")
    @Operation(summary = "Tablette : obtenir un jeton d'appareil à partir de son secret")
    public DeviceSession session(@Valid @RequestBody SessionRequest request,
                                 @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return devices.session(request.deviceId(), request.secret(), userAgent);
    }
}
