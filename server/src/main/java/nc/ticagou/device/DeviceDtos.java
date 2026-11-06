package nc.ticagou.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record DeviceDto(UUID id, String name, Device.State state, Instant createdAt, Instant pairedAt,
                            Instant lastSeenAt, String userAgent, Instant pairingExpiresAt) {

        static DeviceDto from(Device device) {
            return new DeviceDto(device.getId(), device.getName(), device.state(), device.getCreatedAt(),
                    device.getPairedAt(), device.getLastSeenAt(), device.getUserAgent(), device.getPairingExpiresAt());
        }
    }

    public record DeviceRequest(@NotBlank @Size(max = 60) String name) {
    }

    /** Code à saisir sur la tablette, affiché une seule fois sur le PC du bureau. */
    public record PairingCode(DeviceDto device, String code, Instant expiresAt) {
    }

    public record PairRequest(
            @NotNull @Pattern(regexp = "^\\d{6}$", message = "Le code d’appairage comporte 6 chiffres") String code) {
    }

    /** Secret de la tablette, renvoyé une seule fois à l'appairage. */
    public record PairResponse(UUID deviceId, String secret, String name, String structureName) {
    }

    public record SessionRequest(@NotNull UUID deviceId, @NotBlank @Size(max = 100) String secret) {
    }

    public record DeviceSession(String accessToken, long expiresIn, UUID deviceId, String name, UUID structureId,
                                String structureName) {
    }
}
