# Build a debug APK without Gradle, using only Android SDK build-tools + a JDK.
# Usage:  powershell -ExecutionPolicy Bypass -File .\build_apk.ps1
# Output: .\quizhome-debug.apk

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
# aapt2 对非 ASCII 路径支持不好，先把源码复制到临时目录再构建。
$tempRoot = $env:TEMP
if (-not $tempRoot) { $tempRoot = Join-Path $env:LOCALAPPDATA "Temp" }
$stageDir = Join-Path $tempRoot "quizlauncher_build"
$srcRoot = Join-Path $stageDir "src"
$workDir = Join-Path $stageDir "work"
$pkg = "com.family.quizlauncher"

function Find-Sdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "H:\Android\Sdk", "$env:LOCALAPPDATA\Android\Sdk")
    foreach ($path in $candidates) {
        if ($path -and (Test-Path (Join-Path $path "platform-tools"))) { return $path }
    }
    throw "Android SDK not found. Set ANDROID_HOME."
}

function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) { return $env:JAVA_HOME }
    $candidates = @(
        "F:\Program Files\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "C:\Users\<你的用户名>\.jdks\ms-17.0.17",
        "C:\Users\<你的用户名>\.jdks\corretto-1.8.0_462"
    )
    foreach ($path in $candidates) {
        if (Test-Path (Join-Path $path "bin\javac.exe")) { return $path }
    }
    $jdkRoot = "$env:USERPROFILE\.jdks"
    if (Test-Path $jdkRoot) {
        $all = Get-ChildItem $jdkRoot -Directory | Sort-Object Name -Descending
        foreach ($item in $all) {
            if (Test-Path (Join-Path $item.FullName "bin\javac.exe")) { return $item.FullName }
        }
    }
    throw "JDK not found. Install JDK 17+ or set JAVA_HOME."
}

function Find-BuildTools {
    param([string]$Sdk)
    $dir = Join-Path $Sdk "build-tools"
    $preferred = Join-Path $dir "35.0.1"
    if (Test-Path (Join-Path $preferred "aapt2.exe")) { return $preferred }
    $all = Get-ChildItem $dir -Directory | Sort-Object Name -Descending
    foreach ($item in $all) {
        if (Test-Path (Join-Path $item.FullName "aapt2.exe")) { return $item.FullName }
    }
    throw "aapt2 not found in build-tools."
}

function Find-PlatformJar {
    param([string]$Sdk)
    $platforms = Join-Path $Sdk "platforms"
    $preferred = Join-Path $platforms "android-33\android.jar"
    if (Test-Path $preferred) { return $preferred }
    $all = Get-ChildItem $platforms -Directory | Sort-Object Name -Descending
    foreach ($item in $all) {
        $jar = Join-Path $item.FullName "android.jar"
        if (Test-Path $jar) { return $jar }
    }
    throw "android.jar not found in platforms."
}

$sdk = Find-Sdk
$javaHome = Find-Jdk
$buildTools = Find-BuildTools -Sdk $sdk
$androidJar = Find-PlatformJar -Sdk $sdk
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$buildTools;$env:PATH"

Write-Host "SDK        : $sdk"
Write-Host "BuildTools : $buildTools"
Write-Host "JDK        : $javaHome"
Write-Host "android.jar: $androidJar"

if (Test-Path $stageDir) { Remove-Item $stageDir -Recurse -Force }
New-Item -ItemType Directory -Path $stageDir | Out-Null
Copy-Item (Join-Path $projectRoot "app\src\main") -Destination $srcRoot -Recurse
New-Item -ItemType Directory -Path $workDir | Out-Null
$genDir = Join-Path $workDir "gen"
$classesDir = Join-Path $workDir "classes"
$dexDir = Join-Path $workDir "dex"
New-Item -ItemType Directory -Path $genDir | Out-Null
New-Item -ItemType Directory -Path $classesDir | Out-Null
New-Item -ItemType Directory -Path $dexDir | Out-Null

# 1) Manifest: Gradle uses namespace, aapt2 needs the package attribute.
$manifestSrc = Join-Path $srcRoot "AndroidManifest.xml"
$manifestOut = Join-Path $workDir "AndroidManifest.xml"
$manifest = Get-Content $manifestSrc -Raw -Encoding UTF8
$manifest = $manifest -replace '<manifest xmlns:android', "<manifest package=`"$pkg`" android:versionCode=`"16`" android:versionName=`"3.3`" xmlns:android"
Set-Content -Path $manifestOut -Value $manifest -Encoding UTF8

# 2) Compile and link resources.
Write-Host "[1/6] aapt2 compile"
& (Join-Path $buildTools "aapt2.exe") compile --dir (Join-Path $srcRoot "res") -o (Join-Path $workDir "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
& (Join-Path $buildTools "aapt2.exe") link -o (Join-Path $workDir "base.apk") -I $androidJar --manifest $manifestOut -R (Join-Path $workDir "res.zip") -A (Join-Path $srcRoot "assets") --java $genDir --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 3) Compile Java.
Write-Host "[3/6] javac"
$javaFiles = Get-ChildItem (Join-Path $srcRoot "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rJava = Get-ChildItem $genDir -Recurse -Filter R.java | ForEach-Object { $_.FullName }
$javac = Join-Path $javaHome "bin\javac.exe"
& $javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $androidJar -d $classesDir $javaFiles $rJava
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 4) Dex.
Write-Host "[4/6] d8"
# Pack class files into one jar first: passing hundreds of paths exceeds the
# Windows command-line length limit.
$classesJar = Join-Path $workDir "classes.jar"
& (Join-Path $javaHome "bin\jar.exe") cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& (Join-Path $buildTools "d8.bat") --min-api 26 --lib $androidJar --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 5) Put classes.dex into the APK.
Write-Host "[5/6] package classes.dex"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unsigned = Join-Path $workDir "base.apk"
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $entry = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dexDir "classes.dex"), "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal)
    $entry | Out-Null
} finally {
    $zip.Dispose()
}

# 6) Align and sign.
Write-Host "[6/6] zipalign + apksigner"
$aligned = Join-Path $workDir "aligned.apk"
& (Join-Path $buildTools "zipalign.exe") -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# Use a persistent project keystore so later updates keep the same signature.
$keystore = Join-Path $projectRoot "debug.keystore"
if (-not (Test-Path $keystore)) {
    & (Join-Path $javaHome "bin\keytool.exe") -genkeypair -keystore $keystore -alias androiddebugkey -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$final = Join-Path $stageDir "quizhome-debug.apk"
& (Join-Path $buildTools "apksigner.bat") sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --min-sdk-version 26 --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& (Join-Path $buildTools "apksigner.bat") verify --print-certs $final
& (Join-Path $buildTools "aapt2.exe") dump badging $final | Select-String -Pattern "^package|^launchable-activity|sdkVersion|targetSdkVersion"

# Safety gate: this TV crashes system_server when a 4th HOME candidate exists.
# Never ship an APK that registers CATEGORY_HOME.
$manifestTree = & (Join-Path $buildTools "aapt2.exe") dump xmltree --file AndroidManifest.xml $final
$homeHit = $manifestTree | Select-String -Pattern "category.HOME"
if ($homeHit) {
    throw "SAFETY CHECK FAILED: CATEGORY_HOME found in AndroidManifest. Refusing to ship."
}
Write-Host "SAFETY CHECK OK: no android.intent.category.HOME in manifest"

$serviceHit = $manifestTree | Select-String -Pattern "QuizAccessibilityService"
if (-not $serviceHit) {
    throw "SAFETY CHECK FAILED: accessibility service missing from manifest."
}
Write-Host "SAFETY CHECK OK: accessibility service declared"

$deliver = Join-Path $projectRoot "quizhome-debug.apk"
Copy-Item $final $deliver -Force
Write-Host "BUILD OK: $deliver"
