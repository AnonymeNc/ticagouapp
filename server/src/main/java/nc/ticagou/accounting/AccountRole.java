package nc.ticagou.accounting;

/**
 * Rôle d'un compte dans les écritures exportées, et compte proposé par défaut (plan comptable général, à faire
 * valider par l'expert-comptable de la crèche avant le premier export).
 */
public enum AccountRole {
    PRODUITS_GARDE("Prestations de garde", "706100"),
    PRODUITS_INSCRIPTION("Frais d’inscription", "706200"),
    PRODUITS_SUPPLEMENTS("Heures complémentaires et dépassements", "706300"),
    TGC_COLLECTEE("TGC collectée", "445710"),
    CLIENTS_FAMILLES("Clients — familles", "411100"),
    CLIENTS_FINANCEURS("Clients — financeurs et aides", "411200"),
    BANQUE("Banque", "512000"),
    CAISSE("Caisse (espèces)", "531000"),
    CHEQUES("Chèques à encaisser", "511200"),
    CARTE("Cartes bancaires à encaisser", "511500"),
    AVOIRS("Rabais et avoirs accordés", "709000"),
    ARRONDIS("Écarts d’arrondi", "758000");

    private final String label;
    private final String defaultAccount;

    AccountRole(String label, String defaultAccount) {
        this.label = label;
        this.defaultAccount = defaultAccount;
    }

    public String label() {
        return label;
    }

    public String defaultAccount() {
        return defaultAccount;
    }
}
