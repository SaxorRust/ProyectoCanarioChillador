# ==============================================================================
# Script de Compilación Rápida Automatizada - El Canario Chillador (Android)
# Utiliza el Android SDK de U:\ANDROIDsdk y OpenJDK de U:\Android Studio\jbr
# ==============================================================================

$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$SdkDir = "U:\ANDROIDsdk"
$JavaHome = "U:\Android Studio\jbr"
$SevenZip = "U:\7-Zip\7z.exe"

$Aapt2 = "$SdkDir\build-tools\35.0.0\aapt2.exe"
$D8 = "$SdkDir\build-tools\35.0.0\d8.bat"
$Zipalign = "$SdkDir\build-tools\35.0.0\zipalign.exe"
$Apksigner = "$SdkDir\build-tools\35.0.0\apksigner.bat"
$AndroidJar = "$SdkDir\platforms\android-36\android.jar"
$Javac = "$JavaHome\bin\javac.exe"
$Keytool = "$JavaHome\bin\keytool.exe"

$env:JAVA_HOME = $JavaHome

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " COMPILANDO APP ANDROID: EL CANARIO CHILLADOR     " -ForegroundColor Yellow
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Preparar directorios de build
$BuildDir = "$ProjectRoot\app\build"
$ResCompiled = "$BuildDir\res_compiled"
$GenDir = "$BuildDir\gen"
$ClassesDir = "$BuildDir\classes"
$DexDir = "$BuildDir\dex"
$DistDir = "$ProjectRoot\dist"

New-Item -ItemType Directory -Force -Path $ResCompiled, $GenDir, $ClassesDir, $DexDir, $DistDir | Out-Null

# 2. Compilar recursos con AAPT2
Write-Host "[1/6] Compilando recursos XML e interfaces..." -ForegroundColor Green
& $Aapt2 compile --dir "$ProjectRoot\app\src\main\res" -o "$ResCompiled\res.zip"

# 3. Enlazar recursos y generar R.java
Write-Host "[2/6] Enlazando recursos y generando R.java..." -ForegroundColor Green
& $Aapt2 link -o "$BuildDir\app-base.apk" `
    -I $AndroidJar `
    --manifest "$ProjectRoot\app\src\main\AndroidManifest.xml" `
    --java $GenDir `
    --auto-add-overlay `
    "$ResCompiled\res.zip"

# 4. Compilar código Java con Javac
Write-Host "[3/6] Compilando fuentes Java (.java -> .class)..." -ForegroundColor Green
$JavaSources = (Get-ChildItem -Path "$ProjectRoot\app\src\main\java\com\canario\iot\*.java", "$GenDir\com\canario\iot\R.java").FullName
& $Javac -cp $AndroidJar -d $ClassesDir $JavaSources

# 5. Generar bytecode Dalvik/ART con D8
Write-Host "[4/6] Generando bytecode Dalvik (classes.dex) con D8..." -ForegroundColor Green
$CompiledClasses = (Get-ChildItem -Path $ClassesDir -Recurse -Filter "*.class").FullName
& $D8 --lib $AndroidJar --output $DexDir $CompiledClasses

# 6. Empaquetar classes.dex en el APK
Write-Host "[5/6] Empaquetando APK..." -ForegroundColor Green
Copy-Item "$BuildDir\app-base.apk" "$BuildDir\app-unaligned.apk" -Force
& $SevenZip a -tzip "$BuildDir\app-unaligned.apk" "$DexDir\classes.dex" | Out-Null

# 7. Alinear y Firmar APK
Write-Host "[6/6] Alineando con zipalign y firmando con apksigner..." -ForegroundColor Green
$FinalApk = "$DistDir\CanarioChillador-v1.0.apk"
& $Zipalign -f -p 4 "$BuildDir\app-unaligned.apk" $FinalApk

$Keystore = "$ProjectRoot\debug.keystore"
if (-not (Test-Path $Keystore)) {
    & $Keytool -genkey -v -keystore $Keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}

& $Apksigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android --out $FinalApk $FinalApk

# Verificar firma
& $Apksigner verify $FinalApk
Write-Host ""
Write-Host "==================================================" -ForegroundColor Green
Write-Host " [EXITO] APK generado y firmado correctamente:" -ForegroundColor Yellow
Write-Host " $FinalApk" -ForegroundColor White
Write-Host "==================================================" -ForegroundColor Green
