param(
    [ValidateSet("dev", "test", "prod")]
    [string]$Profile = "dev"
)

$keanDir = $PSScriptRoot
$root = Split-Path -Parent $keanDir

function Import-EnvFile([string]$path, [bool]$override) {
    if (-not (Test-Path $path)) {
        return
    }
    Get-Content $path | ForEach-Object {
        if ($_ -match "^\s*#" -or $_ -notmatch "=") { return }
        $k, $v = $_.Split("=", 2)
        $k = $k.Trim()
        $v = $v.Trim()
        if (-not $k -or -not $v) { return }
        if (-not $override -and [Environment]::GetEnvironmentVariable($k)) { return }
        Set-Item -Path "Env:$k" -Value $v
    }
}

Import-EnvFile (Join-Path $root ".env") $false
Import-EnvFile (Join-Path $root ".env.$Profile") $true
Import-EnvFile (Join-Path $keanDir ".env") $false
Import-EnvFile (Join-Path $keanDir ".env.$Profile") $true

$env:SPRING_PROFILES_ACTIVE = $Profile
Set-Location $keanDir
mvn -DskipTests spring-boot:run "-Dspring-boot.run.profiles=$Profile"
