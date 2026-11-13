# Étude : Ti'Cagou en application mobile

- **Statut** : retenue le 14/11/2026, option B (Capacitor), avec l'application de la direction aussi sur ordinateur
- **Date** : 14/11/2026
- **Suite** : [plan d'implémentation](plan-application-mobile.md)
- **Dépôt** : `ticagouapp` repart de `ticagou-web` (commit `733bb1a`), comme `ticagou-web` était reparti de la V1
  ([ADR 0013](adr/0013-web-heberge-instance-par-creche.md)). `ticagou-web` reste tel quel, en remote `ticagou-web`.

## 1. Trois interfaces, trois situations

| Interface | Taille (nov. 2026) | État |
|---|---|---|
| Application parents (`parents-portal/web`) | ~2 100 lignes | PWA portrait, Web Push ([ADR 0018](adr/0018-notifications-push-portail-parents.md)) |
| Kiosque tablette (`web/src/features/kiosk`) | ~4 000 lignes | PWA paysage, file d'attente hors connexion chiffrée ([ADR 0005](adr/0005-tablettes-hors-connexion.md)) |
| Back-office direction / comptabilité (reste de `web/`) | ~17 000 lignes | Pensé pour le PC : barre latérale fixe, peu de points de rupture, maquettes bureau |

Le serveur Spring Boot change peu.

## 2. Options étudiées

| Option | Code réutilisé | Verdict |
|---|---|---|
| A. PWA seule + back-office responsive | 100 % | Pas de store ; notifications iPhone limitées ; pas de vrai mode kiosque |
| **B. Capacitor** (même React dans une coque iOS / Android) | ~95 % | **Retenue** |
| C. React Native / Expo | logique seule | Interface entière à réécrire (Tailwind, shadcn, Radix n'existent pas en natif) |
| D. Natif Swift + Kotlin | 0 % | Deux bases de code |

## 3. Ce que l'option B entraîne

- **Fin du « même origine »** : une coque Capacitor tourne sur `capacitor://localhost` / `https://localhost`.
  CORS à ouvrir pour ces origines ; le cookie de rafraîchissement `HttpOnly` + `SameSite=Strict` ne fonctionne plus :
  jeton de rafraîchissement dans le trousseau (Keychain / Keystore), `Authorization: Bearer`. Le navigateur garde le
  cookie. (L'ADR 0002 l'annonçait.)
- **Une instance par crèche, une seule application** : l'application doit trouver l'adresse de sa crèche. Le kiosque
  l'a déjà dans le QR d'appairage ([ADR 0008](adr/0008-qr-code-appairage.md)) ; la direction a besoin d'un choix de la
  crèche (code ou QR). Le portail parents est unique : pas de question.
- **Versions décalées** : une application installée peut être plus ancienne que l'instance. Discipline `/api/v1`,
  contrôle de version minimale, mise à jour du contenu web autorisée tant que l'objet de l'application ne change pas.
- **Notifications** : APNs (Apple) et FCM (Google) à côté de Web Push. Nouveaux sous-traitants à déclarer aux familles.
  Mêmes règles de contenu (aucune donnée de santé).
- **Données de santé sur un téléphone** : verrouillage biométrique, pas de cache persistant des données de santé,
  aperçu masqué dans le sélecteur d'applications, clé du kiosque dans le Keystore matériel. Journal des consultations
  inchangé.
- **Stores** : comptes développeur (Apple 99 $/an, Google 25 $), fiches de confidentialité (données de santé d'enfants),
  suppression du compte depuis l'application (Apple), compte de démonstration pour la relecture. Pas d'achat intégré :
  l'abonnement est vendu hors de l'application, les factures de crèche paient un service physique. Distribution privée
  possible pour la direction et le kiosque (Apple Business Manager, Managed Google Play).
- **Abonnement** ([ADR 0021](adr/0021-formule-unique.md)) : l'identifiant de poste passe dans le stockage sécurisé ;
  décider si un téléphone de direction compte comme un PC.
- **Outillage** : builds iOS sur Mac (ou CI macOS) ; tests sur appareil ; maquettes mobiles à créer ; Capacitor est une
  dépendance lourde, donc un ADR.

## 4. Fonctions conservées

Aucune fonction n'est perdue : le web reste servi à côté des applications. Sur téléphone, la consultation et les gestes
du quotidien (tableau de bord, encadrement, présences, départ bloqué, messages, repas, fiches) sont les priorités ; les
saisies longues (facturation, contrats, paramètres) restent accessibles mais adaptées ; l'import de données, l'assistant
d'installation et la restauration de sauvegardes sont recommandés sur ordinateur.
