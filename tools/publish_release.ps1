param(
    [string]$Version,
    [string]$Artifacts = '',
    [string]$Repository = 'AlpheusWorld/AWLauncher-releases',
    [string]$Notes,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonExecutable = 'python',
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Provide a stable three-part release version' }
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid public release repository' }
if (-not $JavaHome) { throw 'Set JAVA_HOME to JDK 21' }
if (-not $Artifacts) { $Artifacts = Join-Path $projectRoot 'build/compose/binaries/main' }
$msi = Join-Path $Artifacts "msi/AWLauncher-$Version.msi"
$exe = Join-Path $Artifacts "exe/AWLauncher-$Version.exe"
foreach ($file in @($msi, $exe, $Notes)) { if (-not $file -or -not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'An installer or release notes file is missing' } }
$output = Join-Path $projectRoot "build/publication/v$Version"
[IO.Directory]::CreateDirectory($output) | Out-Null
Copy-Item -LiteralPath $msi -Destination (Join-Path $output 'AWLauncher.msi') -Force
Copy-Item -LiteralPath $exe -Destination (Join-Path $output 'AWLauncher-Setup.exe') -Force
$manifestPath = Join-Path $output 'update.json'
$tag = "v$Version"
$base = "https://github.com/$Repository/releases/download/$tag"
$manifest = [ordered]@{ version=$Version; url="$base/AWLauncher.msi"; sha256=(Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash.ToLowerInvariant();
    size=(Get-Item -LiteralPath $msi).Length; signatureUrl="$base/update.json.sig" } | ConvertTo-Json
[IO.File]::WriteAllText($manifestPath, $manifest + "`n", [Text.UTF8Encoding]::new($false))
$previousKey = $env:AW_UPDATE_SIGNING_PRIVATE_KEY
$protectedBytes = $null
try {
    if (-not $env:AW_UPDATE_SIGNING_PRIVATE_KEY) {
        Add-Type -AssemblyName System.Security
        $store = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.aw-release-signing/ed25519-key.dpapi.json'
        $record = Get-Content -LiteralPath $store -Raw | ConvertFrom-Json
        $protectedBytes = [Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($record.protectedKey), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $env:AW_UPDATE_SIGNING_PRIVATE_KEY = [Convert]::ToBase64String($protectedBytes)
    }
    & (Join-Path $JavaHome 'bin/java.exe') (Join-Path $PSScriptRoot 'SignUpdate.java') sign $manifestPath "$manifestPath.sig"
    if ($LASTEXITCODE -ne 0) { throw 'Manifest signing failed' }
} finally {
    $env:AW_UPDATE_SIGNING_PRIVATE_KEY = $previousKey
    if ($protectedBytes) { [Array]::Clear($protectedBytes,0,$protectedBytes.Length) }
}
& (Join-Path $JavaHome 'bin/java.exe') (Join-Path $PSScriptRoot 'SignUpdate.java') verify $manifestPath (Join-Path $projectRoot 'src/main/resources/update/public-key.txt')
if ($LASTEXITCODE -ne 0) { throw 'Manifest does not match the pinned public key' }
$hashes = @('AWLauncher-Setup.exe','AWLauncher.msi','update.json','update.json.sig') | ForEach-Object {
    (Get-FileHash -LiteralPath (Join-Path $output $_) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_
}
[IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS.txt'), ($hashes -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
$companionCatalog = Join-Path $projectRoot 'src/main/resources/companion/catalog.json'
$companionAssets = @()
foreach ($companion in (Get-Content -LiteralPath $companionCatalog -Raw | ConvertFrom-Json)) {
    $name = ([Uri]$companion.url).Segments[-1]
    $jar = Join-Path $projectRoot "client-mod/build/libs/$name"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing client companion: $name" }
    if ((Get-Item -LiteralPath $jar).Length -ne $companion.size -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $companion.sha256) {
        throw "Client companion differs from the pinned launcher catalog: $name"
    }
    Copy-Item -LiteralPath $jar -Destination (Join-Path $output $name) -Force
    $companionAssets += $name
}
foreach ($name in $companionAssets) {
    $hashes += (Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $name
}
[IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS.txt'), ($hashes -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
if ($PrepareOnly) { Write-Output "Prepared and verified $tag at $output"; return }
$files=@('AWLauncher-Setup.exe','AWLauncher.msi','update.json','update.json.sig','SHA256SUMS.txt','AWLauncher-third-party-sources.zip','OpenJDK-sources.part01','OpenJDK-sources.part02','OpenJDK-SOURCES.md') + $companionAssets
foreach ($name in $files) { if (-not (Test-Path -LiteralPath (Join-Path $output $name) -PathType Leaf)) { throw "A required public release asset is missing: $name" } }

# Credentials stay in memory and are only sent to GitHub's API/upload hosts.
if ($env:AW_PUBLIC_RELEASE_TOKEN) { $token = $env:AW_PUBLIC_RELEASE_TOKEN }
elseif ($env:GITHUB_ACTIONS -eq 'true' -and $env:GITHUB_REPOSITORY -eq $Repository -and $env:GH_TOKEN) { $token = $env:GH_TOKEN }
else {
    $savedInteractive = $env:GCM_INTERACTIVE; $savedPrompt = $env:GIT_TERMINAL_PROMPT
    try {
        $env:GCM_INTERACTIVE='never'; $env:GIT_TERMINAL_PROMPT='0'
        $lines = "protocol=https`nhost=github.com`n`n" | git credential fill
        if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication is unavailable' }
        $token = ($lines | Where-Object { $_.StartsWith('password=') } | Select-Object -First 1).Substring(9)
    } finally { $env:GCM_INTERACTIVE=$savedInteractive; $env:GIT_TERMINAL_PROMPT=$savedPrompt; $lines=$null }
}
$headers=@{ Authorization="Bearer $token"; Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'; 'User-Agent'='AWLauncher-release-publisher' }
try {
    $repositoryInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository" -Headers $headers
    if ($repositoryInfo.private) { throw 'Auto-update assets must be published in a public repository' }
    $body = @{ tag_name=$tag; target_commitish='main'; name="AWLauncher $Version"; body=(Get-Content -LiteralPath $Notes -Raw);
        draft=$true; prerelease=$false } | ConvertTo-Json
    $existing=Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases?per_page=100" -Headers $headers -TimeoutSec 30
    $release=$existing | Where-Object { $_.tag_name -eq $tag } | Select-Object -First 1
    if($release -and -not $release.draft) { throw 'This release is already public; do not replace its assets' }
    if(-not $release) { $release=Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repository/releases" -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 30 }
    foreach ($name in $files) {
        $file=Join-Path $output $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "A required public release asset is missing: $name" }
        $present=$release.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if($present) {
            $expectedDigest='sha256:'+(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
            if($present.size -ne (Get-Item -LiteralPath $file).Length -or ($present.digest -and $present.digest -ne $expectedDigest)) { throw "An existing draft asset differs: $name" }
            Write-Output "Retained verified $name"; continue
        }
        $uploadUrl="https://uploads.github.com/repos/$Repository/releases/$($release.id)/assets?name=$([Uri]::EscapeDataString($name))"
        $info=[Diagnostics.ProcessStartInfo]::new($PythonExecutable)
        $info.ArgumentList.Add((Join-Path $PSScriptRoot 'upload_release_asset.py'))
        $info.UseShellExecute=$false; $info.CreateNoWindow=$true
        $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
        $upload=[Diagnostics.Process]::Start($info)
        $config=@{url=$uploadUrl;file=$file;token=$token} | ConvertTo-Json -Compress
        $upload.StandardInput.Write($config); $upload.StandardInput.Close(); $config=$null
        $uploadedBody=$upload.StandardOutput.ReadToEnd(); $uploadError=$upload.StandardError.ReadToEnd(); $upload.WaitForExit()
        if($upload.ExitCode -ne 0) { throw "GitHub asset upload failed for $name`: $uploadError" }
        $uploaded=$uploadedBody | ConvertFrom-Json
        if($uploaded.size -ne (Get-Item -LiteralPath $file).Length) { throw "GitHub asset upload size mismatch: $name" }
        $upload.Dispose()
        Write-Output "Uploaded $name"
    }
    $release=Invoke-RestMethod -Method Patch -Uri "https://api.github.com/repos/$Repository/releases/$($release.id)" -Headers $headers -ContentType 'application/json' -Body (@{ draft=$false; make_latest='true' } | ConvertTo-Json)
    Write-Output "Published $($release.html_url)"
} finally { $headers=$null; $token=$null }
