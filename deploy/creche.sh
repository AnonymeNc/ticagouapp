#!/bin/sh
# Exploitation des crèches hébergées (ADR 0013) : une instance, une base et un dossier par crèche.
# Voir docs/EXPLOITATION.md. Commandes : ./creche.sh aide
set -eu
# Dossier d'appel : un chemin relatif donné en argument (export, archive) part de là.
APPEL=$(pwd)
cd "$(dirname "$0")"
DEPLOY=$(pwd)

[ -f socle.env ] || { echo "socle.env manquant : lancez ./secrets.sh (docs/EXPLOITATION.md § 2.6)." >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. ./socle.env
set +a
: "${DOMAINE_BASE:?Renseignez DOMAINE_BASE dans socle.env}"
: "${TICAGOU_DONNEES:?Renseignez TICAGOU_DONNEES dans socle.env}"
CRECHES="$TICAGOU_DONNEES/creches"
SITES="$TICAGOU_DONNEES/caddy-sites"
# Exports des crèches, lus par la console (montés en lecture seule) ; gardés 7 jours.
EXPORTS="$TICAGOU_DONNEES/console-exports"

socle() { docker compose -f socle.yml --env-file socle.env "$@"; }
sql_admin() { socle exec -T db psql -v ON_ERROR_STOP=1 -qAt -U ticagou_admin "$@"; }
sql_creche() { sql_admin -d "$(base_de "$1")" -c "$2"; }
base_de() { echo "creche_$1" | tr '-' '_'; }
dossier_de() { echo "$CRECHES/$1"; }
instance() {
    slug=$1; shift
    SLUG=$slug DOSSIER=$(dossier_de "$slug") docker compose -p "creche-$slug" -f instance.yml --env-file "$(dossier_de "$slug")/creche.env" "$@"
}
valeur() { grep "^$2=" "$(dossier_de "$1")/creche.env" | cut -d= -f2-; }
secret() { openssl rand -base64 32 | tr -d '\n'; }
echappe() { printf %s "$1" | sed "s/'/''/g"; }
existe() { [ -f "$(dossier_de "$1")/creche.env" ] || { echo "Crèche inconnue : $1" >&2; exit 1; }; }
creches() { ls "$CRECHES" 2>/dev/null || true; }

# Clé de secours : même alphabet que le serveur (ni 0, ni O, ni 1, ni I).
cle_de_secours() {
    LC_ALL=C tr -dc 'A-HJ-NP-Z2-9' </dev/urandom | head -c 24 | sed 's/..../&-/g; s/-$//'
}

attendre() {
    slug=$1
    i=0
    while [ $i -lt 60 ]; do
        etat=$(docker inspect --format '{{.State.Health.Status}}' "creche-$slug" 2>/dev/null || echo absent)
        [ "$etat" = healthy ] && return 0
        i=$((i + 1))
        sleep 5
    done
    echo "L'instance $slug ne démarre pas (état : $etat). docker logs creche-$slug" >&2
    return 1
}

# Une base fermée aux autres rôles : une crèche ne se connecte pas à celle d'à côté.
fermer_base() {
    sql_admin -d postgres -c "REVOKE ALL ON DATABASE $1 FROM PUBLIC"
}

ecrire_site() {
    slug=$1 domaine=$2
    {
        echo "$domaine {"
        [ "${TLS_INTERNE:-}" = 1 ] && printf '\ttls internal\n'
        # X-Forwarded-Port : le serveur compare l'origine de la page à sa propre adresse, port compris.
        printf '\timport securite\n\treverse_proxy creche-%s:8080 {\n\t\theader_up X-Forwarded-Port {http.request.port}\n\t}\n}\n' "$slug"
    } >"$SITES/$slug.caddy"
    socle exec -T caddy caddy reload --config /etc/caddy/Caddyfile
}

# Rôle et base d'un service du socle (portail, console), créés une seule fois.
base_de_service() {
    sql_admin -d postgres -c "SELECT 1 FROM pg_roles WHERE rolname = '$1'" | grep -q 1 \
        || sql_admin -d postgres -c "CREATE ROLE $1 LOGIN PASSWORD '$(echappe "$2")'"
    sql_admin -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$1'" | grep -q 1 \
        || sql_admin -d postgres -c "CREATE DATABASE $1 OWNER $1"
    fermer_base "$1"
}

# Site de la console : seulement pour les adresses autorisées ; sans liste, pas de site (tunnel SSH).
ecrire_site_console() {
    if [ -z "${CONSOLE_DOMAIN:-}" ] || [ -z "${CONSOLE_ADRESSES_AUTORISEES:-}" ]; then
        rm -f "$SITES/console.caddy"
        echo "Console sans site public : ssh -L 8095:127.0.0.1:8095 <serveur>, puis http://localhost:8095"
        return
    fi
    {
        echo "$CONSOLE_DOMAIN {"
        [ "${TLS_INTERNE:-}" = 1 ] && printf '\ttls internal\n'
        printf '\timport securite\n\t@refuse not remote_ip %s\n\trespond @refuse 404\n\treverse_proxy console:8095\n}\n' "$CONSOLE_ADRESSES_AUTORISEES"
    } >"$SITES/console.caddy"
}

# socle [--portail] [--console]
cmd_socle() {
    mkdir -p "$CRECHES" "$SITES"
    chmod 700 "$TICAGOU_DONNEES"
    profil=""
    for option in "$@"; do
        case $option in
            --portail)
                : "${PORTAL_DOMAIN:?Renseignez PORTAL_DOMAIN dans socle.env}"
                : "${PORTAL_DB_PASSWORD:?Renseignez PORTAL_DB_PASSWORD dans socle.env}"
                # Sans elle, le portail tirerait une clé au hasard à chaque démarrage : photos, vidéos et PDF déjà
                # reçus deviendraient illisibles au premier redémarrage.
                : "${PORTAL_FILE_KEY:?Renseignez PORTAL_FILE_KEY dans socle.env (openssl rand -base64 32), et gardez-la dans votre coffre}"
                profil="$profil --profile portail"
                printf '%s {\n\timport securite\n\treverse_proxy portail:8090\n}\n' "$PORTAL_DOMAIN" >"$SITES/portail.caddy"
                ;;
            --console)
                mkdir -p "$EXPORTS" && chmod 711 "$EXPORTS"
                : "${CONSOLE_DB_PASSWORD:?Renseignez CONSOLE_DB_PASSWORD dans socle.env}"
                : "${CONSOLE_SECRET_KEY:?Renseignez CONSOLE_SECRET_KEY dans socle.env (openssl rand -base64 32)}"
                profil="$profil --profile console"
                ecrire_site_console
                ;;
            *) echo "Option inconnue : $option" >&2; exit 1 ;;
        esac
    done
    socle up -d db caddy
    until socle exec -T db pg_isready -q -U ticagou_admin -d postgres; do sleep 2; done
    if [ -n "$profil" ]; then
        case $profil in *portail*) base_de_service portail "$PORTAL_DB_PASSWORD" ;; esac
        case $profil in *console*) base_de_service console "$CONSOLE_DB_PASSWORD" ;; esac
        # shellcheck disable=SC2086
        socle $profil up -d --build
    fi
    socle exec -T caddy caddy reload --config /etc/caddy/Caddyfile
    echo "Socle démarré."
}

cmd_construire() {
    version=${1:-$(git -C .. describe --tags --always --dirty 2>/dev/null || echo dev)}
    docker build -f Dockerfile -t "ticagou-web:$version" -t ticagou-web:latest ..
    echo "$version" >.version
    echo "Image : ticagou-web:$version"
}

# creer <crèche> [--domaine <nom>] [--essai] : crèche = identifiant court, minuscules, chiffres et tirets.
#   Toute crèche créée est en évaluation 30 jours ; « abonnement » en fait une vraie crèche, sans perte. --essai : accepté,
#   sans effet (l'essai durait 15 jours).
cmd_creer() {
    slug=${1:?Usage : creer <crèche> [--domaine <nom>] [--essai]}; shift
    echo "$slug" | grep -Eq '^[a-z0-9][a-z0-9-]{1,40}$' || { echo "Identifiant invalide : minuscules, chiffres, tirets." >&2; exit 1; }
    domaine="$slug.$DOMAINE_BASE"
    essai=""
    while [ $# -gt 0 ]; do
        case $1 in
            --domaine) domaine=$2; shift ;;
            --essai) essai=1 ;;
            *) echo "Option inconnue : $1" >&2; exit 1 ;;
        esac
        shift
    done
    dossier=$(dossier_de "$slug")
    [ -e "$dossier" ] && { echo "Cette crèche existe déjà : $dossier" >&2; exit 1; }
    base=$(base_de "$slug")
    motdepasse=$(openssl rand -hex 24)
    jeton=$(openssl rand -hex 24)
    # Sauvegardes étalées entre 1 h et 5 h : les crèches ne se dérangent pas.
    heure=$(printf '%02d:%02d' $((1 + $(od -An -N1 -tu1 /dev/urandom) % 4)) $(($(od -An -N1 -tu1 /dev/urandom) % 60)))

    mkdir -p "$dossier/data"
    chmod 700 "$dossier"
    # Le conteneur tourne avec l'utilisateur 1000.
    chown 1000:1000 "$dossier/data" 2>/dev/null || true
    (
        umask 077
        cat >"$dossier/creche.env" <<FIN
IMAGE=ticagou-web:$(cat "$DEPLOY/.version" 2>/dev/null || echo latest)
DOMAINE=$domaine
TICAGOU_DB_URL=jdbc:postgresql://db:5432/$base
TICAGOU_DB_USER=$base
TICAGOU_DB_PASSWORD=$motdepasse
TICAGOU_JWT_SECRET=$(secret)
TICAGOU_STORAGE_KEY=$(secret)
TICAGOU_BACKUP_RECOVERYKEY=$(cle_de_secours)
TICAGOU_BACKUP_DAILYTIME=$heure
TICAGOU_SETUP_TOKEN=$jeton
FIN
        # Coordonnées du support, affichées dans l'aide de l'application (socle.env).
        for variable in TICAGOU_SUPPORT_PHONE TICAGOU_SUPPORT_WHATSAPP TICAGOU_SUPPORT_EMAIL TICAGOU_SUPPORT_HOURS TICAGOU_SUPPORT_DELAY; do
            valeur=$(printenv "$variable" || true)
            if [ -n "$valeur" ]; then printf '%s=%s\n' "$variable" "$valeur" >>"$dossier/creche.env"; fi
        done
    )
    if [ -n "${PORTAL_DOMAIN:-}" ] && socle ps --status running portail 2>/dev/null | grep -q portail; then
        code=$(cmd_code_supervision "$slug")
        printf 'TICAGOU_MONITORING_URL=https://%s\nTICAGOU_MONITORING_CODE=%s\n' "$PORTAL_DOMAIN" "$code" >>"$dossier/creche.env"
    fi

    # CREATEDB : test de restauration.
    sql_admin -d postgres -c "CREATE ROLE $base LOGIN CREATEDB PASSWORD '$motdepasse'"
    sql_admin -d postgres -c "CREATE DATABASE $base OWNER $base"
    fermer_base "$base"

    instance "$slug" up -d
    attendre "$slug"
    ecrire_site "$slug" "$domaine"
    echo
    echo "Évaluation de 30 jours : « abonnement $slug … » la transforme en abonnement, sans perte de données."
    echo "Crèche créée. Lien de démarrage à remettre à la directrice (une seule utilisation) :"
    echo "  https://$domaine/setup?jeton=$jeton"
    echo
    echo "À copier dans votre coffre : $dossier/creche.env (clés de cette crèche)."
}

# abonnement <crèche> --numero N --places N --pc N --tablettes N --jusqu-au AAAA-MM-JJ [--formule "libellé"]
#   Une seule formule, tout compris (ADR 0021) : --offre et --modules, encore envoyés par d'anciens ordres, sont acceptés
#   sans effet. Prix : docs/commercial/bareme-tarifaire.md
cmd_abonnement() {
    slug=${1:?Usage : abonnement <crèche> --numero ... --jusqu-au AAAA-MM-JJ}; shift
    existe "$slug"
    numero="" formule="" places="" pc=2 tablettes=2 fin=""
    while [ $# -gt 0 ]; do
        case $1 in
            --numero) numero=$2 ;;
            --offre|--modules) ;;
            --formule) formule=$2 ;;
            --places) places=$2 ;;
            --pc) pc=$2 ;;
            --tablettes) tablettes=$2 ;;
            --jusqu-au) fin=$2 ;;
            *) echo "Option inconnue : $1" >&2; exit 1 ;;
        esac
        shift 2
    done
    [ -n "$numero" ] && [ -n "$places" ] && [ -n "$fin" ] || { echo "--numero, --places et --jusqu-au sont obligatoires." >&2; exit 1; }
    echo "$places$pc$tablettes" | grep -Eq '^[0-9]+$' || { echo "--places, --pc et --tablettes sont des nombres." >&2; exit 1; }
    echo "$fin" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' || { echo "--jusqu-au : AAAA-MM-JJ." >&2; exit 1; }
    [ -n "$formule" ] || formule="Ti’Cagou — jusqu’à $places places"
    titulaire=$(sql_creche "$slug" "SELECT coalesce((SELECT name FROM structure LIMIT 1), '$slug')")
    sql_creche "$slug" "INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until, modules)
        VALUES (1, '$(echappe "$numero")', '$(echappe "$titulaire")', '$(echappe "$formule")', $places, $pc, $tablettes, '$fin', NULL)
        ON CONFLICT (id) DO UPDATE SET number = excluded.number, holder = excluded.holder, plan = excluded.plan,
            places = excluded.places, desktops = excluded.desktops, tablets = excluded.tablets,
            valid_until = excluded.valid_until, modules = excluded.modules, updated_at = now()"
    echo "Abonnement enregistré pour $slug, jusqu'au $fin. Effet immédiat."
}

# mettre-a-jour <version> [<crèche>] : une crèche témoin d'abord, puis --toutes. S'arrête à la première panne.
cmd_mettre_a_jour() {
    version=${1:?Usage : mettre-a-jour <version> <crèche>|--toutes}
    cible=${2:?Usage : mettre-a-jour <version> <crèche>|--toutes}
    docker image inspect "ticagou-web:$version" >/dev/null
    liste=$cible
    [ "$cible" = --toutes ] && liste=$(creches)
    for slug in $liste; do
        existe "$slug"
        echo "--- $slug"
        sed -i.bak "s|^IMAGE=.*|IMAGE=ticagou-web:$version|" "$(dossier_de "$slug")/creche.env"
        rm -f "$(dossier_de "$slug")/creche.env.bak"
        instance "$slug" up -d
        attendre "$slug"
    done
    echo "Mise à jour terminée."
}

etat_conteneur() {
    docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "creche-$1" 2>/dev/null || echo absent
}

# Texte JSON : guillemets et barres obliques inverses échappés, sauts de ligne retirés.
json_texte() { printf '"%s"' "$(printf %s "$1" | tr -d '\n\r' | sed 's/\\/\\\\/g; s/"/\\"/g')"; }

# etat --json : pour la console de l'éditeur (ADR 0017) ; conteneur, image, domaine et abonnement de chaque crèche,
# disque des données. Aucun secret, aucune donnée de crèche.
cmd_etat_json() {
    libre=$(df -P -B1 "$TICAGOU_DONNEES" 2>/dev/null | awk 'NR == 2 { print $4 }')
    total=$(df -P -B1 "$TICAGOU_DONNEES" 2>/dev/null | awk 'NR == 2 { print $2 }')
    # Versions de l'image construites sur ce serveur : la console les propose pour « mettre à jour ».
    images=$(docker images ticagou-web --format '{{.Tag}}' 2>/dev/null | grep -E '^[0-9A-Za-z][0-9A-Za-z._-]{0,29}$' | sed 's/.*/"&"/' | paste -sd, - || true)
    printf '{"at":"%s","disk":{"freeBytes":%s,"totalBytes":%s},"images":[%s],"creches":[' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${libre:-null}" "${total:-null}" "$images"
    premier=1
    for slug in $(creches); do
        [ -f "$(dossier_de "$slug")/creche.env" ] || continue
        abonnement=$(sql_creche "$slug" "SELECT coalesce((SELECT json_build_object('number', number, 'plan', plan,
            'places', places, 'desktops', desktops, 'tablets', tablets, 'validUntil', valid_until, 'modules', modules)
            FROM subscription WHERE id = 1)::text, 'null')" 2>/dev/null || echo null)
        [ -n "$abonnement" ] || abonnement=null
        # Rubriques masquées à la demande de la crèche (ADR 0022).
        masquees=$(sql_creche "$slug" "SELECT coalesce(hidden_sections, '') FROM structure LIMIT 1" 2>/dev/null || true)
        [ $premier = 1 ] || printf ','
        premier=0
        printf '{"slug":%s,"container":%s,"image":%s,"domain":%s,"hiddenSections":%s,"subscription":%s}' "$(json_texte "$slug")" \
            "$(json_texte "$(etat_conteneur "$slug")")" "$(json_texte "$(valeur "$slug" IMAGE | cut -d: -f2)")" \
            "$(json_texte "$(valeur "$slug" DOMAINE)")" "$(json_texte "$masquees")" "$abonnement"
    done
    printf ']}\n'
}

# rapport-console : envoie `etat --json` à la console (cron toutes les 5 min, sur l'hôte)
#   */5 * * * * /opt/ticagou/ticagou-web/deploy/creche.sh rapport-console >>/var/log/ticagou-console.log 2>&1
cmd_rapport_console() {
    : "${CONSOLE_HOST_TOKEN:?Renseignez CONSOLE_HOST_TOKEN dans socle.env}"
    entetes=$(mktemp)
    trap 'rm -f "$entetes"' EXIT
    # Le jeton passe par un fichier : il n'apparaît pas dans la liste des processus.
    printf 'Authorization: Bearer %s\nContent-Type: application/json\n' "$CONSOLE_HOST_TOKEN" >"$entetes"
    cmd_etat_json | curl -fsS -m 30 -X POST -H @"$entetes" --data-binary @- http://127.0.0.1:8095/interne/v1/hote
}

cmd_etat() {
    [ "${1:-}" = --json ] && { cmd_etat_json; return; }
    printf '%-24s %-10s %-22s %s\n' CRÈCHE ÉTAT VERSION "ABONNEMENT JUSQU'AU"
    for slug in $(creches); do
        etat=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "creche-$slug" 2>/dev/null || echo arrêtée)
        fin=$(sql_creche "$slug" "SELECT coalesce((SELECT valid_until::text FROM subscription), 'évaluation')" 2>/dev/null || echo "?")
        printf '%-24s %-10s %-22s %s\n' "$slug" "$etat" "$(valeur "$slug" IMAGE | cut -d: -f2)" "$fin"
    done
}

cmd_suspendre() { existe "$1"; instance "$1" stop; echo "$1 arrêtée. Les données sont conservées."; }
cmd_reprendre() { existe "$1"; instance "$1" up -d; attendre "$1"; echo "$1 relancée."; }

# restaurer <crèche> <archive.tcb> : archive du dossier data/backups de la crèche, ou récupérée hors site
cmd_restaurer() {
    slug=${1:?Usage : restaurer <crèche> <archive.tcb>}
    archive=${2:?Usage : restaurer <crèche> <archive.tcb>}
    case $archive in /*) ;; *) archive="$APPEL/$archive" ;; esac
    existe "$slug"
    nom=$(basename "$archive")
    [ -f "$(dossier_de "$slug")/data/backups/$nom" ] || cp "$archive" "$(dossier_de "$slug")/data/backups/$nom"
    instance "$slug" stop
    instance "$slug" run --rm --no-deps -e "TICAGOU_RECOVERY_KEY=$(valeur "$slug" TICAGOU_BACKUP_RECOVERYKEY)" \
        app restaurer --archive "/data/backups/$nom" --remplacer
    # La base vient d'être recréée : elle a perdu sa fermeture.
    fermer_base "$(base_de "$slug")"
    instance "$slug" up -d
    attendre "$slug"
    echo "$slug restaurée depuis $nom."
}

# telephone-perdu <crèche> <identifiant> : pour la direction, qui ne peut plus se connecter
cmd_telephone_perdu() {
    slug=${1:?Usage : telephone-perdu <crèche> <identifiant>}
    existe "$slug"
    login=${2:?Usage : telephone-perdu <crèche> <identifiant>}
    compte=$(sql_creche "$slug" "UPDATE app_user SET totp_secret = NULL, totp_enabled_at = NULL, totp_last_step = NULL WHERE lower(login) = lower('$(echappe "$login")') RETURNING login")
    [ -n "$compte" ] || { echo "Aucun compte « $login » dans $slug." >&2; return 1; }
    echo "Double authentification réinitialisée pour $compte : à refaire à la prochaine connexion."
}

# mot-de-passe <crèche> <identifiant> : mot de passe oublié (la direction surtout). Lien à usage unique, 24 heures, à
#   transmettre à la personne après avoir vérifié son identité ; elle choisit elle-même le nouveau mot de passe.
#   Seule l'empreinte du jeton est gardée en base.
cmd_mot_de_passe() {
    slug=${1:?Usage : mot-de-passe <crèche> <identifiant>}
    login=${2:?Usage : mot-de-passe <crèche> <identifiant>}
    existe "$slug"
    jeton=$(openssl rand -hex 24)
    compte=$(sql_creche "$slug" "INSERT INTO password_reset (token_hash, structure_id, user_id, expires_at)
        SELECT encode(sha256(convert_to('$jeton', 'UTF8')), 'hex'), structure_id, id, now() + interval '24 hours'
        FROM app_user WHERE lower(login) = lower('$(echappe "$login")') AND active RETURNING (SELECT login FROM app_user u WHERE u.id = user_id)")
    [ -n "$compte" ] || { echo "Aucun compte actif « $login » dans $slug." >&2; return 1; }
    echo "Lien de réinitialisation du mot de passe de $compte (usage unique, valable 24 heures) :"
    echo "  https://$(valeur "$slug" DOMAINE)/reinitialiser?jeton=$jeton"
}

# rubriques <crèche> [--masquer A,B] : rubriques à ne pas afficher, à la demande de la crèche (ADR 0022). Sans
#   --masquer, tout est réaffiché. Simple affichage : rien n'est effacé ni refusé.
RUBRIQUES="INSCRIPTIONS FACTURATION COMPTABILITE PERSONNEL REPAS PORTAIL_PARENTS"
cmd_rubriques() {
    slug=${1:?Usage : rubriques <crèche> [--masquer A,B]}; shift
    existe "$slug"
    masquer=""
    while [ $# -gt 0 ]; do
        case $1 in
            --masquer) masquer=${2:-} ;;
            *) echo "Option inconnue : $1" >&2; exit 1 ;;
        esac
        shift 2
    done
    for r in $(printf '%s' "$masquer" | tr ',' ' '); do
        case " $RUBRIQUES " in *" $r "*) ;; *) echo "Rubrique inconnue : $r (connues : $RUBRIQUES)." >&2; return 1 ;; esac
    done
    if [ -n "$masquer" ]; then
        sql_creche "$slug" "UPDATE structure SET hidden_sections = '$masquer'"
        echo "Rubriques masquées pour $slug : $masquer (prises en compte à la prochaine ouverture des écrans)."
    else
        sql_creche "$slug" "UPDATE structure SET hidden_sections = NULL"
        echo "Toutes les rubriques sont affichées pour $slug."
    fi
}

# sauvegarder-console : base de la console (factures de l'éditeur), chiffrée avec CONSOLE_SECRET_KEY, 30 jours gardés.
#   cron : 30 0 * * * /opt/ticagou/ticagou-web/deploy/creche.sh sauvegarder-console
#   relire : openssl enc -d -aes-256-cbc -pbkdf2 -pass env:CONSOLE_SECRET_KEY -in <fichier> | pg_restore ...
cmd_sauvegarder_console() {
    : "${CONSOLE_SECRET_KEY:?Renseignez CONSOLE_SECRET_KEY dans socle.env}"
    dossier="$TICAGOU_DONNEES/console-sauvegardes"
    mkdir -p "$dossier"
    chmod 700 "$dossier"
    fichier="$dossier/console-$(date +%Y%m%d-%H%M).dump.enc"
    socle exec -T db pg_dump -U ticagou_admin -Fc console \
        | openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:CONSOLE_SECRET_KEY -out "$fichier"
    [ -s "$fichier" ] || { echo "Sauvegarde de la console vide : $fichier" >&2; exit 1; }
    find "$dossier" -name 'console-*.dump.enc' -mtime +30 -delete
    echo "Console sauvegardée : $fichier"
}

# console-telephone-perdu <identifiant> : compte de la console de l'éditeur ; le QR code se réaffiche à la connexion
cmd_console_telephone_perdu() {
    login=${1:?Usage : console-telephone-perdu <identifiant>}
    sql_admin -d console -c "UPDATE operator SET totp_secret = NULL, totp_enabled_at = NULL, totp_last_step = NULL WHERE lower(login) = lower('$(echappe "$login")')"
    sql_admin -d console -c "INSERT INTO audit_event (at, operator_login, action, target) VALUES (now(), NULL, 'TOTP_REINITIALISE', '$(echappe "$login")')"
    echo "Double authentification de la console réinitialisée pour $login : à refaire à la prochaine connexion."
}

portail_admin() {
    socle exec -T portail wget -qO- --header "Authorization: Bearer $PORTAL_ADMIN_TOKEN" \
        --header "Content-Type: application/json" --post-data "$2" "http://localhost:8090/admin/v1/$1"
}

cmd_code_portail() {
    reponse=$(portail_admin activation-codes "{\"label\":\"${1:?Usage : code-portail <libellé>}\"}")
    code=$(printf '%s' "$reponse" | sed -n 's/.*"activationCode":"\([^"]*\)".*/\1/p')
    [ -n "$code" ] || { echo "Code non créé : $reponse" >&2; return 1; }
    echo "Code d'activation du portail parents : $code"
    echo "À saisir par la direction dans Paramètres › Portail parents, avec l'adresse https://${PORTAL_DOMAIN:-<domaine du portail>}."
}

cmd_code_supervision() {
    portail_admin supervision-codes "{\"label\":\"$1\"}" | sed -n 's/.*"activationCode":"\([^"]*\)".*/\1/p'
}

# supprimer <crèche> : fin de contrat, après restitution des données. Irréversible.
cmd_supprimer() {
    slug=${1:?Usage : supprimer <crèche>}
    existe "$slug"
    printf 'Efface la base et les documents de %s. Retapez son identifiant : ' "$slug"
    read -r reponse
    [ "$reponse" = "$slug" ] || { echo "Annulé."; exit 1; }
    base=$(base_de "$slug")
    instance "$slug" down
    sql_admin -d postgres -c "DROP DATABASE IF EXISTS $base WITH (FORCE)"
    sql_admin -d postgres -c "DROP ROLE IF EXISTS $base"
    rm -f "$SITES/$slug.caddy"
    socle exec -T caddy caddy reload --config /etc/caddy/Caddyfile
    rm -rf "$(dossier_de "$slug")"
    echo "$slug supprimée : base, documents, sauvegardes et site effacés."
}

# exporter <crèche> : toute la crèche (base, documents, clés) dans une archive chiffrée avec CONSOLE_SECRET_KEY, écrite
# dans console-exports et gardée 7 jours ; la console la fait télécharger avant une suppression (restitution aussi).
#   relire : openssl enc -d -aes-256-cbc -pbkdf2 -pass env:CONSOLE_SECRET_KEY -in <fichier> | tar xz
#   recréer la crèche : ./creche.sh reimporter <fichier>
cmd_exporter() {
    slug=${1:?Usage : exporter <crèche>}
    existe "$slug"
    : "${CONSOLE_SECRET_KEY:?Renseignez CONSOLE_SECRET_KEY dans socle.env}"
    mkdir -p "$EXPORTS"
    chmod 711 "$EXPORTS"
    dossier=$(dossier_de "$slug")
    travail=$(mktemp -d)
    mkdir "$travail/$slug"
    if ! socle exec -T db pg_dump -U ticagou_admin -Fc "$(base_de "$slug")" >"$travail/$slug/base.dump" || [ ! -s "$travail/$slug/base.dump" ]; then
        rm -rf "$travail"
        echo "Export de la base de $slug impossible." >&2
        exit 1
    fi
    cp "$dossier/creche.env" "$travail/$slug/creche.env"
    # Documents et pièces de l'instance ; ses sauvegardes, plus anciennes que cet export, n'y sont pas.
    tar -C "$dossier" --exclude=data/backups -cf "$travail/$slug/donnees.tar" data
    cat >"$travail/$slug/LISEZMOI.txt" <<FIN
Export complet de la crèche $slug ($(valeur "$slug" DOMAINE)), fait le $(date '+%d/%m/%Y à %H:%M').

base.dump    base PostgreSQL (pg_dump, format personnalisé) : enfants, familles, contrats, présences, factures, journal
donnees.tar  documents de la crèche, chiffrés avec TICAGOU_STORAGE_KEY
creche.env   clés de la crèche (base, documents, clé de secours des sauvegardes) : à garder au coffre

Recréer la crèche à l'identique sur le serveur : ./creche.sh reimporter <ce fichier .tar.gz.enc>
Lire seulement la base : pg_restore -l base.dump, ou pg_restore -d <base vide> base.dump
FIN
    fichier="$EXPORTS/$slug-$(date +%Y%m%d-%H%M).tar.gz.enc"
    tar -C "$travail" -czf "$travail/export.tar.gz" "$slug"
    openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:CONSOLE_SECRET_KEY -in "$travail/export.tar.gz" -out "$fichier"
    rm -rf "$travail"
    [ -s "$fichier" ] || { echo "Export vide : $fichier" >&2; exit 1; }
    chmod 600 "$fichier"
    # Lu par la console, qui tourne avec l'utilisateur 1001.
    chown 1001:1001 "$fichier" 2>/dev/null || true
    echo "Export prêt : $(basename "$fichier") ($(wc -c <"$fichier" | tr -d ' ') octets)"
}

# Suppression ordonnée par la console : seulement si l'export qu'elle a fait télécharger est là, complet et récent.
supprimer_apres_export() {
    slug=$1 export=$2
    existe "$slug"
    echo "$export" | grep -Eq "^$slug-[0-9]{8}-[0-9]{4}\\.tar\\.gz\\.enc\$" || { echo "Export d'une autre crèche ou absent : refusé." >&2; exit 1; }
    fichier="$EXPORTS/$export"
    [ -s "$fichier" ] || { echo "Export $export introuvable : suppression refusée." >&2; exit 1; }
    [ -n "$(find "$fichier" -mmin -1440)" ] || { echo "Export de plus de 24 h : refaites l'export avant de supprimer." >&2; exit 1; }
    echo "$slug" | cmd_supprimer "$slug"
}

# reimporter <export.tar.gz.enc> : recrée une crèche supprimée depuis son export (mêmes clés, même domaine).
cmd_reimporter() {
    archive=${1:?Usage : reimporter <export.tar.gz.enc>}
    case $archive in /*) ;; *) archive="$APPEL/$archive" ;; esac
    : "${CONSOLE_SECRET_KEY:?Renseignez CONSOLE_SECRET_KEY dans socle.env}"
    travail=$(mktemp -d)
    openssl enc -d -aes-256-cbc -pbkdf2 -pass env:CONSOLE_SECRET_KEY -in "$archive" -out "$travail/export.tar.gz"
    tar -C "$travail" -xzf "$travail/export.tar.gz"
    rm -f "$travail/export.tar.gz"
    slug=$(ls "$travail")
    echo "$slug" | grep -Eq '^[a-z0-9][a-z0-9-]{1,40}$' || { rm -rf "$travail"; echo "Export illisible." >&2; exit 1; }
    dossier=$(dossier_de "$slug")
    [ -e "$dossier" ] && { rm -rf "$travail"; echo "Cette crèche existe déjà : $dossier" >&2; exit 1; }
    base=$(base_de "$slug")
    mkdir -p "$dossier"
    chmod 700 "$dossier"
    (umask 077; cp "$travail/$slug/creche.env" "$dossier/creche.env")
    tar -C "$dossier" -xf "$travail/$slug/donnees.tar"
    chown -R 1000:1000 "$dossier/data" 2>/dev/null || true
    sql_admin -d postgres -c "CREATE ROLE $base LOGIN CREATEDB PASSWORD '$(echappe "$(valeur "$slug" TICAGOU_DB_PASSWORD)")'"
    sql_admin -d postgres -c "CREATE DATABASE $base OWNER $base"
    fermer_base "$base"
    socle exec -T db pg_restore -U ticagou_admin -d "$base" --no-owner --role="$base" <"$travail/$slug/base.dump"
    rm -rf "$travail"
    instance "$slug" up -d
    attendre "$slug"
    ecrire_site "$slug" "$(valeur "$slug" DOMAINE)"
    echo "$slug recréée depuis $(basename "$archive") : https://$(valeur "$slug" DOMAINE)"
}

# demo <identifiant> [--domaine <nom>] : une instance de démonstration remplie par
# scripts/demo/donnees-demo.mjs (crèche, enfants, équipe, présences, factures), lancé dans un conteneur Node.
cmd_demo() {
    slug=${1:?Usage : demo <identifiant> [--domaine <nom>]}; shift
    domaine=""
    while [ $# -gt 0 ]; do
        case $1 in
            # Une seule formule (ADR 0021) : --formule, d’anciens ordres de la console, est sans effet.
            --formule) ;;
            --domaine) domaine=$2 ;;
            *) echo "Option inconnue : $1" >&2; exit 1 ;;
        esac
        shift 2
    done
    if [ -n "$domaine" ]; then cmd_creer "$slug" --domaine "$domaine"; else cmd_creer "$slug"; fi
    echo "DEMO=1" >>"$(dossier_de "$slug")/creche.env"
    jeton=$(valeur "$slug" TICAGOU_SETUP_TOKEN)
    set -- --url "http://creche-$slug:8080" --jeton "$jeton"
    if [ -n "${PORTAL_DOMAIN:-}" ] && socle ps --status running portail 2>/dev/null | grep -q portail; then
        code=$(portail_admin activation-codes "{\"label\":\"$slug\"}" | sed -n 's/.*"activationCode":"\([^"]*\)".*/\1/p')
        [ -n "$code" ] && set -- "$@" --portail "https://$PORTAL_DOMAIN" --code "$code"
    fi
    echo "Chargement des données de démonstration (1 à 3 minutes)…"
    docker run --rm --network ticagou -v "$DEPLOY/../scripts/demo:/demo:ro" node:24-alpine node /demo/donnees-demo.mjs "$@"
    sql_creche "$slug" "INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until, modules)
        SELECT 1, 'DEMO', name, 'Ti’Cagou (démonstration)', 60, 5, 5, CURRENT_DATE + 365, NULL FROM structure LIMIT 1
        ON CONFLICT (id) DO UPDATE SET plan = excluded.plan, modules = NULL, valid_until = excluded.valid_until, updated_at = now()"
    echo "Démonstration prête : https://$(valeur "$slug" DOMAINE)"
    echo "Direction : nathalie.kaloi / Niaouli-2026! (comptes : docs/commercial/demonstrations.md)"
}

# supprimer-demo <identifiant> : sans confirmation, mais seulement pour une instance créée par « demo ».
cmd_supprimer_demo() {
    slug=${1:?Usage : supprimer-demo <identifiant>}
    existe "$slug"
    grep -q '^DEMO=1$' "$(dossier_de "$slug")/creche.env" || { echo "$slug n'est pas une démonstration : refusé." >&2; exit 1; }
    echo "$slug" | cmd_supprimer "$slug"
}

# appliquer : ordres confirmés dans la console (ADR 0017), exécutés ici sur une liste blanche ; cron chaque minute :
#   * * * * * /opt/ticagou/ticagou-web/deploy/creche.sh appliquer >>/var/log/ticagou-console.log 2>&1
CONSOLE_INTERNE=${CONSOLE_INTERNE:-http://127.0.0.1:8095/interne/v1}

# Paramètre d'un ordre (« clé=valeur;clé=valeur »).
param() { printf '%s' "$2" | tr ';' '\n' | sed -n "s/^$1=//p"; }

# Contrôle d'un ordre : identifiants, type de la liste blanche, valeurs dans un alphabet sûr.
ordre_valide() {
    echo "$1" | grep -Eq '^[0-9a-f-]{36}$' || return 1
    case $2 in CREER|DEMO|SUPPRIMER_DEMO|ABONNEMENT|SUSPENDRE|REPRENDRE|METTRE_A_JOUR|EXPORTER|SUPPRIMER|RUBRIQUES|TELEPHONE_PERDU|MOT_DE_PASSE|CODE_PORTAIL) ;; *) return 1 ;; esac
    echo "$3" | grep -Eq '^[a-z0-9][a-z0-9-]{1,40}$' || return 1
    if [ -n "$4" ] && printf '%s\n' "$4" | tr ';' '\n' | grep -Evq '^[a-z_]+=[A-Za-z0-9.,_-]*$'; then
        return 1
    fi
    return 0
}

executer_ordre() {
    type=$1 slug=$2 params=$3
    case $type in
        CREER)
            domaine=$(param domaine "$params")
            if [ -n "$domaine" ]; then cmd_creer "$slug" --domaine "$domaine"; else cmd_creer "$slug"; fi ;;
        DEMO)
            domaine=$(param domaine "$params")
            if [ -n "$domaine" ]; then cmd_demo "$slug" --domaine "$domaine"; else cmd_demo "$slug"; fi ;;
        SUPPRIMER_DEMO) cmd_supprimer_demo "$slug" ;;
        ABONNEMENT)
            # Une seule formule (ADR 0021) : l'offre et les modules d'anciens ordres ne sont plus transmis.
            cmd_abonnement "$slug" --numero "$(param numero "$params")" --places "$(param places "$params")" \
                --pc "$(param pc "$params")" --tablettes "$(param tablettes "$params")" --jusqu-au "$(param jusqu_au "$params")" ;;
        SUSPENDRE) cmd_suspendre "$slug" ;;
        REPRENDRE) cmd_reprendre "$slug" ;;
        METTRE_A_JOUR) cmd_mettre_a_jour "$(param version "$params")" "$slug" ;;
        EXPORTER) cmd_exporter "$slug" ;;
        SUPPRIMER) supprimer_apres_export "$slug" "$(param export "$params")" ;;
        RUBRIQUES)
            masquer=$(param masquer "$params")
            if [ -n "$masquer" ]; then cmd_rubriques "$slug" --masquer "$masquer"; else cmd_rubriques "$slug"; fi ;;
        TELEPHONE_PERDU) cmd_telephone_perdu "$slug" "$(param identifiant "$params")" ;;
        MOT_DE_PASSE) cmd_mot_de_passe "$slug" "$(param identifiant "$params")" ;;
        CODE_PORTAIL) existe "$slug"; cmd_code_portail "$slug" ;;
        *) echo "Ordre hors liste blanche : $type" >&2; return 1 ;;
    esac
}

cmd_appliquer() {
    : "${CONSOLE_HOST_TOKEN:?Renseignez CONSOLE_HOST_TOKEN dans socle.env}"
    mkdir -p "$TICAGOU_DONNEES"
    # Un seul passage à la fois : un ordre long (démonstration, mise à jour) ne se relance pas à la minute suivante.
    exec 9>"$TICAGOU_DONNEES/.appliquer.lock"
    flock -n 9 || exit 0
    # Exports déjà téléchargés : sur l'ordinateur de l'éditeur, plus besoin de les garder ici au-delà de 7 jours.
    [ -d "$EXPORTS" ] && find "$EXPORTS" -name '*.tar.gz.enc' -mtime +7 -delete
    entetes=$(mktemp) ordres=$(mktemp) sortie=$(mktemp)
    trap 'rm -f "$entetes" "$ordres" "$sortie"' EXIT
    printf 'Authorization: Bearer %s\n' "$CONSOLE_HOST_TOKEN" >"$entetes"
    curl -fsS -m 30 -H @"$entetes" "$CONSOLE_INTERNE/ordres" >"$ordres"
    tab=$(printf '\t')
    while IFS="$tab" read -r id type slug params; do
        [ -n "$id" ] || continue
        # Pris une seule fois : si un autre passage l'a pris, la console répond 409 et on passe.
        curl -fs -m 30 -o /dev/null -X POST -H @"$entetes" "$CONSOLE_INTERNE/ordres/$id/debut" || continue
        echo "$(date '+%d/%m/%Y %H:%M') ordre $type $slug"
        if ! ordre_valide "$id" "$type" "$slug" "$params"; then
            echo "Ordre refusé par creche.sh : type ou paramètres hors liste blanche." >"$sortie"
            ok=false
        elif (executer_ordre "$type" "$slug" "$params") </dev/null >"$sortie" 2>&1; then
            ok=true
        else
            ok=false
        fi
        curl -fsS -m 30 -o /dev/null -X POST -H @"$entetes" -H 'Content-Type: text/plain' --data-binary @"$sortie" \
            "$CONSOLE_INTERNE/ordres/$id/fin?ok=$ok" || echo "Compte rendu de l'ordre $id impossible." >&2
    done <"$ordres"
}

aide() {
    cat <<FIN
socle [--portail] [--console]  démarre PostgreSQL, Caddy et, en option, le portail et la console
construire [version]           construit l'image de l'application
creer <crèche> [--domaine n]   crée une instance (évaluation de 30 jours) et donne le lien de démarrage
abonnement <crèche> ...        --numero --places --jusqu-au [--pc --tablettes --formule "libellé"]
etat [--json]                  liste les crèches, leur version, leur abonnement (--json : pour la console)
mettre-a-jour <version> <crèche>|--toutes
suspendre <crèche>             arrête l'instance (impayé, fin de contrat)
reprendre <crèche>
restaurer <crèche> <archive>   remplace les données par une sauvegarde
telephone-perdu <crèche> <identifiant>   double authentification à refaire (direction, comptabilité)
mot-de-passe <crèche> <identifiant>   lien de réinitialisation du mot de passe (24 h, usage unique)
rubriques <crèche> [--masquer A,B]   rubriques à ne pas afficher ; sans --masquer : tout réafficher
                               (INSCRIPTIONS, FACTURATION, COMPTABILITE, PERSONNEL, REPAS, PORTAIL_PARENTS)
code-portail <libellé>         code d'activation du portail parents
sauvegarder-console            sauvegarde chiffrée de la base de la console (cron, chaque nuit)
rapport-console                envoie l'état des crèches à la console (cron, toutes les 5 min)
console-telephone-perdu <identifiant>   double authentification de la console à refaire
demo <identifiant> [--formule F] instance de démonstration remplie (Essentiel, Gestion ou Complet)
supprimer-demo <identifiant>   efface une démonstration (refusé pour une vraie crèche)
appliquer                      exécute les ordres confirmés dans la console (cron, chaque minute)
exporter <crèche>              base, documents et clés dans une archive chiffrée (console-exports, 7 jours)
reimporter <export>            recrée une crèche supprimée depuis son export
supprimer <crèche>             efface tout (depuis la console : après export téléchargé)
FIN
}

commande=${1:-aide}
[ $# -gt 0 ] && shift
case $commande in
    socle) cmd_socle "$@" ;;
    construire) cmd_construire "$@" ;;
    creer) cmd_creer "$@" ;;
    abonnement) cmd_abonnement "$@" ;;
    etat) cmd_etat "$@" ;;
    mettre-a-jour) cmd_mettre_a_jour "$@" ;;
    suspendre) cmd_suspendre "${1:?crèche manquante}" ;;
    reprendre) cmd_reprendre "${1:?crèche manquante}" ;;
    restaurer) cmd_restaurer "$@" ;;
    telephone-perdu) cmd_telephone_perdu "$@" ;;
    mot-de-passe) cmd_mot_de_passe "$@" ;;
    rubriques) cmd_rubriques "$@" ;;
    code-portail) cmd_code_portail "$@" ;;
    sauvegarder-console) cmd_sauvegarder_console ;;
    rapport-console) cmd_rapport_console ;;
    console-telephone-perdu) cmd_console_telephone_perdu "$@" ;;
    demo) cmd_demo "$@" ;;
    supprimer-demo) cmd_supprimer_demo "$@" ;;
    appliquer) cmd_appliquer ;;
    exporter) cmd_exporter "$@" ;;
    reimporter) cmd_reimporter "$@" ;;
    supprimer) cmd_supprimer "$@" ;;
    *) aide ;;
esac
