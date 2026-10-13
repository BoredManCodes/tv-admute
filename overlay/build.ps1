# Builds the overlay APK with the Android SDK command-line tools (no Gradle).
# Needs: JDK 17, Android SDK with build-tools and a platform (android-34 or newer).
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
$jar = Get-ChildItem "$sdk\platforms\android-3*\android.jar" | Sort-Object FullName | Select-Object -Last 1
if (-not $bt -or -not $jar) { throw "Android build-tools or platform jar not found under $sdk" }
$out = Join-Path $here "build"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

& "$($bt.FullName)\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& "$($bt.FullName)\aapt2.exe" link -o "$out\base.apk" -I $jar.FullName --manifest "$here\AndroidManifest.xml" `
    --min-sdk-version 26 --target-sdk-version 30 --version-code 2 --version-name 2.0 "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }

$src = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object FullName
$ErrorActionPreference = "Continue"   # javac notes go to stderr, which is not a failure
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp $jar.FullName -d "$out\classes" $src 2>&1 | Out-Host
$javacExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($javacExit) { throw "javac failed" }

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$($bt.FullName)\d8.bat" --lib $jar.FullName --release --output "$out\dex" $classes
if ($LASTEXITCODE) { throw "d8 failed" }

Copy-Item "$out\base.apk" "$out\unsigned.apk"
jar uf "$out\unsigned.apk" -C "$out\dex" classes.dex
if ($LASTEXITCODE) { throw "jar failed" }
& "$($bt.FullName)\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }

$ks = "$env:USERPROFILE\.android\debug.keystore"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}
& "$($bt.FullName)\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$out\tv-admute-overlay.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
Write-Host "Built $out\tv-admute-overlay.apk"

