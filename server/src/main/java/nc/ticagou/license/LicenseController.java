package nc.ticagou.license;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.license.LicenseService.LicenseDto;

/** Paramètres › Tablettes et abonnement : direction. */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "Système", description = "Abonnement, version, PC du bureau")
@PreAuthorize("hasRole('DIRECTION')")
public class LicenseController {

    private final LicenseService licenses;
    private final DesktopClientService desktops;

    public LicenseController(LicenseService licenses, DesktopClientService desktops) {
        this.licenses = licenses;
        this.desktops = desktops;
    }

    @GetMapping("/license")
    @Operation(summary = "Licence, tablettes appairées, version installée")
    public LicenseDto license() {
        return licenses.current();
    }

    @GetMapping("/license/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "État de la licence (bandeau d'échéance, lecture seule), pour tous les postes et tablettes")
    public LicenseTerms.Status status() {
        return licenses.status();
    }

    @GetMapping("/desktops")
    @Operation(summary = "PC du bureau enregistrés (comptés par la licence)")
    public List<DesktopClientService.DesktopClient> desktops() {
        return desktops.list();
    }

    @PostMapping("/desktops/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un PC : il libère sa place et ne peut plus se connecter")
    public void revokeDesktop(@PathVariable UUID id) {
        desktops.revoke(id);
    }
}
