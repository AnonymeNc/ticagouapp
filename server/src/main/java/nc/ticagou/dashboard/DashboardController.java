package nc.ticagou.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.dashboard.DashboardDtos.Kpis;
import nc.ticagou.dashboard.DashboardDtos.Today;

/** Tableau de bord : la journée (direction, éducatrices) et les indicateurs du mois (direction, comptabilité). */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Tableau de bord", description = "Absents, départs et arrivées du jour ; indicateurs du mois")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE')")
    @Operation(summary = "Absents signalés, départs prévus avant la fermeture, arrivées du personnel")
    public Today today() {
        return dashboard.today();
    }

    @GetMapping("/kpis")
    @PreAuthorize("hasAnyRole('DIRECTION', 'COMPTABILITE')")
    @Operation(summary = "Remplissage, chiffre d'affaires facturé, impayés, liste d'attente")
    public Kpis kpis() {
        return dashboard.kpis();
    }
}
