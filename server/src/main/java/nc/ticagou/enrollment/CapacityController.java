package nc.ticagou.enrollment;

import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.enrollment.CapacityDtos.CapacityDto;
import nc.ticagou.enrollment.CapacityDtos.TestRequest;
import nc.ticagou.enrollment.CapacityDtos.TestResult;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

@RestController
@RequestMapping("/api/v1/capacity")
@Tag(name = "Capacité", description = "Places libres par section, par jour et par mois sur 6 mois")
@PreAuthorize(EnrollmentController.READERS)
@RequiresModule(LicenseModule.INSCRIPTIONS)
public class CapacityController {

    private final CapacityService capacity;

    public CapacityController(CapacityService capacity) {
        this.capacity = capacity;
    }

    @GetMapping
    @Operation(summary = "Grille des places libres, contrats signés, sorties prévues et places proposées comptés")
    public CapacityDto grid(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth from) {
        return capacity.grid(from != null ? from : capacity.currentMonth());
    }

    @PostMapping("/test")
    @Operation(summary = "Tester une demande : premier mois où chaque jour demandé reste libre")
    public TestResult test(@Valid @RequestBody TestRequest request) {
        var snapshot = capacity.snapshot(capacity.currentMonth(), request.excludeRequestId());
        return TestResult.from(snapshot.test(request.birthDate(), request.sectionId(), request.days(),
                request.desiredStart()));
    }
}
