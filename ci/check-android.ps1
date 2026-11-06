param(
    [string]$ContractDirectory,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $gradleArgs=@(':androidApp:assembleDebug',':androidApp:assembleRelease',':androidApp:bundleRelease',
        'testAndroidHostTest','compileCommonMainKotlinMetadata',':androidApp:lintDebug',':androidApp:lintRelease',
        '--console=plain')
    if($ContractDirectory) {$gradleArgs += "-PcontractDirectory=$ContractDirectory"}
    if($Offline) {$gradleArgs += '--offline'}
    # Gradle compiler warnings use stderr; only its exit code determines success.
    $ErrorActionPreference='Continue'
    & .\gradlew.bat @gradleArgs
    $result=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($result -ne 0) {throw "Gradle validation failed: $result"}
} finally {Pop-Location}
