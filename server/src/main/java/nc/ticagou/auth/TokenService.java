package nc.ticagou.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.config.TiCagouProperties;

/** Émission des jetons d'accès (JWT) et rotation des jetons de rafraîchissement. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final TiCagouProperties.Security settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, RefreshTokenRepository refreshTokens, TiCagouProperties properties,
                        Clock clock) {
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.settings = properties.security();
        this.clock = clock;
    }

    /** Nouvelle session (nouvelle famille de jetons de rafraîchissement). */
    @Transactional
    public TokenPair issue(AppUser user, AuthMethod method) {
        return issue(user, method, UUID.randomUUID(), defaultRefreshTtl(method));
    }

    /** Nouvelle session dont le jeton de rafraîchissement vit {@code refreshTtl} (application Direction, ADR 0024). */
    @Transactional
    public TokenPair issue(AppUser user, AuthMethod method, Duration refreshTtl) {
        return issue(user, method, UUID.randomUUID(), refreshTtl);
    }

    /**
     * Consomme un jeton de rafraîchissement. Vide si inconnu, expiré ou révoqué ;
     * {@link Rotation#reused()} si le jeton avait déjà servi (la famille est alors révoquée).
     */
    @Transactional
    public Rotation consume(String rawToken) {
        var now = clock.instant();
        var token = refreshTokens.findByTokenHash(hash(rawToken)).orElse(null);
        if (token == null) {
            return Rotation.invalid();
        }
        if (token.getUsedAt() != null && token.getRevokedAt() == null) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            return new Rotation(Optional.empty(), true, token.getUserId(), token.getStructureId());
        }
        if (!token.isUsable(now)) {
            return Rotation.invalid();
        }
        token.markUsed(now);
        return new Rotation(Optional.of(token), false, token.getUserId(), token.getStructureId());
    }

    /** Suite d'une session après consommation du jeton précédent. */
    @Transactional
    public TokenPair rotate(AppUser user, RefreshToken previous) {
        return rotate(user, previous, defaultRefreshTtl(previous.getAuthMethod()));
    }

    @Transactional
    public TokenPair rotate(AppUser user, RefreshToken previous, Duration refreshTtl) {
        return issue(user, previous.getAuthMethod(), previous.getFamilyId(), refreshTtl);
    }

    private Duration defaultRefreshTtl(AuthMethod method) {
        return method == AuthMethod.PIN ? settings.pinRefreshTokenTtl() : settings.refreshTokenTtl();
    }

    /** Révoque la session du jeton ; renvoie le jeton trouvé (pour journaliser la déconnexion). */
    @Transactional
    public Optional<RefreshToken> revokeSession(String rawToken) {
        var token = refreshTokens.findByTokenHash(hash(rawToken));
        token.ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), clock.instant()));
        return token;
    }

    @Transactional
    public void revokeAll(UUID userId) {
        refreshTokens.revokeAllForUser(userId, clock.instant());
    }

    private TokenPair issue(AppUser user, AuthMethod method, UUID familyId, Duration refreshTtl) {
        var now = clock.instant();
        var accessExpiry = now.plus(settings.accessTokenTtl());
        var claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(accessExpiry)
                .claim(CurrentUser.CLAIM_STRUCTURE, user.getStructureId().toString())
                .claim(CurrentUser.CLAIM_ROLE, user.getRole().name())
                .claim(CurrentUser.CLAIM_NAME, user.displayName())
                .claim(CurrentUser.CLAIM_METHOD, method.name())
                .claim(CurrentUser.CLAIM_MFA_PENDING, user.needsMfaEnrollment(settings.mfaRequired(), method))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        var raw = new byte[32];
        random.nextBytes(raw);
        var refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refreshTokens.save(new RefreshToken(user, hash(refreshToken), familyId, method, now, now.plus(refreshTtl)));

        return new TokenPair(accessToken, settings.accessTokenTtl().toSeconds(), refreshToken, familyId);
    }

    /** Jeton d'accès d'une tablette appairée (ADR 0005) : rôle APPAREIL, pas de jeton de rafraîchissement. */
    public DeviceToken issueDevice(UUID deviceId, UUID structureId, String deviceName) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(deviceId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(settings.accessTokenTtl()))
                .claim(CurrentUser.CLAIM_STRUCTURE, structureId.toString())
                .claim(CurrentUser.CLAIM_ROLE, CurrentDevice.ROLE)
                .claim(CurrentUser.CLAIM_NAME, deviceName)
                .claim(CurrentDevice.CLAIM_TYPE, CurrentDevice.TYPE_DEVICE)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new DeviceToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
                settings.accessTokenTtl().toSeconds());
    }

    /** Vrai si la session (famille de jetons) appartient à cet utilisateur : preuve d'une connexion PIN en ligne. */
    @Transactional(readOnly = true)
    public boolean isSessionOf(UUID sessionId, UUID userId) {
        return refreshTokens.existsByFamilyIdAndUserId(sessionId, userId);
    }

    static String hash(String rawToken) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code sessionId} : famille des jetons de rafraîchissement, stable pendant toute la session. */
    public record TokenPair(String accessToken, long expiresIn, String refreshToken, UUID sessionId) {
    }

    public record DeviceToken(String accessToken, long expiresIn) {
    }

    public record Rotation(Optional<RefreshToken> token, boolean reused, UUID userId, UUID structureId) {

        static Rotation invalid() {
            return new Rotation(Optional.empty(), false, null, null);
        }
    }
}
