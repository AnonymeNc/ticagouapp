package nc.ticagou.auth;

/** Rôles des utilisateurs ; autorité Spring Security {@code ROLE_<nom>}. */
public enum Role {
    DIRECTION,
    EDUCATRICE,
    CUISINE,
    COMPTABILITE;

    public String authority() {
        return "ROLE_" + name();
    }
}
