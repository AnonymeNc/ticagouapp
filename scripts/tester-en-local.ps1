<#
.SYNOPSIS
  Lance Ti'Cagou en local pour tester : base, serveur, application du bureau et, avec -Portail, le portail parents.

.DESCRIPTION
  Chaque programme s'ouvre dans sa propre fenêtre PowerShell (fermez-la pour l'arrêter, ou lancez -Arreter).
  Le port 8080 du serveur est libéré de force au lancement, y compris du Ti'Cagou installé sur ce PC (Windows demande
  alors une autorisation). Les autres programmes déjà lancés ne sont pas relancés. Voir docs/TESTER-EN-LOCAL.md.

.PARAMETER Portail
  Démarre aussi le portail parents (base, Mailpit, service hébergé, application des parents) et crée un code
  d'activation à saisir dans Paramètres › Portail parents.

.PARAMETER Demo
  Démonstration : une base à part (« ticagou_demo », documents dans server\data-demo) remplie automatiquement par
  scripts\demo\donnees-demo.mjs : 18 mois d'historique (présences, factures, paiements), équipe, repas, inscriptions,
  album de photos et vidéos, actualités, messages, demandes des parents (5 à 10 minutes la première fois). Les fois
  suivantes, seuls les pointages et transmissions du jour sont rejoués. La base de développement n'est pas touchée. Le
  portail parents démarre aussi : toutes les familles y ont accès (connexion par e-mail, code dans Mailpit).
  Parcours de démonstration : docs/commercial/demonstrations.md.

.PARAMETER Console
  Démarre aussi la console de l'éditeur (interface d'administration de Ti'Cagou : parc des crèches, abonnements,
  facturation, ordres) : base sur 5436, service sur 8095, interface sur http://localhost:1421. Premier compte :
  http://localhost:1421/premier-compte?jeton=dev, puis connexion avec une application d'authentification.

.PARAMETER RemiseAZero
  Efface la base de développement et les documents chiffrés pour rejouer l'installation (assistant /setup).
  Avec -Demo, efface seulement la base de démonstration, qui est rechargée aussitôt.
  Faites un instantané avant si vous tenez aux données (docs/TESTER-EN-LOCAL.md, § 10).

.PARAMETER Arreter
  Arrête les fenêtres lancées par ce script et les conteneurs Docker.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Portail

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Demo

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Demo -Console
#>
param([switch]$Portail, [switch]$Demo, [switch]$Console, [switch]$RemiseAZero, [switch]$Arreter)

# La démonstration montre les familles sur le portail.
if ($Demo) { $Portail = $true }

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$state = Join-Path $env:TEMP 'ticagou-tester-en-local.json'

function Write-Step($text) { Write-Host "`n> $text" -ForegroundColor Cyan }

function Test-Port([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $port); return $true } catch { return $false } finally { $client.Close() }
}

# Journal de chaque fenêtre, relu quand un programme ne répond pas.
function Get-WindowLog([string]$title) { Join-Path $env:TEMP ('ticagou-' + ($title -replace '[^A-Za-z]', '') + '.log') }

function Wait-Url([string]$url, [int]$seconds, [string]$name, [string]$window) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            Write-Host "  Prêt : $name ($url)" -ForegroundColor Green
            return $true
        } catch { Start-Sleep -Seconds 3 }
    }
    Write-Host "  $name ne répond pas encore ($url)." -ForegroundColor Yellow
    if ($window) {
        $log = Get-WindowLog $window
        if (Test-Path $log) {
            Write-Host "  Dernières lignes de sa fenêtre « Ti'Cagou - $window » ($log) :" -ForegroundColor Yellow
            Get-Content $log -Tail 15 | ForEach-Object { Write-Host "    $_" }
        } else {
            Write-Host "  Sa fenêtre « Ti'Cagou - $window » n'a rien écrit : si son titre commence par « Sélectionner »," -ForegroundColor Yellow
            Write-Host '  un clic l''a mise en pause : cliquez dedans puis appuyez sur Échap.' -ForegroundColor Yellow
        }
    }
    return $false
}

# Dans chaque fenêtre : le mode « édition rapide » de Windows est coupé (un clic dans la fenêtre mettait le programme en
# pause jusqu'à Échap), et la sortie est aussi écrite dans un journal (Get-WindowLog).
$windowPreamble = @'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
try {
    Add-Type -Namespace TiCagou -Name Console -MemberDefinition '[DllImport("kernel32.dll")] public static extern System.IntPtr GetStdHandle(int h); [DllImport("kernel32.dll")] public static extern bool GetConsoleMode(System.IntPtr h, out uint m); [DllImport("kernel32.dll")] public static extern bool SetConsoleMode(System.IntPtr h, uint m);'
    $in = [TiCagou.Console]::GetStdHandle(-10); $mode = 0
    if ([TiCagou.Console]::GetConsoleMode($in, [ref]$mode)) { $null = [TiCagou.Console]::SetConsoleMode($in, ($mode -band -bnot 0x40) -bor 0x80) }
} catch { }
'@

function Start-Window([string]$title, [string]$folder, [string]$command, [int]$port) {
    if (Test-Port $port) {
        Write-Host "  $title : port $port déjà utilisé, je garde celui qui tourne (redémarrez-le pour charger la dernière version du code)." -ForegroundColor Yellow
        return $null
    }
    $log = Get-WindowLog $title
    Remove-Item $log -ErrorAction SilentlyContinue
    # La dernière instruction de la commande est le programme : sa sortie passe aussi dans le journal.
    $script = "$windowPreamble`n`$Host.UI.RawUI.WindowTitle = 'Ti''Cagou - $title'; Set-Location -LiteralPath '$folder'; $command | Tee-Object -FilePath '$log'"
    # Commande encodée : guillemets et espaces des arguments (chemins, options Maven) arrivent intacts.
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
    $process = Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-EncodedCommand', $encoded -PassThru
    Write-Host "  $title démarre (fenêtre « Ti'Cagou - $title »)."
    return $process.Id
}

# Commande externe sans sortie : PowerShell 5.1 ferait de chaque ligne d'erreur de Docker une erreur bloquante.
function Invoke-Quiet([string]$command) {
    cmd /c "$command >nul 2>&1"
    return $LASTEXITCODE -eq 0
}

function Test-Docker {
    return (Invoke-Quiet 'docker info --format "{{.ServerVersion}}"')
}

function Assert-Docker {
    if (Test-Docker) { return }
    $desktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $desktop)) { throw "Docker Desktop est introuvable : installez-le (https://www.docker.com/products/docker-desktop/)." }
    Write-Host '  Docker Desktop est arrêté : je le démarre (jusqu''à 2 minutes)...'
    Start-Process $desktop
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        if (Test-Docker) { return }
    }
    throw 'Docker Desktop ne répond pas : lancez-le à la main puis relancez ce script.'
}

function Start-Containers([string]$folder, [string]$arguments, [string]$label) {
    Push-Location $folder
    try {
        # Via cmd : PowerShell 5.1 ferait de chaque ligne d'avancement de Docker une erreur bloquante.
        $output = cmd /c "docker compose up -d --wait $arguments 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "$label ne démarre pas : $(($output | Select-Object -Last 2) -join ' ')" }
    } finally { Pop-Location }
}

# Libère un port de force. L'occupant est un serveur oublié ou le Ti'Cagou installé (service Windows), dont le code n'est
# pas celui du dépôt. Arrêter un service, ou un programme d'un autre compte, demande l'autorisation de Windows.
function Clear-Port([int]$port) {
    if (-not (Test-Port $port)) { return }
    $elevated = @()
    $service = Get-Service -Name 'TiCagou' -ErrorAction SilentlyContinue
    if ($service -and $service.Status -eq 'Running') {
        Write-Host "  Le Ti'Cagou installé sur ce PC tourne : je l'arrête (Windows va demander une autorisation)." -ForegroundColor Yellow
        $elevated += 'Stop-Service -Name TiCagou -Force'
    }
    # netstat et non Get-NetTCPConnection, qui ne voit pas toujours les écoutes en IPv6 (« [::]:8080 »).
    $owners = @(netstat -ano | ForEach-Object {
        if ($_ -match "^\s*TCP\s+\S+:$port\s+(0\.0\.0\.0|\[::\]):0\s+\S+\s+(\d+)\s*$") { [int]$Matches[2] }
    } | Select-Object -Unique)
    foreach ($id in $owners) {
        $name = (Get-Process -Id $id -ErrorAction SilentlyContinue).ProcessName
        if ($name -match '^(wslrelay|com\.docker\..*)$') {
            # Port publié par un conteneur : tuer ce programme ferait tomber Docker.
            foreach ($container in @(cmd /c "docker ps -q --filter publish=$port 2>nul")) {
                if ($container) { $null = Invoke-Quiet "docker stop $container" }
            }
            Write-Host "  Port $port occupé par un conteneur Docker : arrêté." -ForegroundColor Yellow
            continue
        }
        Write-Host "  Port $port occupé par $name (PID $id) : je l'arrête." -ForegroundColor Yellow
        if (-not (Invoke-Quiet "taskkill /PID $id /T /F")) { $elevated += "taskkill /PID $id /T /F" }
    }
    if ($elevated.Count -gt 0) {
        try {
            Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', ($elevated -join '; ')
        } catch {
            throw "Autorisation refusée : le port $port reste occupé. Relancez le script et acceptez la demande de Windows."
        }
    }
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-Port $port)) {
            Write-Host "  Port $port libéré." -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Le port $port est toujours occupé : regardez qui le tient avec netstat -ano | findstr :$port"
}

# Abonnement de la base de démonstration : une ligne, comme avec deploy/creche.sh. Une seule formule (ADR 0021).
function Set-DemoAbonnement {
    $sql = "INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until) SELECT 1, 'DEMO', name, 'Ti''Cagou (demonstration)', 60, 5, 5, CURRENT_DATE + 365 FROM structure LIMIT 1 ON CONFLICT (id) DO UPDATE SET plan = excluded.plan, valid_until = excluded.valid_until"
    if (-not (Invoke-Quiet "docker exec ticagou-web-postgres psql -U ticagou -d ticagou_demo -c `"$sql`"")) { throw 'Abonnement de démonstration impossible à écrire.' }
}

# Base de développement installée : un abonnement d'un an, pour qu'elle ne passe pas en lecture seule au bout des 30
# jours d'évaluation. Une base qui a déjà un abonnement le garde (pour essayer la fin d'un abonnement, docs § 7).
function Set-DevAbonnement {
    $sql = "INSERT INTO subscription (id, number, holder, plan, places, desktops, tablets, valid_until) SELECT 1, 'DEV', name, 'Ti''Cagou (developpement)', 60, 5, 5, CURRENT_DATE + 365 FROM structure LIMIT 1 ON CONFLICT (id) DO NOTHING"
    $null = Invoke-Quiet "docker exec ticagou-web-postgres psql -U ticagou -d ticagou -c `"$sql`""
}

# Portail local : chaque rechargement de la démonstration (ou remise à zéro) crée une nouvelle crèche dans le portail,
# et l'ancienne y restait, avec les mêmes familles : les parents voyaient plusieurs « Les P'tits Niaoulis ». On retire
# du portail local les crèches qu'aucune base locale (ticagou, ticagou_demo…) n'utilise plus.
function Remove-PortalOrphans {
    $live = @()
    $bases = cmd /c 'docker exec ticagou-web-postgres psql -U ticagou -d ticagou -tAc "SELECT datname FROM pg_database WHERE datname LIKE ''ticagou%''" 2>nul'
    foreach ($base in $bases) {
        $base = "$base".Trim()
        if (-not $base) { continue }
        $live += cmd /c "docker exec ticagou-web-postgres psql -U ticagou -d $base -tAc `"SELECT portal_structure_id FROM portal_settings WHERE portal_structure_id IS NOT NULL`" 2>nul"
    }
    $live = @($live | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^[0-9a-f-]{36}$' })
    # Prudence : sans aucune crèche locale reliée (base illisible…), on ne retire rien.
    if ($live.Count -eq 0) { return }
    $list = ($live | ForEach-Object { "'$_'" }) -join ','
    $removed = @(cmd /c "docker exec parents-portal-portail-db-1 psql -U portail -d portail -tAc `"DELETE FROM portal_structure WHERE id NOT IN ($list) RETURNING name`" 2>nul" |
        Where-Object { "$_".Trim() })
    if ($removed.Count -gt 0) { Write-Host "  Portail : $($removed.Count) ancienne(s) crèche(s) de test retirée(s) (rechargements précédents)." }
}

# Clé de la console en développement : gardée d'un lancement à l'autre (elle chiffre les secrets TOTP en base).
function Get-ConsoleKey {
    $file = Join-Path $env:APPDATA 'ticagou\console-cle-dev.txt'
    if (-not (Test-Path $file)) {
        $null = New-Item -ItemType Directory -Force (Split-Path $file)
        $bytes = New-Object byte[] 32
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        Set-Content -Path $file -Value ([Convert]::ToBase64String($bytes)) -Encoding ascii
    }
    return (Get-Content $file -Raw).Trim()
}

function Install-Packages([string]$folder) {
    if (-not (Test-Path (Join-Path $folder 'node_modules'))) {
        Write-Host "  Installation des paquets de $folder (une seule fois)..."
        pnpm --dir $folder install --silent
    }
}

# --- Arrêt -------------------------------------------------------------------------------------

if ($Arreter) {
    Write-Step 'Arrêt des fenêtres lancées par ce script'
    if (Test-Path $state) {
        foreach ($id in (Get-Content $state | ConvertFrom-Json)) {
            if ($id) { cmd /c "taskkill /PID $id /T /F >nul 2>&1" }
        }
        Remove-Item $state
    }
    Write-Step 'Arrêt des conteneurs'
    $null = Invoke-Quiet "docker compose -f `"$root\docker-compose.yml`" stop"
    $null = Invoke-Quiet "docker compose -f `"$root\parents-portal\docker-compose.yml`" stop"
    $null = Invoke-Quiet "docker compose -f `"$root\console\docker-compose.yml`" stop"
    Write-Host 'Terminé.' -ForegroundColor Green
    return
}

# --- Préalables ---------------------------------------------------------------------------------

Write-Step 'Vérification des outils'
foreach ($tool in 'docker', 'pnpm', 'java') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "« $tool » est introuvable : voir docs/TESTER-EN-LOCAL.md, § 1." }
}
Assert-Docker

Write-Step 'Libération du port 8080'
Clear-Port 8080

if ($RemiseAZero -and $Demo) {
    Write-Step 'Remise à zéro de la base de démonstration'
    Start-Containers $root 'postgres' 'La base de données'
    $null = Invoke-Quiet 'docker exec ticagou-web-postgres psql -U ticagou -d ticagou -c "DROP DATABASE IF EXISTS ticagou_demo WITH (FORCE)"'
    $demoData = Join-Path $root 'server\data-demo'
    if (Test-Path $demoData) { Remove-Item -Recurse -Force $demoData }
    Write-Host '  Base de démonstration effacée : elle est rechargée ci-dessous.'
} elseif ($RemiseAZero) {
    Write-Step 'Remise à zéro de la base de développement'
    Push-Location $root
    $null = Invoke-Quiet 'docker compose down -v'
    Pop-Location
    $data = Join-Path $root 'server\data'
    if (Test-Path $data) { Remove-Item -Recurse -Force $data }
    Write-Host '  Base et documents effacés : l''assistant d''installation s''ouvrira au premier lancement.'
}

$pids = @()

Write-Step 'Base de données (Docker, port 5435)'
Start-Containers $root 'postgres' 'La base de données'

# Évaluation de 30 jours, comme en production (ADR 0011). Une base installée depuis plus longtemps reçoit un abonnement
# de développement (Set-DevAbonnement) plutôt qu'une évaluation allongée.
$serverEnv = ''
if ($Demo) {
    Write-Step 'Base de démonstration « ticagou_demo »'
    $exists = cmd /c 'docker exec ticagou-web-postgres psql -U ticagou -d ticagou -tAc "SELECT 1 FROM pg_database WHERE datname = ''ticagou_demo''" 2>nul'
    if ("$exists".Trim() -ne '1') {
        if (-not (Invoke-Quiet 'docker exec ticagou-web-postgres psql -U ticagou -d ticagou -c "CREATE DATABASE ticagou_demo"')) { throw 'Base de démonstration impossible à créer.' }
        Write-Host '  Base créée : elle sera remplie dès que le serveur répondra.'
    }
    $demoDocuments = Join-Path $root 'server\data-demo\documents'
    $serverEnv += "`$env:TICAGOU_DB_URL = 'jdbc:postgresql://localhost:5435/ticagou_demo'; `$env:TICAGOU_STORAGE_DIR = '$demoDocuments'; "
}

Write-Step 'Serveur (port 8080) et application du bureau (port 1420)'
Install-Packages (Join-Path $root 'web')
$pids += Start-Window 'Serveur' (Join-Path $root 'server') "$serverEnv.\mvnw.cmd spring-boot:run" 8080
$pids += Start-Window 'Bureau' $root 'pnpm --dir web dev' 1420

if ($Portail) {
    Write-Step 'Portail parents : base (5434) et Mailpit (8025)'
    Start-Containers (Join-Path $root 'parents-portal') '' 'La base du portail'
    Install-Packages (Join-Path $root 'parents-portal\web')
    $portalArgs = '"-Dspring-boot.run.arguments=--portal.secure-cookie=false --portal.admin-token=dev --portal.public-url=http://localhost:5180"'
    $pids += Start-Window 'Portail' (Join-Path $root 'parents-portal\server') ".\mvnw.cmd spring-boot:run $portalArgs" 8090
    $pids += Start-Window 'Application parents' $root 'pnpm --dir parents-portal\web dev' 5180
}

if ($Console) {
    Write-Step 'Console de l''éditeur : base (5436), service (8095), interface (1421)'
    # Mailpit sert aussi aux envois de la console (relances, factures) : il vient avec le portail.
    if (-not $Portail) { Start-Containers (Join-Path $root 'parents-portal') 'mailpit' 'Mailpit' }
    Start-Containers (Join-Path $root 'console') '' 'La base de la console'
    Install-Packages (Join-Path $root 'console\web')
    $consoleEnv = "`$env:CONSOLE_SECRET_KEY = '$(Get-ConsoleKey)'; `$env:CONSOLE_SETUP_TOKEN = 'dev'; `$env:CONSOLE_SECURE_COOKIE = 'false'; " +
        "`$env:CONSOLE_PORTAL_URL = 'http://localhost:8090'; `$env:CONSOLE_PORTAL_ADMIN_TOKEN = 'dev'; `$env:CONSOLE_HOST_TOKEN = 'dev'; " +
        "`$env:CONSOLE_MAIL_FROM = 'Ti''''Cagou <facturation@ticagou.nc>'; "
    $pids += Start-Window 'Console' (Join-Path $root 'console\server') "$consoleEnv.\mvnw.cmd spring-boot:run" 8095
    $pids += Start-Window 'Interface console' $root 'pnpm --dir console\web dev' 1421
}

$pids | Where-Object { $_ } | ConvertTo-Json | Set-Content $state

Write-Step 'Attente des serveurs (le premier démarrage compile : quelques minutes)'
$serverUp = Wait-Url 'http://localhost:8080/api/v1/health' 300 'Serveur' 'Serveur'
$null = Wait-Url 'http://localhost:1420' 120 'Application du bureau' 'Bureau'
$code = $null
if ($Portail) {
    if (Wait-Url 'http://localhost:8090/actuator/health' 300 'Portail parents' 'Portail') {
        $null = Wait-Url 'http://localhost:5180' 120 'Application des parents' 'Application parents'
        try {
            $code = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/admin/v1/activation-codes' `
                -Headers @{ Authorization = 'Bearer dev' } -ContentType 'application/json' -Body '{"label":"Test local"}').activationCode
        } catch { Write-Host "  Code d'activation non créé : $($_.Exception.Message)" -ForegroundColor Yellow }
    }
}
if ($Demo -and $serverUp) {
    $initialized = (Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/setup/status').initialized
    Write-Step $(if ($initialized) { 'Démonstration : pointages et transmissions du jour' } else { 'Démonstration : chargement des données (1 à 3 minutes)' })
    $demoArgs = @((Join-Path $root 'scripts\demo\donnees-demo.mjs'), '--url', 'http://localhost:8080')
    if ($initialized) { $demoArgs += '--aujourdhui' }
    if ($code) { $demoArgs += '--portail', 'http://localhost:8090', '--code', $code, '--mailpit', 'http://localhost:8025' }
    & node @demoArgs
    if ($LASTEXITCODE -ne 0) { Write-Host '  Le chargement de la démonstration a échoué (message ci-dessus).' -ForegroundColor Yellow }
    Set-DemoAbonnement
}
if (-not $Demo -and $serverUp) { Set-DevAbonnement }
if ($Portail -and $code) { Remove-PortalOrphans }
if ($Console) { $null = Wait-Url 'http://localhost:8095/actuator/health' 300 'Console de l''éditeur' 'Console' }

# --- Résumé -------------------------------------------------------------------------------------

Write-Host "`n================ Ti'Cagou en local ================" -ForegroundColor Green
Write-Host 'Bureau (directrice, comptabilité)  : http://localhost:1420'
Write-Host 'Tablette (équipe, code PIN)        : http://localhost:1420/kiosk'
if ($Portail) {
    Write-Host 'Application des parents            : http://localhost:5180'
    Write-Host 'E-mails de connexion (Mailpit)     : http://localhost:8025'
    if ($Demo) {
        Write-Host 'Parents de démonstration           : sarah.waheo@exemple.nc, kevin.gope@exemple.nc, anne.poedi@exemple.nc…'
        Write-Host '                                     (toutes les familles ; code de connexion dans Mailpit)'
    } elseif ($code) {
        Write-Host ''
        Write-Host 'Portail à activer dans Paramètres › Portail parents :' -ForegroundColor Cyan
        Write-Host '  Adresse du portail : http://localhost:8090'
        Write-Host "  Code d'activation  : $code"
    }
}
if ($Console) {
    Write-Host 'Console de l''éditeur (admin)       : http://localhost:1421'
    Write-Host '  Premier compte                   : http://localhost:1421/premier-compte?jeton=dev'
}
Write-Host ''
Write-Host 'Comptes et parcours : docs/TESTER-EN-LOCAL.md ; démonstration : docs/commercial/demonstrations.md'
Write-Host 'Tout arrêter        : powershell -ExecutionPolicy Bypass -File scripts\tester-en-local.ps1 -Arreter'
if (-not $serverUp) { Write-Host 'Le serveur n''a pas répondu à temps : voir ses dernières lignes plus haut.' -ForegroundColor Yellow }
