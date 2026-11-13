# ADR 0021 — Une seule formule, tout compris

- **Statut** : acceptée
- **Date** : 10/11/2026
- **Remplace** : [ADR 0015](0015-formules-et-modules.md) (formules et modules)
- **Complétée par** : [ADR 0028](0028-postes-mobiles-et-abonnement.md) (applications Direction comprises, téléphones hors du compte des PC)

## Contexte

L'ADR 0015 découpait l'offre en trois formules (Essentiel, Gestion, Complet) et six modules à la carte. Le logiciel
masquait menus, rubriques et rôles d'un module absent, et le serveur refusait ses écritures (`module_absent`).

Le concurrent de référence ([plan d'alignement](../plan-alignement-meeko.md)) vend une offre unique, tout compris.
Plusieurs formules compliquent la vente (« qu'est-ce qui n'est pas compris ? »), les démonstrations et le support, et
une crèche sans facturation ou sans portail perd l'essentiel de ce qui fait la valeur de Ti'Cagou.

## Décision

**Une seule formule : tout Ti'Cagou est compris** — enfants et familles, présences, encadrement, inscriptions et
contrats, facturation et aides, comptabilité, personnel, repas, portail parents, sauvegardes.

Ce qui différencie encore deux abonnements : le nombre de **places** (capacité agréée), de **PC** et de **tablettes**,
et l'échéance (ADR 0011, 0013). Le prix peut dépendre de la taille de la crèche ; le contenu, jamais.

Dans le logiciel :
- `LicenseModule`, `@RequiresModule` et `useModules` sont supprimés ; le contrôle des modifications ne porte plus que
  sur l'échéance de la licence (`LicenseGuard`, ex-`ModuleGuard`) ;
- `GET /api/v1/system/modules` disparaît ; menus, rubriques de Paramètres, onglets de la fiche enfant, cartes du
  tableau de bord et rôles sont toujours affichés (selon le rôle seulement) ;
- la colonne `subscription.modules` reste en base mais n'est plus lue : `creche.sh abonnement` et la console peuvent
  encore l'écrire sans effet, le temps que la console passe à l'offre unique ;
- les mots « module Repas », « module Personnel »… restent dans la documentation et le code pour nommer un **domaine
  fonctionnel**, plus une option payante.

## Conséquences

- Plus de démonstration par formule : `tester-en-local.ps1 -Formule` et `creche.sh demo --formule` disparaissent.
- La console de l'éditeur ne propose plus qu'une formule, sous son code historique `COMPLET` libellé « Ti'Cagou »
  (migration `V8__formule_unique.sql`) : nouveaux contrats et avenants sans formule à choisir ni module ; un barème
  n'exige plus que les prix de cette formule ; ordres d'abonnement sans offre ni modules, démonstrations sans formule ;
  parc et reporting sans répartition par formule. Les contrats signés avant (Essentiel, Gestion, modules) se facturent
  à leur prix jusqu'à leur avenant. Prix (migration `V9__bareme_formule_unique.sql`, depuis le 01/11/2026) :
  **15 000 F HT par mois** quelle que soit la taille, aligné sur Meeko, **support rapide compris** (plus de Support
  Premium) ; voir `docs/commercial/bareme-tarifaire.md`.
- Une crèche qui ne veut pas d'un domaine (portail parents, par exemple) ne l'active simplement pas : le portail reste
  désactivé tant que la direction ne l'ouvre pas.
