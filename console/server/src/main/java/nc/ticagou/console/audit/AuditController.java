package nc.ticagou.console.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Paramètres › Journal. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditService.Event> latest(@RequestParam(defaultValue = "200") int limit) {
        return audit.latest(limit);
    }
}
