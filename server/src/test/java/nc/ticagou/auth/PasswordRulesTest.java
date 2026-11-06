package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import nc.ticagou.common.ApiException;

class PasswordRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"court", "aaaaaaaaaaaa", "ababababab"})
    void motsDePasseFaiblesRefuses(String password) {
        assertThatThrownBy(() -> PasswordRules.checkPassword(password)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Niaouli-2026!", "lagon bleu de Poé"})
    void motsDePasseCorrectsAcceptes(String password) {
        assertThatCode(() -> PasswordRules.checkPassword(password)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1111", "1234", "6789", "4321", "123", "12a4", "12345"})
    void pinFaciblesOuMalFormesRefuses(String pin) {
        assertThatThrownBy(() -> PasswordRules.checkPin(pin)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4831", "5208", "0907"})
    void pinCorrectsAcceptes(String pin) {
        assertThatCode(() -> PasswordRules.checkPin(pin)).doesNotThrowAnyException();
    }
}
