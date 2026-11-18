<#
.SYNOPSIS
    Creates a GitHub release for KILO v3.5.0
.DESCRIPTION
    Uses GitHub REST API to create a release with the prepared release notes.
    Requires a GitHub Personal Access Token with 'repo' scope.
.NOTES
    Run: .\create-release.ps1 -Token "ghp_xxxxxxxxxxxx"
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$Token,

    [string]$Owner = "KineticFusion",
    [string]$Repo  = "Kilo-An-Android-Auto-Music-Player",
    [string]$Tag   = "v3.5.0",
    [string]$TargetBranch = "main"
)

$releaseTitle = Get-Content "GITHUB_RELEASE_TITLE.txt" -Raw
$releaseNotes = Get-Content "RELEASE_NOTES_v3.5.0.md" -Raw

$headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}

$body = @{
    tag_name         = $Tag
    target_commitish = $TargetBranch
    name             = $releaseTitle
    body             = $releaseNotes
    draft            = $false
    prerelease       = $false
    generate_release_notes = $false
} | ConvertTo-Json -Depth 10

Write-Host "Creating release $Tag for $Owner/$Repo..." -ForegroundColor Cyan

try {
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "https://api.github.com/repos/$Owner/$Repo/releases" `
        -Headers $headers `
        -Body $body `
        -ContentType "application/json"

    Write-Host "✅ Release created successfully!" -ForegroundColor Green
    Write-Host "URL: $($response.html_url)" -ForegroundColor Cyan
    Write-Host "Upload URL: $($response.upload_url)" -ForegroundColor Cyan
}
catch {
    Write-Host "❌ Failed to create release:" -ForegroundColor Red
    Write-Host $_.Exception.Response.StatusCode.value__
    $stream = $_.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    $errorBody = $reader.ReadToEnd()
    Write-Host $errorBody
    exit 1
}

# Optionally upload APK if it exists
$apkPath = "app/build/outputs/apk/release/app-release.apk"
if (Test-Path $apkPath) {
    Write-Host "Uploading APK..." -ForegroundColor Cyan
    $uploadUrl = $response.upload_url -replace '\{.*\}', ''
    $fileName = "Kilo-3.5.0.apk"

    $uploadHeaders = @{
        "Authorization" = "token $Token"
        "Accept"        = "application/vnd.github+json"
        "Content-Type"  = "application/octet-stream"
    }

    try {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$uploadUrl?name=$fileName" `
            -Headers $uploadHeaders `
            -InFile $apkPath
        Write-Host "✅ APK uploaded!" -ForegroundColor Green
    }
    catch {
        Write-Host "⚠️ APK upload failed (release created but no artifact):" -ForegroundColor Yellow
        Write-Host $_.Exception.Message
    }
}
else {
    Write-Host "⚠️ APK not found at $apkPath - skipping artifact upload" -ForegroundColor Yellow
    Write-Host "Build release APK first: ./gradlew assembleRelease" -ForegroundColor Gray
}