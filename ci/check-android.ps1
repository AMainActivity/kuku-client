param(
    [string]$ContractDirectory,
    [switch]$Offline,
    [switch]$SkipContractTests
)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $gradleArgs=@(':androidApp:assembleDebug',':androidApp:assembleRelease',':androidApp:bundleRelease',
        'testAndroidHostTest','compileCommonMainKotlinMetadata',':androidApp:lintDebug',':androidApp:lintRelease',
        '--console=plain')
    if($ContractDirectory) {$gradleArgs += "-PcontractDirectory=$ContractDirectory"}
    if($SkipContractTests) {
        if($ContractDirectory) {throw "Use either -SkipContractTests or -ContractDirectory, not both."}
        Write-Warning "Published server contract tests are excluded; client tests and build checks remain enabled."
        $gradleArgs += '-PincludeContractTests=false'
    }
    if($Offline) {$gradleArgs += '--offline'}
    # Gradle compiler warnings use stderr; only its exit code determines success.
    $ErrorActionPreference='Continue'
    & .\gradlew.bat @gradleArgs
    $result=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($result -ne 0) {throw "Gradle validation failed: $result"}
} finally {Pop-Location}
