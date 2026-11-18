<#
.SYNOPSIS
    Updates GitHub repository description, topics, and settings
.DESCRIPTION
    Uses GitHub REST API to update repo metadata for a better looking page.
.NOTES
    Run: .\update-repo.ps1 -Token "ghp_xxxxxxxxxxxx"
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$Token,

    [string]$Owner = "KineticFusion",
    [string]$Repo  = "Kilo-An-Android-Auto-Music-Player"
)

$headers = @{
    "Authorization" = "token $Token"
    "Accept"        = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}

# Repository description (shown at top of repo page)
$description = "🎵 Modern Android music player with native C++ crossfade engine, Spotify browsing, YouTube Music hybrid playback, and Android Auto support. Built with Kotlin, Compose, Media3, and NDK r27."

# Topics (shown as tags on repo page)
$topics = @(
    "android",
    "kotlin",
    "jetpack-compose",
    "material3",
    "media3",
    "exoplayer",
    "oboe",
    "ndk",
    "c++",
    "crossfade",
    "spotify",
    "youtube-music",
    "music-player",
    "android-auto",
    "audio",
    "educational-project"
)

# Update repository
$repoBody = @{
    description = $description
    homepage    = "https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player/releases"
    topics      = $topics
    has_issues  = $true
    has_projects = $false
    has_wiki    = $false
    has_discussions = $true
    allow_squash_merge = $true
    allow_merge_commit = $false
    allow_rebase_merge = $true
    delete_branch_on_merge = $true
    allow_auto_merge = $true
} | ConvertTo-Json -Depth 10

Write-Host "Updating repository metadata..." -ForegroundColor Cyan

try {
    $response = Invoke-RestMethod `
        -Method Patch `
        -Uri "https://api.github.com/repos/$Owner/$Repo" `
        -Headers $headers `
        -Body $repoBody `
        -ContentType "application/json"

    Write-Host "✅ Repository updated!" -ForegroundColor Green
    Write-Host "Description: $($response.description)"
    Write-Host "Topics: $($response.topics -join ', ')"
}
catch {
    Write-Host "❌ Failed to update repository:" -ForegroundColor Red
    $stream = $_.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    Write-Host $reader.ReadToEnd()
    exit 1
}

# Enable GitHub Pages for docs (if docs folder exists)
$pagesBody = @{
    source = @{
        branch = "main"
        path   = "/docs"
    }
} | ConvertTo-Json -Depth 10

Write-Host "Checking GitHub Pages..." -ForegroundColor Cyan
try {
    $pagesResponse = Invoke-RestMethod `
        -Method Post `
        -Uri "https://api.github.com/repos/$Owner/$Repo/pages" `
        -Headers $headers `
        -Body $pagesBody `
        -ContentType "application/json"
    Write-Host "✅ GitHub Pages enabled at: $($pagesResponse.html_url)" -ForegroundColor Green
}
catch {
    if ($_.Exception.Response.StatusCode -eq 422) {
        Write-Host "ℹ️ GitHub Pages already configured" -ForegroundColor Gray
    }
    else {
        Write-Host "⚠️ GitHub Pages setup:" -ForegroundColor Yellow
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        Write-Host $reader.ReadToEnd()
    }
}