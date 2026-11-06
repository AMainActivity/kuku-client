param(
    [Parameter(Mandatory)][ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+$')][string]$Tag,
    [Parameter(Mandatory)][ValidateRange(1, 2100000000)][int]$VersionCode
)
$ErrorActionPreference = 'Stop'
$required = @('RELEASE_KEYSTORE_BASE64', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD')
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing GitHub Actions secret: $name. Configure the required repository Actions secrets."
    }
}
if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) { throw 'This script requires a GitHub Actions runner.' }
$version = $Tag.Substring(1)
$keyPath = Join-Path $env:RUNNER_TEMP ("kuku-release-" + [guid]::NewGuid().ToString('N') + '.jks')
$previousStoreFile = $env:RELEASE_STORE_FILE
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $tagCommit = & git rev-parse --verify "refs/tags/$Tag^{commit}"
    if ($LASTEXITCODE -ne 0) { throw 'Release requires an existing Git tag.' }
    $headCommit = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $headCommit -ne $tagCommit) { throw 'Checkout does not match the release tag.' }
    $notesPath = Join-Path (Get-Location) "release-notes/$version.md"
    if (!(Test-Path -LiteralPath $notesPath)) { throw "Missing release notes for $Tag." }
    $releaseNotes = [IO.File]::ReadAllText($notesPath).Trim()
    if ([string]::IsNullOrWhiteSpace($releaseNotes)) { throw "Release notes for $Tag are empty." }
    try { $keyBytes = [Convert]::FromBase64String($env:RELEASE_KEYSTORE_BASE64) }
    catch { throw 'RELEASE_KEYSTORE_BASE64 must contain a Base64-encoded keystore.' }
    [IO.File]::WriteAllBytes($keyPath, $keyBytes)
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    $env:RELEASE_STORE_FILE = $keyPath
    $env:RELEASE_KEYSTORE_BASE64 = $null
    Write-Warning 'Private published-contract tests are excluded. Client tests, lint and release builds are required.'
    $gradleArgs = @(':androidApp:assembleRelease', ':androidApp:bundleRelease',
        'testAndroidHostTest', ':androidApp:lintRelease', '-PincludeContractTests=false',
        "-PappVersionName=$version", "-PappVersionCode=$VersionCode", '--no-configuration-cache', '--console=plain')
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat @gradleArgs
    $result = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($result -ne 0) { throw "Release validation failed: $result" }
    $apk = Join-Path (Get-Location) 'androidApp/build/outputs/apk/release/androidApp-release.apk'
    $aab = Join-Path (Get-Location) 'androidApp/build/outputs/bundle/release/androidApp-release.aab'
    if (!(Test-Path -LiteralPath $apk) -or !(Test-Path -LiteralPath $aab)) { throw 'Signed release outputs are missing.' }
    $signer = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Filter apksigner.bat -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($null -eq $signer) { throw 'Android apksigner was not found.' }
    & $signer.FullName verify $apk
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed.' }
    $output = Join-Path (Get-Location) 'androidApp/build/release-distribution'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $apkName = "kuku-$version.apk"
    $aabName = "kuku-$version.aab"
    Copy-Item -LiteralPath $apk -Destination (Join-Path $output $apkName)
    Copy-Item -LiteralPath $aab -Destination (Join-Path $output $aabName)
    $checksums = foreach ($name in @($apkName, $aabName)) {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $output $name)).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    [IO.File]::WriteAllLines((Join-Path $output 'SHA256SUMS.txt'), $checksums)
    $notes = @($releaseNotes, '', '---', '', "Android $version (versionCode $VersionCode).", '', "Commit: $headCommit", '',
        'Signed APK for direct installation; AAB for store upload.',
        'Client host tests and release lint passed. Private server-contract tests were not run.',
        'This release workflow does not verify iOS or device scenarios.')
    [IO.File]::WriteAllLines((Join-Path $output 'release-notes.md'), $notes)
} finally {
    $env:RELEASE_STORE_FILE = $previousStoreFile
    if (Test-Path -LiteralPath $keyPath) { Remove-Item -LiteralPath $keyPath -Force }
    Pop-Location
}
