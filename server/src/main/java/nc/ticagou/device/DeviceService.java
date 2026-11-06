package nc.ticagou.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.CurrentDevice;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.auth.TokenService;
import nc.ticagou.common.ApiException;
import nc.ticagou.license.LicenseService;
import nc.ticagou.common.ClientIp;
import nc.ticagou.device.DeviceDtos.DeviceDto;
import nc.ticagou.device.DeviceDtos.DeviceSession;
import nc.ticagou.device.DeviceDtos.PairResponse;
import nc.ticagou.device.DeviceDtos.PairingCode;
import nc.ticagou.structure.StructureRepository;

/**
 * Appairage des tablettes (Paramètres › Appareils, ADR 0005). La direction crée l'appareil et obtient un code
 * à 6 chiffres valable 10 minutes ; la tablette l'échange contre un secret, puis le secret contre un jeton
 * d'appareil de courte durée. Retirer l'appareil invalide son secret immédiatement.
 */
@Service
public class DeviceService {

    static final Duration PAIRING_VALIDITY = Duration.ofMinutes(10);

    private final DeviceRepository devices;
    private final StructureRepository structures;
    private final TokenService tokens;
    private final PairingThrottle throttle;
    private final AuditService audit;
    private final LicenseService licenses;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(DeviceRepository devices, StructureRepository structures, TokenService tokens,
                         PairingThrottle throttle, AuditService audit, LicenseService licenses, Clock clock) {
        this.devices = devices;
        this.structures = structures;
        this.tokens = tokens;
        this.throttle = throttle;
        this.audit = audit;
        this.licenses = licenses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> list() {
        return devices.findByStructureIdOrderByCreatedAtAsc(CurrentUser.get().structureId()).stream()
                .map(DeviceDto::from).toList();
    }

    @Transactional
    public PairingCode create(String name) {
        licenses.requireTabletSlot();
        var user = CurrentUser.get();
        var device = devices.save(new Device(user.structureId(), name.trim(), user.displayName(), clock.instant()));
        var code = newCode(device);
        audit.record(AuditAction.DEVICE_PAIRING_STARTED, "device", device.getId(), Map.of("name", device.getName()));
        return code;
    }

    /** Nouveau code pour une tablette réinitialisée ou un code expiré ; l'ancien secret cesse de fonctionner à l'appairage. */
    @Transactional
    public PairingCode renewCode(UUID id) {
        var device = load(id);
        if (device.state() == Device.State.RETIREE) {
            throw ApiException.rule("device_revoked", "Cette tablette a été retirée. Ajoutez-la à nouveau.");
        }
        var code = newCode(device);
        audit.record(AuditAction.DEVICE_PAIRING_STARTED, "device", id, Map.of("name", device.getName()));
        return code;
    }

    @Transactional
    public DeviceDto rename(UUID id, String name) {
        var device = load(id);
        device.rename(name.trim());
        audit.record(AuditAction.DEVICE_RENAMED, "device", id, Map.of("name", device.getName()));
        return DeviceDto.from(device);
    }

    @Transactional
    public void revoke(UUID id) {
        var device = load(id);
        device.revoke(clock.instant());
        audit.record(AuditAction.DEVICE_REVOKED, "device", id, Map.of("name", device.getName()));
    }

    /** Échange du code saisi sur la tablette contre son secret. */
    @Transactional
    public PairResponse pair(String code, String userAgent) {
        var address = String.valueOf(ClientIp.current());
        if (throttle.isBlocked(address)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "pairing_locked",
                    "Trop de codes incorrects. Patientez quelques minutes puis demandez un nouveau code à la direction.");
        }
        var now = clock.instant();
        var device = devices.findByPairingCodeHash(hash(code)).stream().filter(d -> d.canPair(now)).findFirst()
                .orElse(null);
        if (device == null) {
            throttle.failed(address);
            throw ApiException.rule("pairing_code_invalid",
                    "Ce code est incorrect ou a expiré. Demandez un nouveau code sur le PC du bureau.");
        }
        throttle.succeeded(address);
        licenses.requireTabletSlot();
        var secret = randomToken();
        device.pair(hash(secret), now, truncate(userAgent));
        var structure = structures.findById(device.getStructureId()).orElseThrow();
        audit.record(new Actor(device.getStructureId(), null, device.getName()), AuditAction.DEVICE_PAIRED, "device",
                device.getId(), Map.of("name", device.getName()));
        return new PairResponse(device.getId(), secret, device.getName(), structure.getName());
    }

    /** Jeton d'accès de la tablette ; 401 si elle a été retirée ou si son secret a été remplacé. */
    @Transactional
    public DeviceSession session(UUID deviceId, String secret, String userAgent) {
        var device = devices.findById(deviceId)
                .filter(d -> d.isActive() && MessageDigest.isEqual(
                        d.getTokenHash().getBytes(StandardCharsets.UTF_8), hash(secret).getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(DeviceService::unknownDevice);
        device.seen(clock.instant(), truncate(userAgent));
        var structure = structures.findById(device.getStructureId()).orElseThrow();
        var token = tokens.issueDevice(device.getId(), device.getStructureId(), device.getName());
        return new DeviceSession(token.accessToken(), token.expiresIn(), device.getId(), device.getName(),
                structure.getId(), structure.getName());
    }

    /**
     * Tablette de la requête, relue en base : un jeton encore valide d'une tablette retirée est refusé.
     */
    @Transactional(readOnly = true)
    public Device requireCurrent() {
        var current = CurrentDevice.find().orElseThrow(() -> ApiException.forbidden("device_required",
                "Cette action se fait depuis une tablette appairée."));
        return devices.findById(current.deviceId()).filter(Device::isActive)
                .orElseThrow(DeviceService::unknownDevice);
    }

    private PairingCode newCode(Device device) {
        String code;
        do {
            code = "%06d".formatted(random.nextInt(1_000_000));
        } while (isPending(code));
        var expiresAt = clock.instant().plus(PAIRING_VALIDITY);
        device.startPairing(hash(code), expiresAt);
        devices.saveAndFlush(device);
        return new PairingCode(DeviceDto.from(device), code, expiresAt);
    }

    private boolean isPending(String code) {
        var now = clock.instant();
        return devices.findByPairingCodeHash(hash(code)).stream().anyMatch(d -> d.canPair(now));
    }

    private Device load(UUID id) {
        return devices.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Tablette introuvable."));
    }

    private String randomToken() {
        var raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String truncate(String userAgent) {
        return userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 200));
    }

    static ApiException unknownDevice() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "device_unknown",
                "Cette tablette n’est plus appairée. Demandez un code d’appairage à la direction.");
    }

    static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
