# ADR 0009 — HTTPS sur le réseau de la crèche, épinglage et découverte mDNS

- **Statut** : remplacée par l'[ADR 0013](0013-web-heberge-instance-par-creche.md)
- **Date** : 02/11/2026

## Contexte

L'ADR 0005 avait reporté HTTPS à l'installeur. En HTTP sur le réseau local :
- les tablettes n'ont ni service worker (rechargement hors connexion, installation en PWA) ni WebCrypto ;
- les échanges contiennent des données de santé d'enfants.

Un certificat public (Let's Encrypt) est impossible : pas de nom de domaine, pas d'accès entrant, souvent pas d'internet.
Un certificat auto-signé déclenche un avertissement, et Chrome refuse alors le service worker.

## Décision

**Autorité locale**
- L'installation crée au premier démarrage TLS une autorité propre à la structure : ECDSA P-256, valable 10 ans,
  avec les fichiers `ca.pem` et `ca-key.pem` dans `C:\ProgramData\TiCagou\tls`.
- Ses *name constraints* la limitent aux réseaux privés (10/8, 172.16/12, 192.168/16, 127/8) et aux noms `.local` et
  `localhost`. Installée comme racine sur une tablette, elle ne peut pas certifier un site internet.
- Elle signe le certificat du serveur (397 jours). Ses noms : `ticagou.local`, `localhost` et chaque IPv4 privée du serveur.
- Le serveur revérifie toutes les 10 minutes. Le certificat est refait si une adresse change (DHCP) ou à 30 jours de
  l'échéance, puis rechargé à chaud (bundle SSL PEM de Spring Boot, `reload-on-update`), sans redémarrage.
- Génération avec **BouncyCastle `bcpkix`** (déjà présent pour Argon2 via `bcprov`, même version 1.86, ~1 Mo de plus).

**Ports**
- HTTPS sur 8443.
- Le port 8080 en HTTP ne sert que l'amorçage :
  - la page `/appairer` et les fichiers du front ;
  - `/ticagou-ca.crt` ;
  - `/api/v1/system/tls` (empreinte SHA-256) ;
  - `/api/v1/health`.
- Tout le reste est redirigé (308) vers HTTPS.
- `ticagou.tls.enabled` est faux en développement et dans les tests, vrai à l'installation.

**Tablettes**
1. Le QR code d'appairage ouvre `http://<ip>:8080/appairer?pair=CODE&fp=<début de l'empreinte>`.
2. La page affiche l'empreinte complète et la compare au début porté par le QR. Si elles diffèrent, elle bloque.
3. Elle fait télécharger et installer la CA, avec un pas-à-pas Android et iPad.
4. Elle vérifie la connexion chiffrée, puis ouvre `https://<ip>:8443/kiosk?pair=CODE`, où la tablette s'appaire seule.

**PC du bureau (client Tauri)**
- La commande Rust `trust_server` lit l'autorité en HTTP. La direction compare l'empreinte à celle affichée sur le
  serveur ou trouvée par mDNS.
- Puis Rust épingle l'empreinte et importe la CA dans les racines de l'utilisateur Windows (confirmation de Windows),
  pour que la WebView l'accepte.
- À chaque lancement, Rust se connecte au serveur avec l'autorité épinglée comme **seule** racine (rustls). Si la
  chaîne ne correspond pas, un écran bloquant s'affiche et rien n'est envoyé. L'updater utilise le même client.

**Découverte**
- Le serveur annonce `_ticagou._tcp` et répond pour `ticagou.local`, avec **JmDNS** (Apache 2.0, ~200 Ko).
- Le TXT porte la version, les ports et l'empreinte. Le client bureau parcourt le réseau avec la crate `mdns-sd`.
- La saisie d'une adresse reste possible.

## Alternatives écartées

- **Auto-signé + avertissement accepté** : pas de service worker sur Chrome Android, et un réflexe d'« accepter le
  risque » dangereux à donner au personnel.
- **Certificat public sur un sous-domaine Ti'Cagou (DNS-01)** : il faut internet, un service central et une
  résolution DNS publique vers une IP privée (bloquée par certains routeurs).
- **Clé de l'autorité chiffrée en base** : la base n'est pas encore accessible quand le serveur web démarre (première
  installation, migrations). Le dossier TLS est protégé par les droits Windows posés par l'installeur.

## Conséquences

- Les tablettes appairées en HTTP doivent être réappairées une fois : l'origine change, donc le stockage local aussi.
- **iPad** : il faut activer la confiance dans Réglages › Général › Informations › Réglages des certificats.
- **Android d'entreprise** : une politique peut interdire les CA utilisateur.
- L'épinglage porte sur les connexions natives du client (contrôle au lancement, updater). Les requêtes de la WebView
  s'appuient sur la CA importée, dont la portée est bornée par les *name constraints*.
- Les PIN de la file hors connexion peuvent désormais être chiffrés avec WebCrypto (évolution de l'ADR 0005).
