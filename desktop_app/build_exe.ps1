# Script de Compilación a Ejecutable .exe para El Canario Chillador (Desktop)
# Genera un binario autónomo para Windows x64 utilizando PyInstaller

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " COMPILANDO EJECUTABLE DESKTOP: CANARIO CHILLADOR " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Verificar PyInstaller
$pyinstallerCmd = Get-Command pyinstaller -ErrorAction SilentlyContinue
if (-not $pyinstallerCmd) {
    $pyinstallerExec = "python"
    $pyinstallerArgs = @("-m", "PyInstaller")
} else {
    $pyinstallerExec = "pyinstaller"
    $pyinstallerArgs = @()
}

# 2. Rutas de salida y dependencias
$DistDir = Join-Path $ScriptDir "dist"
$BuildDir = Join-Path $ScriptDir "build"
$IconPath = Join-Path $ScriptDir "canario.ico"
$MainScript = Join-Path $ScriptDir "canario_app.py"
$TclData = Join-Path $ScriptDir "tcl_data"
$TkData = Join-Path $ScriptDir "tk_data"

# 3. Asegurar extracción de Tcl/Tk 9.0 para Python 3.14
if (-not (Test-Path (Join-Path $TclData "init.tcl")) -or -not (Test-Path (Join-Path $TkData "tk.tcl"))) {
    Write-Host "[1/4] Extrayendo datos de Tcl/Tk 9.0 desde DLLs para compatibilidad PyInstaller..." -ForegroundColor Yellow
    python -c "
import zipfile, os
base_dir = r'$ScriptDir'
tcl_out = os.path.join(base_dir, 'tcl_data')
tk_out = os.path.join(base_dir, 'tk_data')
os.makedirs(tcl_out, exist_ok=True)
os.makedirs(tk_out, exist_ok=True)
with zipfile.ZipFile('C:/Python314/DLLs/tcl90.dll') as z:
    for name in z.namelist():
        if name.startswith('tcl_library/'):
            rel = name[len('tcl_library/'):]
            if rel:
                p = os.path.join(tcl_out, rel)
                if name.endswith('/'): os.makedirs(p, exist_ok=True)
                else:
                    os.makedirs(os.path.dirname(p), exist_ok=True)
                    with open(p, 'wb') as f: f.write(z.read(name))
with zipfile.ZipFile('C:/Python314/DLLs/tcl9tk90.dll') as z:
    for name in z.namelist():
        if name.startswith('tk_library/'):
            rel = name[len('tk_library/'):]
            if rel:
                p = os.path.join(tk_out, rel)
                if name.endswith('/'): os.makedirs(p, exist_ok=True)
                else:
                    os.makedirs(os.path.dirname(p), exist_ok=True)
                    with open(p, 'wb') as f: f.write(z.read(name))
print('[OK] Datos de Tcl/Tk 9.0 extraidos correctamente.')
"
}

Write-Host "[2/4] Limpiando directorios previos de compilación..." -ForegroundColor DarkGray
if (Test-Path $BuildDir) { Remove-Item -Path $BuildDir -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host "[3/4] Empaquetando aplicación con PyInstaller (--onefile --windowed)..." -ForegroundColor Yellow
$cmdArgs = $pyinstallerArgs + @(
    "--noconfirm",
    "--clean",
    "--name", "CanarioChillador-Desktop",
    "--onefile",
    "--windowed",
    "--icon", $IconPath,
    "--add-data", "$IconPath;.",
    "--add-data", "$TclData;_tcl_data",
    "--add-data", "$TkData;_tk_data",
    "--distpath", $DistDir,
    "--workpath", $BuildDir,
    "--specpath", $ScriptDir,
    $MainScript
)

& $pyinstallerExec $cmdArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[ERROR] Falló la compilación con PyInstaller (código de salida $LASTEXITCODE)." -ForegroundColor Red
    exit $LASTEXITCODE
}

# 4. Verificación del binario final
$ExePath = Join-Path $DistDir "CanarioChillador-Desktop.exe"
if (Test-Path $ExePath) {
    $item = Get-Item $ExePath
    $sizeMb = [math]::Round($item.Length / 1MB, 2)
    Write-Host "`n==================================================" -ForegroundColor Green
    Write-Host " [EXITO] Ejecutable standalone generado y reparado:" -ForegroundColor Green
    Write-Host " $ExePath ($sizeMb MB)" -ForegroundColor White
    Write-Host "==================================================" -ForegroundColor Green
} else {
    Write-Host "`n[ERROR] No se encontró el archivo $ExePath tras compilar." -ForegroundColor Red
    exit 1
}
