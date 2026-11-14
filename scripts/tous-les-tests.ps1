<#
.SYNOPSIS
  Lance tous les tests automatisés de Ti'Cagou, l'un après l'autre, et affiche un bilan.

.DESCRIPTION
  Serveur, portail parents et console (Maven : tests unitaires et d'intégration), fronts du bureau, des parents et de
  la console (Vitest et vérification des types), cohérence des plugins des applications mobiles. Une suite en échec
  n'arrête pas les suivantes ; le bilan dit lesquelles relancer. Docker Desktop doit tourner : sans lui, les tests
  d'intégration sont ignorés. Comptez 15 à 25 minutes. Voir docs/TESTER-EN-LOCAL.md, § 9.

.PARAMETER Rapide
  Fronts et applications mobiles seulement (2 à 3 minutes), sans les suites Maven.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\tous-les-tests.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\tous-les-tests.ps1 -Rapide
#>
param([switch]$Rapide)

$root = Split-Path -Parent $PSScriptRoot
$results = @()

function Invoke-Suite([string]$name, [string]$folder, [string[]]$commands) {
    Write-Host "`n== $name ==" -ForegroundColor Cyan
    $start = Get-Date
    $ok = $true
    Push-Location (Join-Path $root $folder)
    try {
        foreach ($command in $commands) {
            Write-Host "  > $command" -ForegroundColor DarkGray
            # cmd /c : le code de sortie de pnpm et de mvnw.cmd est fiable, et stderr n'interrompt pas PowerShell 5.1.
            cmd /c $command
            if ($LASTEXITCODE -ne 0) { $ok = $false; break }
        }
    } finally {
        Pop-Location
    }
    $minutes = [math]::Round(((Get-Date) - $start).TotalMinutes, 1)
    $script:results += [pscustomobject]@{ Suite = $name; Resultat = $(if ($ok) { 'OK' } else { 'ÉCHEC' }); Minutes = $minutes }
}

cmd /c 'docker info --format "{{.ServerVersion}}" >nul 2>&1'
if ($LASTEXITCODE -ne 0 -and -not $Rapide) {
    Write-Host "Docker Desktop n'est pas lancé : les tests d'intégration seraient ignorés. Démarrez-le, puis relancez." -ForegroundColor Yellow
    exit 1
}

if (-not $Rapide) {
    Invoke-Suite 'Serveur' 'server' @('mvnw.cmd -q -B verify')
    Invoke-Suite 'Portail parents (serveur)' 'parents-portal\server' @('mvnw.cmd -q -B verify')
    Invoke-Suite 'Console de l''éditeur (serveur)' 'console\server' @('mvnw.cmd -q -B verify')
}
Invoke-Suite 'Bureau et tablette (web)' 'web' @('pnpm install --frozen-lockfile --silent', 'pnpm test', 'pnpm typecheck')
Invoke-Suite 'Application des parents' 'parents-portal\web' @('pnpm install --frozen-lockfile --silent', 'pnpm test', 'pnpm typecheck')
Invoke-Suite 'Console de l''éditeur (web)' 'console\web' @('pnpm install --frozen-lockfile --silent', 'pnpm test', 'pnpm typecheck')
Invoke-Suite 'Applications mobiles (plugins)' 'mobile' @('pnpm install --frozen-lockfile --silent', 'node scripts\check-versions.mjs')

Write-Host "`n================ Bilan ================" -ForegroundColor Green
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($results | Where-Object { $_.Resultat -ne 'OK' })
if ($failed.Count -gt 0) {
    Write-Host "À relancer : $(($failed | ForEach-Object { $_.Suite }) -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'Tout est vert.' -ForegroundColor Green
