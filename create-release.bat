@echo off
REM ==========================================
REM KILO v3.5.0 Release Creator (Windows Batch)
REM ==========================================
REM Usage: create-release.bat YOUR_GITHUB_TOKEN
REM ==========================================

if "%1"=="" (
    echo.
    echo ERROR: GitHub token required!
    echo.
    echo Usage: create-release.bat ^<github_token^>
    echo.
    echo Get a token at: https://github.com/settings/tokens
    echo Scope needed: repo
    echo.
    pause
    exit /b 1
)

set TOKEN=%1
set OWNER=KineticFusion
set REPO=Kilo-An-Android-Auto-Music-Player
set TAG=v3.5.0

echo Creating release %TAG% for %OWNER%/%REPO%...

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$Token='%TOKEN%'; $Owner='%OWNER%'; $Repo='%REPO%'; $Tag='%TAG%'; ^
    $releaseTitle=Get-Content 'GITHUB_RELEASE_TITLE.txt' -Raw; ^
    $releaseNotes=Get-Content 'RELEASE_NOTES_v3.5.0.md' -Raw; ^
    $headers=@{Authorization='token '+$Token; Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'}; ^
    $body=@{tag_name=$Tag; target_commitish='main'; name=$releaseTitle; body=$releaseNotes; draft=$false; prerelease=$false} | ConvertTo-Json -Depth 10; ^
    try { ^
        $r=Invoke-RestMethod -Method Post -Uri ('https://api.github.com/repos/{0}/{1}/releases' -f $Owner,$Repo) -Headers $headers -Body $body -ContentType 'application/json'; ^
        Write-Host 'SUCCESS: Release created at' $r.html_url -ForegroundColor Green; ^
        if (Test-Path 'app/build/outputs/apk/release/app-release.apk') { ^
            $uploadUrl=$r.upload_url -replace '\{.*\}',''; ^
            $fh=Invoke-RestMethod -Method Post -Uri ($uploadUrl+'?name=Kilo-3.5.0.apk') -Headers @{Authorization='token '+$Token; Accept='application/vnd.github+json'; 'Content-Type'='application/octet-stream'} -InFile 'app/build/outputs/apk/release/app-release.apk'; ^
            Write-Host 'SUCCESS: APK uploaded' -ForegroundColor Green; ^
        } else { Write-Host 'WARNING: APK not found, skipping upload' -ForegroundColor Yellow } ^
    } catch { ^
        Write-Host 'ERROR:' $_.Exception.Message -ForegroundColor Red; ^
        if ($_.Exception.Response) { $s=$_.Exception.Response.GetResponseStream(); $rd=New-Object System.IO.StreamReader($s); Write-Host $rd.ReadToEnd() } ^
    }"

echo.
echo Done!
pause