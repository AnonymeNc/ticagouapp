#!/bin/sh
# Exploitation des crèches hébergées (ADR 0013) : une instance, une base et un dossier par crèche.
# Voir docs/EXPLOITATION.md. Commandes : ./creche.sh aide
set -eu
cd "$(dirname "$0")"
DEPLOY=$(pwd)

[ -f socle.env ] || { echo "socle.env manquant : copiez socle.env.example." >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. ./socle.env
set +a
: "${DOMAINE_BASE:?Renseignez DOMAINE_BASE dans socle.env}"
: "${TICAGOU_DONNEES:?Renseignez TICAGOU_DONNEES dans socle.env}"
CRECHES="$TICAGOU_DONNEES/creches"
SITES="$TICAGOU_DONNEES/caddy-sites"

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

cmd_socle() {
    mkdir -p "$CRECHES" "$SITES"
    chmod 700 "$TICAGOU_DONNEES"
    profil=""
    if [ "${1:-}" = --portail ]; then
        : "${PORTAL_DOMAIN:?Renseignez PORTAL_DOMAIN dans socle.env}"
        : "${PORTAL_DB_PASSWORD:?Renseignez PORTAL_DB_PASSWORD dans socle.env}"
        profil="--profile portail"
        printf '%s {\n\timport securite\n\treverse_proxy portail:8090\n}\n' "$PORTAL_DOMAIN" >"$SITES/portail.caddy"
    fi
    socle up -d db caddy
    until socle exec -T db pg_isready -q -U ticagou_admin -d postgres; do sleep 2; done
    if [ -n "$profil" ]; then
        sql_admin -d postgres -c "SELECT 1 FROM pg_roles WHERE rolname = 'portail'" | grep -q 1 \
            || sql_admin -d postgres -c "CREATE ROLE portail LOGIN PASSWORD '$(echappe "$PORTAL_DB_PASSWORD")'"
        sql_admin -d postgres -c "SELECT 1 FROM pg_database WHERE datname = 'portail'" | grep -q 1 \
            || sql_admin -d postgres -c "CREATE DATABASE portail OWNER portail"
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

# creer <crèche> [--domaine <nom>] : crèche = identifiant court, minuscules, chiffres et tirets
cmd_creer() {
    slug=${1:?Usage : creer <crèche> [--domaine <nom>]}; shift
    echo "$slug" | grep -Eq '^[a-z0-9][a-z0-9-]{1,40}$' || { echo "Identifiant invalide : minuscules, chiffres, tirets." >&2; exit 1; }
    domaine="$slug.$DOMAINE_BASE"
    [ "${1:-}" = --domaine ] && domaine=$2
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
    echo "Crèche créée. Lien de démarrage à remettre à la directrice (une seule utilisation) :"
    echo "  https://$domaine/setup?jeton=$jeton"
    echo
    echo "À copier dans votre coffre : $dossier/creche.env (clés de cette crèche)."
}

# abonnement <crèche> --numero N --offre essentiel|gestion|complet --places N --pc N --tablettes N --jusqu-au AAAA-MM-JJ
#   [--modules A,B : modules ajoutés à l'offre] [--formule "libellé"]. Offres : ADR 0015, docs/commercial/bareme-tarifaire.md
cmd_abonnement() {
    slug=${1:?Usage : abonnement <crèche> --numero ... --jusqu-au AAAA-MM-JJ}; shift
    existe "$slug"
    numero="" formule="" places="" pc=2 tablettes=2 fin="" offre="" ajouts=""
    while [ $# -gt 0 ]; do
        case $1 in
            --numero) numero=$2 ;;
            --offre) offre=$2 ;;
            --formule) formule=$2 ;;
            --places) places=$2 ;;
            --pc) pc=$2 ;;
            --tablettes) tablettes=$2 ;;
            --jusqu-au) fin=$2 ;;
            --modules) ajouts=$2 ;;
            *) echo "Option inconnue : $1" >&2; exit 1 ;;
        esac
        shift 2
    done
    [ -n "$numero" ] && [ -n "$offre" ] && [ -n "$places" ] && [ -n "$fin" ] || { echo "--numero, --offre, --places et --jusqu-au sont obligatoires." >&2; exit 1; }
    case $offre in
        essentiel) libelle=Essentiel liste="" ;;
        gestion) libelle=Gestion liste=INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS ;;
        complet) libelle=Complet liste=INSCRIPTIONS,FACTURATION,COMPTABILITE,PERSONNEL,REPAS,PORTAIL_PARENTS ;;
        *) echo "--offre : essentiel, gestion ou complet." >&2; exit 1 ;;
    esac
    modules="'$(echappe "$liste,$ajouts")'"
    echo "$places$pc$tablettes" | grep -Eq '^[0-9]+$' || { echo "--places, --pc et --tablettes sont des nombres." >&2; exit 1; }
    echo "$fin" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' || { echo "--jusqu-au : AAAA-MM-JJ." >&2; exit 1; }
    [ -n "$formule" ] || formule="$libelle — jusqu’à $places places"
    titulaire=$(sql_creche "$slug" "SELECT coalesce((SELECT name FROM structure LIMIT 1), '$slug')")
    sql_creche "$slug" "INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until, modules)
        VALUES (1, '$(echappe "$numero")', '$(echappe "$titulaire")', '$(echappe "$formule")', $places, $pc, $tablettes, '$fin', $modules)
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

cmd_etat() {
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
    sql_creche "$slug" "UPDATE app_user SET totp_secret = NULL, totp_enabled_at = NULL, totp_last_step = NULL WHERE lower(login) = lower('$(echappe "$2")')"
    echo "Double authentification réinitialisée : à refaire à la prochaine connexion."
}

portail_admin() {
    socle exec -T portail wget -qO- --header "Authorization: Bearer $PORTAL_ADMIN_TOKEN" \
        --header "Content-Type: application/json" --post-data "$2" "http://localhost:8090/admin/v1/$1"
}

cmd_code_portail() { portail_admin activation-codes "{\"label\":\"${1:?Usage : code-portail <libellé>}\"}"; echo; }

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
    echo "$slug supprimée. Pensez à la copie hors site."
}

aide() {
    cat <<FIN
socle [--portail]              démarre PostgreSQL, Caddy et, en option, le portail
construire [version]           construit l'image de l'application
creer <crèche> [--domaine n]   crée une instance et donne le lien de démarrage
abonnement <crèche> ...        --numero --offre --places --jusqu-au [--pc --tablettes --modules A,B]
etat                           liste les crèches, leur version, leur abonnement
mettre-a-jour <version> <crèche>|--toutes
suspendre <crèche>             arrête l'instance (impayé, fin de contrat)
reprendre <crèche>
restaurer <crèche> <archive>   remplace les données par une sauvegarde
telephone-perdu <crèche> <identifiant>
code-portail <libellé>         code d'activation du portail parents
supprimer <crèche>             efface tout, après restitution des données
FIN
}

commande=${1:-aide}
[ $# -gt 0 ] && shift
case $commande in
    socle) cmd_socle "$@" ;;
    construire) cmd_construire "$@" ;;
    creer) cmd_creer "$@" ;;
    abonnement) cmd_abonnement "$@" ;;
    etat) cmd_etat ;;
    mettre-a-jour) cmd_mettre_a_jour "$@" ;;
    suspendre) cmd_suspendre "${1:?crèche manquante}" ;;
    reprendre) cmd_reprendre "${1:?crèche manquante}" ;;
    restaurer) cmd_restaurer "$@" ;;
    telephone-perdu) cmd_telephone_perdu "$@" ;;
    code-portail) cmd_code_portail "$@" ;;
    supprimer) cmd_supprimer "$@" ;;
    *) aide ;;
esac
