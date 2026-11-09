package nc.ticagou.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;
import nc.ticagou.portal.PortalDtos.AcceptBooking;
import nc.ticagou.portal.PortalDtos.BookingDto;
import nc.ticagou.portal.PortalDtos.BookingRules;
import nc.ticagou.portal.PortalDtos.RefuseBooking;

/** Demandes des parents (lot 2) : accueils occasionnels à accepter ou refuser, retards annoncés, règles datées. */
@RestController
@RequestMapping("/api/v1")
@RequiresModule(LicenseModule.PORTAIL_PARENTS)
@Tag(name = "Portail parents", description = "Demandes d'accueil occasionnel et retards annoncés par les parents")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping("/bookings")
    @PreAuthorize(PortalController.TEAM)
    @Operation(summary = "Demandes d'accueil à traiter et retards annoncés pour aujourd'hui")
    public List<BookingDto> open() {
        return bookings.open();
    }

    @PostMapping("/bookings/{id}/accept")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Accepter : l'enfant est attendu ce jour-là ; au-delà des places de la section, motif obligatoire")
    public BookingDto accept(@PathVariable UUID id, @Valid @RequestBody AcceptBooking request) {
        return bookings.accept(id, request.overrideReason());
    }

    @PostMapping("/bookings/{id}/refuse")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Refuser avec un motif transmis à la famille")
    public BookingDto refuse(@PathVariable UUID id, @Valid @RequestBody RefuseBooking request) {
        return bookings.refuse(id, request.reason());
    }

    @GetMapping("/booking-rules")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Délai minimum et horizon des demandes en vigueur")
    public BookingRules rules() {
        return bookings.rules();
    }

    @PutMapping("/booking-rules")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Nouvelles règles des demandes, à partir d'aujourd'hui")
    public BookingRules updateRules(@Valid @RequestBody BookingRules rules) {
        return bookings.updateRules(rules.minNoticeHours(), rules.maxDaysAhead());
    }
}
