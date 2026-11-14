# Revue de sécurité — applications mobiles

- **Date** : 14/11/2026
- **Périmètre** : Ti'Cagou Direction (iOS, Android), Ti'Cagou Pointage (Android), Ti'Cagou Parents (iOS, Android), et ce
  que le serveur, le portail et le socle ont gagné pour elles (ADR 0023 à 0028).
- **Rappel** : données de santé d'enfants ; accès par rôle, journal des consultations, double authentification de la
  direction et de la comptabilité, stockage de la tablette chiffré (CLAUDE.md).

## 1. Ce qui reste sur le téléphone

| Donnée | Où | Protection |
|---|---|---|
| Jeton de rafraîchissement (Direction), jeton de session (Parents) | Keychain iOS (« cet appareil, déverrouillé ») / Keystore Android | jamais dans `localStorage` ni `Preferences` ; effacé à la déconnexion, à l'oubli de la crèche, à la suppression du compte |
| Jeton d'accès (15 min), jeton de média (1 h) | mémoire | perdus à la fermeture |
| Identifiant de poste | coffre, par crèche | haché côté serveur |
| Secret de la tablette, clé de la file chiffrée | Keystore (Pointage) | clé AES importée non extractible ; file et instantané du jour chiffrés AES-256-GCM |
| Liste des crèches (nom, commune, adresse) | Preferences | rien de sensible |
| Cache des requêtes (TanStack Query) | mémoire | pas de cache persistant des données de santé |

**Corrigé pendant la revue** : Android sauvegardait par défaut les données des applications dans le cloud de Google
(`allowBackup="true"`). Les trois applications ont désormais `allowBackup="false"`, `fullBackupContent="false"` et des
règles d'extraction qui excluent tout, sauvegarde comme transfert d'appareil à appareil (Android 12 et plus).

## 2. Écran et captures

- Direction et Pointage : aperçu masqué dans le sélecteur d'applications et captures bloquées sur Android
  (`@capacitor/privacy-screen`, activé au démarrage).
- Parents : non activé (pas de donnée de santé détaillée, et les parents veulent pouvoir capturer une photo de leur
  enfant). Verrouillage proposé.

## 3. Verrouillage et sessions

- Direction : verrouillage obligatoire d'une session rouverte depuis le coffre et après 2 minutes en arrière-plan ;
  sans verrou sur le téléphone, la session tombe dès le passage en arrière-plan. Session de 30 jours, rotation à chaque
  usage, réutilisation détectée (révocation de toute la famille de jetons).
- Actions irréversibles depuis le téléphone (validation des factures du mois, avoir, purge d’une famille) : Face ID ou
  code du téléphone redemandé (`confirmIrreversible`, maquette C3) — **corrigé pendant la revue**.
- Parents : verrouillage optionnel.

## 4. Téléphone perdu ou volé

- **Direction** : Paramètres › Postes › Retirer. Le poste ne peut plus renouveler sa session (15 minutes au plus) et
  ses notifications s'arrêtent (`PosteRevoked`). Si le téléphone portait l'application TOTP : réinitialisation de la
  double authentification (procédure ADR 0013).
- **Tablette** : Paramètres › Postes › Retirer la tablette (inchangé, ADR 0005).
- **Parents** : la crèche retire l'accès du parent (ses sessions et appareils tombent) ; le parent peut aussi supprimer
  son compte depuis un autre appareil. Une révocation des sessions par le parent lui-même reste à proposer (§ 7).

## 5. Réseau

- HTTPS seulement (ATS sur iOS ; trafic en clair interdit par défaut sur Android cible 36). L'adresse d'une crèche tapée
  à la main exige `https://` (sauf build de développement vers le poste local).
- CORS : origines Capacitor admises **sans cookie** (`allowCredentials=false`) sur l'instance et le portail. La sécurité
  repose sur le jeton porteur, pas sur l'origine.
- Annuaire : un fichier par code, pas de liste ; codes de 6 caractères tirés au hasard (31⁶ possibilités).
- Jeton de média (Parents) dans l'adresse des fichiers : lecture seule des fichiers du parent, une heure, signé HMAC,
  clé renouvelée à chaque démarrage du portail. Les journaux d'accès de Caddy ne sont pas activés par le socle ; s'ils
  l'étaient, filtrer le paramètre `jeton`.

## 6. Notifications

Aucune donnée de santé, aucun contenu de message, aucun nom de la personne venue chercher un enfant : prénom et initiale
seulement (ADR 0026). Les jetons refusés par Apple ou Google sont effacés. Apple et Google sont sous-traitants : voir
[politique de confidentialité](commercial/stores/politique-confidentialite.md).

## 7. Reste à faire (suivi)

| Sujet | Gravité | Proposition |
|---|---|---|
| Révocation de ses sessions par le parent (« Déconnecter mes autres appareils ») | faible | point d'accès du portail + bouton dans « Mon compte » |
| Essais sur appareils réels : Keychain/Keystore, Face ID, Lock Task, notifications | à vérifier | bêta TestFlight et test interne Google ([PUBLIER-LES-APPLICATIONS.md](PUBLIER-LES-APPLICATIONS.md)) |
| Épinglage de certificat | faible | non retenu : Let's Encrypt renouvelle les certificats ; l'épinglage casserait les applications |
