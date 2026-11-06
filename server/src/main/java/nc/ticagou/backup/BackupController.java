package nc.ticagou.backup;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.backup.BackupDtos.Overview;
import nc.ticagou.backup.BackupDtos.RunDto;

/** Paramètres › Sauvegardes : direction seulement (maquette « Droits par rôle », ligne Sauvegardes). */
@RestController
@RequestMapping("/api/v1/backups")
@Tag(name = "Sauvegardes", description = "Sauvegardes chiffrées, test de restauration")
@PreAuthorize("hasRole('DIRECTION')")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping
    @Operation(summary = "Réglages, dernière sauvegarde, prochaine, historique")
    public Overview overview() {
        return backups.overview();
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Sauvegarder maintenant (en arrière-plan)")
    public RunDto run() {
        return backups.start();
    }

    @PostMapping("/restore-test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Tester la restauration de la dernière sauvegarde dans une base temporaire")
    public RunDto restoreTest() {
        return backups.startRestoreTest();
    }
}
