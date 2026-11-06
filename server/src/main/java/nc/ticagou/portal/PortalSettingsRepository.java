package nc.ticagou.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalSettingsRepository extends JpaRepository<PortalSettings, UUID> {

    List<PortalSettings> findByStateNot(PortalSettings.State state);
}
