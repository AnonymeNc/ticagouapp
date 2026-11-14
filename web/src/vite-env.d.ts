/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Cible du build (ADR 0023) : absent dans le navigateur, « direction » ou « pointage » pour les applications. */
  readonly VITE_TICAGOU_CIBLE?: string;
  /** Version de l'application mobile, celle du serveur (ADR 0027). */
  readonly VITE_TICAGOU_APP_VERSION?: string;
  /** « 1 » : le navigateur se comporte comme l'application (essais sans plugin natif). */
  readonly VITE_TICAGOU_APP_SIMULEE?: string;
  /** Annuaire des codes de crèche (ADR 0025) ; par défaut https://annuaire.ticagou.nc. */
  readonly VITE_TICAGOU_ANNUAIRE?: string;
}
