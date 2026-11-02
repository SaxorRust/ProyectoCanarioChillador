# ==============================================================================
# Script PowerShell: Monitor de Estado y Diagnostico de CI/CD en GitHub Actions
# Proyecto: El Canario Chillador (https://github.com/SaxorRust/ProyectoCanarioChillador)
# ==============================================================================

param(
    [switch]$Watch,
    [switch]$Diagnose,
    [string]$RunId = "",
    [int]$IntervalSec = 8
)

$ErrorActionPreference = "Stop"

# 1. Obtener Token de GitHub mediante Git Credential Manager
function Get-GitHubToken {
    try {
        $credOutput = "protocol=https`nhost=github.com`n" | git credential fill 2>$null
        $passLine = $credOutput | Where-Object { $_ -match "^password=" }
        if ($passLine) {
            return ($passLine -replace "^password=", "").Trim()
        }
    } catch {}
    if ($env:GITHUB_TOKEN) {
        return $env:GITHUB_TOKEN
    }
    return ""
}

$RepoOwner = "SaxorRust"
$RepoName = "ProyectoCanarioChillador"
$BaseApi = "https://api.github.com/repos/$RepoOwner/$RepoName"

$token = Get-GitHubToken
$headers = @{
    "User-Agent" = "PowerShell-CanarioMonitor"
    "Accept"     = "application/vnd.github+json"
}
if ($token) {
    $headers["Authorization"] = "Bearer $token"
}

# 2. Funcion para obtener la ultima ejecucion o una especifica
function Get-WorkflowRun {
    param([string]$TargetRunId)
    if ($TargetRunId) {
        return Invoke-RestMethod -Uri "$BaseApi/actions/runs/$TargetRunId" -Headers $headers
    } else {
        $runs = Invoke-RestMethod -Uri "$BaseApi/actions/runs?per_page=1" -Headers $headers
        if ($runs.workflow_runs.Count -gt 0) {
            return $runs.workflow_runs[0]
        }
    }
    return $null
}

# 3. Funcion para obtener los jobs de una ejecucion
function Get-RunJobs {
    param([string]$TargetRunId)
    $response = Invoke-RestMethod -Uri "$BaseApi/actions/runs/$TargetRunId/jobs" -Headers $headers
    return $response.jobs
}

# 4. Funcion para obtener los logs de un job fallido
function Get-JobFailedLogs {
    param([string]$JobId)
    try {
        $logText = Invoke-RestMethod -Uri "$BaseApi/actions/jobs/$JobId/logs" -Headers $headers
        $lines = $logText -split "`n"
        $tail = $lines | Select-Object -Last 25
        return ($tail -join "`n")
    } catch {
        return "No fue posible recuperar el log detallado: $($_.Exception.Message)"
    }
}

# ==============================================================================
# BUCLE DE MONITOREO
# ==============================================================================
do {
    Clear-Host
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host " [MONITOR] PIPELINE DEVSECOPS & CI (GITHUB ACTIONS)" -ForegroundColor Yellow
    Write-Host " Repositorio: $RepoOwner/$RepoName" -ForegroundColor White
    Write-Host "================================================================================" -ForegroundColor Cyan

    $run = Get-WorkflowRun -TargetRunId $RunId
    if (-not $run) {
        Write-Host "No se encontraron ejecuciones activas en GitHub Actions." -ForegroundColor Red
        break
    }

    $runIdToUse = $run.id
    $statusColor = switch ($run.status) {
        "completed" { "Green" }
        "in_progress" { "Yellow" }
        "queued" { "Gray" }
        default { "White" }
    }

    $conclusionColor = switch ($run.conclusion) {
        "success" { "Green" }
        "failure" { "Red" }
        "cancelled" { "Gray" }
        default { "Yellow" }
    }

    Write-Host ""
    Write-Host ">> Ejecucion: #$($run.run_number) - $($run.name)" -ForegroundColor White -NoNewline
    Write-Host " [ID: $($run.id)]" -ForegroundColor Gray
    Write-Host "   Commit: $($run.head_commit.message) ($($run.head_sha.Substring(0,7)))" -ForegroundColor DarkGray
    Write-Host "   Rama: $($run.head_branch) | Evento: $($run.event)" -ForegroundColor DarkGray
    Write-Host "   Estado: " -NoNewline
    Write-Host "$($run.status.ToUpper())" -ForegroundColor $statusColor -NoNewline
    if ($run.conclusion) {
        Write-Host " | Conclusion: " -NoNewline
        Write-Host "$($run.conclusion.ToUpper())" -ForegroundColor $conclusionColor
    } else {
        Write-Host ""
    }
    Write-Host "   URL: $($run.html_url)" -ForegroundColor DarkCyan
    Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Gray

    # Consultar jobs individuales
    $jobs = Get-RunJobs -TargetRunId $runIdToUse
    Write-Host " DETALLE DE TRABAJOS (JOBS):" -ForegroundColor Yellow

    $hasFailures = $false
    $failedJobsList = @()

    foreach ($j in $jobs) {
        $icon = switch ($j.conclusion) {
            "success" { " [OK]   " }
            "failure" { " [FAIL] " }
            "cancelled" { " [CANC] " }
            default {
                if ($j.status -eq "in_progress") { " [RUN]  " }
                else { " [WAIT] " }
            }
        }
        $jobColor = switch ($j.conclusion) {
            "success" { "Green" }
            "failure" { "Red" }
            default {
                if ($j.status -eq "in_progress") { "Yellow" }
                else { "Gray" }
            }
        }

        Write-Host "$icon" -ForegroundColor $jobColor -NoNewline
        Write-Host "$($j.name)" -ForegroundColor White -NoNewline
        Write-Host " - $($j.status)" -ForegroundColor Gray -NoNewline
        if ($j.conclusion) {
            Write-Host " ($($j.conclusion))" -ForegroundColor $jobColor
        } else {
            Write-Host ""
        }

        if ($j.conclusion -eq "failure") {
            $hasFailures = $true
            $failedJobsList += $j
        }
    }

    # Diagnostico de fallas
    if ($hasFailures -and ($Diagnose -or -not $Watch)) {
        Write-Host ""
        Write-Host "================================================================================" -ForegroundColor Red
        Write-Host " [DIAGNOSTICO] LOGS DE JOBS FALLIDOS" -ForegroundColor Red
        Write-Host "================================================================================" -ForegroundColor Red

        foreach ($fj in $failedJobsList) {
            Write-Host ""
            Write-Host ">> FALLO EN: $($fj.name)" -ForegroundColor Yellow
            foreach ($st in $fj.steps) {
                if ($st.conclusion -eq "failure") {
                    Write-Host "   Paso afectado: $($st.name)" -ForegroundColor Red
                }
            }
            Write-Host "   --- Fragmento de log ---" -ForegroundColor DarkGray
            $tailLogs = Get-JobFailedLogs -JobId $fj.id
            Write-Host $tailLogs -ForegroundColor DarkCyan
        }
    }

    if ($Watch) {
        if ($run.status -eq "completed") {
            Write-Host ""
            if ($run.conclusion -eq "success") {
                Write-Host ">> EXITO: Todos los jobs finalizaron correctamente." -ForegroundColor Green
            } else {
                Write-Host ">> ATENCION: La ejecucion finalizo con fallos." -ForegroundColor Red
            }
            break
        }
        Write-Host ""
        Write-Host "Esperando $IntervalSec s para refrescar... (Ctrl+C para salir)" -ForegroundColor DarkGray
        Start-Sleep -Seconds $IntervalSec
    }
} while ($Watch)
