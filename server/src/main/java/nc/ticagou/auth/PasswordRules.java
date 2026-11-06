package nc.ticagou.auth;

import nc.ticagou.common.ApiException;

/** Exigences minimales sur les mots de passe et les PIN. */
public final class PasswordRules {

    public static final int MIN_PASSWORD_LENGTH = 10;

    private PasswordRules() {
    }

    public static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.rule("weak_password",
                    "Le mot de passe doit contenir au moins %d caractères.".formatted(MIN_PASSWORD_LENGTH));
        }
        if (password.length() > 128) {
            throw ApiException.rule("weak_password", "Le mot de passe ne peut pas dépasser 128 caractères.");
        }
        if (password.chars().distinct().count() < 4) {
            throw ApiException.rule("weak_password", "Le mot de passe est trop simple.");
        }
    }

    public static void checkPin(String pin) {
        if (pin == null || !pin.matches(AuthDtos.PIN_PATTERN)) {
            throw ApiException.rule("invalid_pin", AuthDtos.PIN_MESSAGE + ".");
        }
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) {
            throw ApiException.rule("weak_pin", "Ce code PIN est trop facile à deviner (ex. 1111, 1234).");
        }
    }
}
