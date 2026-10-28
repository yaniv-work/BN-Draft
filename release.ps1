<#
.SYNOPSIS
  Build a signed BN-Draft APK and publish it as the latest GitHub release.
  Installed copies pick it up via "Check for updates" (or on next launch).

.EXAMPLE
  .\release.ps1 -Notes "Fixed the thing"            # 0.0.1 -> 0.0.2
  .\release.ps1 -Bump minor -Notes "New feature"     # 0.0.2 -> 0.1.0
  .\release.ps1 -NoBump -Notes "First release"       # publish current version as-is
#>
param(
    [string]$Notes = "",
    [ValidateSet("patch", "minor", "major")][string]$Bump = "patch",
    [switch]$NoBump
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Invoke-Native([string]$What, [scriptblock]$Cmd) {
    & $Cmd
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

if (-not (Test-Path keystore.properties)) { throw "keystore.properties missing - can't sign an upgradeable APK." }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }

# --- version ---
$props = @{}
Get-Content version.properties | Where-Object { $_ -match "=" } | ForEach-Object {
    $k, $v = $_ -split "=", 2; $props[$k.Trim()] = $v.Trim()
}
$code = [int]$props.versionCode
$name = $props.versionName
if (-not $NoBump) {
    $parts = $name.Split(".") | ForEach-Object { [int]$_ }
    switch ($Bump) {
        # Parenthesize the "+ 1": in PowerShell the comma binds tighter than +.
        "major" { $parts = @(($parts[0] + 1), 0, 0) }
        "minor" { $parts = @($parts[0], ($parts[1] + 1), 0) }
        "patch" { $parts = @($parts[0], $parts[1], ($parts[2] + 1)) }
    }
    $code += 1
    $name = $parts -join "."
    [IO.File]::WriteAllText("$PSScriptRoot\version.properties", "versionCode=$code`nversionName=$name`n")
}
$tag = "v$name"
Write-Host "Releasing $tag (build $code)" -ForegroundColor Cyan

# --- build ---
Invoke-Native "Gradle build" { .\gradlew.bat assembleRelease --console=plain -q }

New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item app\build\outputs\apk\release\app-release.apk dist\BN-Draft.apk -Force
$json = [ordered]@{ versionCode = $code; versionName = $name; notes = $Notes } | ConvertTo-Json
[IO.File]::WriteAllText("$PSScriptRoot\dist\version.json", $json, (New-Object Text.UTF8Encoding $false))

# --- publish ---
Invoke-Native "git add" { git add -A }
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { Invoke-Native "git commit" { git commit -q -m "Release $tag" } }
Invoke-Native "git tag" { git tag $tag }
Invoke-Native "git push" { git push -q origin HEAD $tag }

$relNotes = if ($Notes) { $Notes } else { "Release $tag" }
Invoke-Native "gh release" { gh release create $tag dist\BN-Draft.apk dist\version.json --title $tag --notes $relNotes }

Write-Host "Published $tag. Phones running BN-Draft will offer the update." -ForegroundColor Green
