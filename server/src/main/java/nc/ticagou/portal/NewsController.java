package nc.ticagou.portal;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.portal.PortalDtos.BroadcastRequest;
import nc.ticagou.portal.PortalDtos.BroadcastResult;
import nc.ticagou.portal.PortalDtos.NewsDto;

/** Actualités de la crèche et messages à plusieurs familles, publiés sur le portail parents. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Portail parents", description = "Actualités de la crèche et messages à plusieurs familles")
public class NewsController {

    private final NewsService news;

    public NewsController(NewsService news) {
        this.news = news;
    }

    @GetMapping("/portal/news")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Actualités publiées, expirées ou retirées")
    public List<NewsDto> list() {
        return news.list();
    }

    @PostMapping(path = "/portal/news", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(PortalController.DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publier une actualité (toute la crèche ou une section), pièce jointe PDF ou image de 5 Mo au plus")
    public NewsDto publish(@RequestParam String title, @RequestParam String body,
                           @RequestParam(required = false) UUID sectionId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
                           @RequestParam(required = false) MultipartFile file) throws IOException {
        var attachment = file == null || file.isEmpty() ? null
                : new NewsService.Attachment(file.getBytes(), file.getOriginalFilename());
        return news.publish(title, body, sectionId, expiresOn, attachment);
    }

    @PostMapping("/portal/news/{id}/withdraw")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Retirer une actualité : elle est effacée du portail")
    public NewsDto withdraw(@PathVariable UUID id) {
        return news.withdraw(id);
    }

    @GetMapping("/portal/news/{id}/file")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Pièce jointe d'une actualité")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        var file = news.file(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.fileName(),
                        java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(file.content());
    }

    @PostMapping("/messages/broadcast")
    @PreAuthorize(PortalController.DIRECTION)
    @Operation(summary = "Écrire à toutes les familles invitées, ou à celles d'une section")
    public BroadcastResult broadcast(@Valid @RequestBody BroadcastRequest request) {
        return news.broadcast(request.sectionId(), request.body());
    }
}
