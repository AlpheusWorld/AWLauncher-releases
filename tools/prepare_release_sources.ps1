param([Parameter(Mandatory=$true)][string]$Version,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid release version' }
if(-not $JavaHome) { throw 'Set JAVA_HOME' }
$root=Split-Path $PSScriptRoot -Parent
$output=Join-Path $root "build/publication/v$Version"
$cache=Join-Path $root 'build/publication/source-cache'
[IO.Directory]::CreateDirectory($output) | Out-Null
[IO.Directory]::CreateDirectory($cache) | Out-Null
$authArchive=Join-Path $cache 'MinecraftAuth-v5.0.2.zip'
if(-not (Test-Path -LiteralPath $authArchive)) { Invoke-WebRequest -Uri 'https://github.com/RaphiMC/MinecraftAuth/archive/refs/tags/v5.0.2.zip' -OutFile $authArchive }
$sourceFolder=Join-Path $output 'third-party-source'
[IO.Directory]::CreateDirectory($sourceFolder) | Out-Null
Copy-Item -LiteralPath $authArchive -Destination (Join-Path $sourceFolder 'MinecraftAuth-v5.0.2.zip') -Force
Copy-Item -LiteralPath (Join-Path $root 'branding/legal/GPL-3.0.txt') -Destination $sourceFolder -Force
Copy-Item -LiteralPath (Join-Path $root 'branding/legal/LGPL-3.0.txt') -Destination $sourceFolder -Force
$explanation=@'
# Corresponding sources

MinecraftAuth 5.0.2 is included as a separate, replaceable JAR under LGPL-3.0. Its unmodified source archive, including build scripts, is included here. Upstream: https://github.com/RaphiMC/MinecraftAuth/tree/v5.0.2

AWLauncher loads shared dependencies from its app directory. To use a modified LGPL component, rebuild the library using its upstream build instructions and replace its JAR while the launcher is closed. Preserve the filename referenced in app/AWLauncher.cfg. You may disable class-data sharing by adding -Xshare:off to that configuration when testing modified libraries. AWLauncher does not require third-party JARs to retain the original signature or hash at startup.

Full Eclipse Temurin/OpenJDK sources are provided as a separate asset in the same release. Notices and licenses for all runtime components are retained in the application image under legal and runtime/legal, and in the original JARs.
'@
[IO.File]::WriteAllText((Join-Path $sourceFolder 'README.md'),$explanation,[Text.UTF8Encoding]::new($false))
$librarySources = Join-Path $output 'AWLauncher-third-party-sources.zip'
if (-not (Test-Path -LiteralPath $librarySources -PathType Leaf)) {
    Compress-Archive -Path (Join-Path $sourceFolder '*') -DestinationPath $librarySources
}
$release=[IO.File]::ReadAllText((Join-Path $JavaHome 'release'))
$jdkVersion=[regex]::Match($release,'IMPLEMENTOR_VERSION="Temurin-([^"\r\n]+)"').Groups[1].Value
if(-not $jdkVersion) { throw 'Public release sources require the matching Eclipse Temurin runtime' }
$jdkTag=[Uri]::EscapeDataString('jdk-'+$jdkVersion)
$assets=(Invoke-RestMethod -Uri "https://api.github.com/repos/adoptium/temurin21-binaries/releases/tags/$jdkTag").assets
$sourceAsset=$assets | Where-Object { $_.name -match '^OpenJDK21U-jdk-sources_.+\.tar\.gz$' } | Select-Object -First 1
$checksumAsset=$assets | Where-Object { $_.name -eq $sourceAsset.name+'.sha256.txt' } | Select-Object -First 1
if(-not $sourceAsset -or -not $checksumAsset) { throw 'Matching OpenJDK source release is unavailable' }
$sourcePath=Join-Path $cache $sourceAsset.name
if(-not (Test-Path -LiteralPath $sourcePath)) { Invoke-WebRequest -Uri $sourceAsset.browser_download_url -OutFile $sourcePath }
$checksumResponse=Invoke-WebRequest -Uri $checksumAsset.browser_download_url
$checksumText=if($checksumResponse.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($checksumResponse.Content) } else { [string]$checksumResponse.Content }
$expected=($checksumText.Trim() -split '\s+')[0]
if((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash -ne $expected) { throw 'OpenJDK source checksum mismatch' }
Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $output 'OpenJDK-sources.tar.gz') -Force
$inputStream=[IO.File]::OpenRead($sourcePath)
try {
    $buffer=New-Object byte[] (1024*1024)
    foreach($part in @('OpenJDK-sources.part01','OpenJDK-sources.part02')) {
        $written=0L
        $partStream=[IO.File]::Create((Join-Path $output $part))
        try {
            while($written -lt 64MB) {
                $count=$inputStream.Read($buffer,0,[int][Math]::Min($buffer.Length,64MB-$written))
                if($count -eq 0) { break }
                $partStream.Write($buffer,0,$count); $written+=$count
            }
        } finally { $partStream.Dispose() }
    }
    if($inputStream.Position -ne $inputStream.Length) { throw 'The source archive requires more than two parts' }
} finally { $inputStream.Dispose() }
$sourceInstructions=@'
# Eclipse Temurin/OpenJDK corresponding sources

The complete source archive is split into two files for reliable downloading. Download both OpenJDK-sources.part01 and OpenJDK-sources.part02 into the same directory, then join them in PowerShell:

```powershell
$output = [IO.File]::Create((Join-Path $PWD 'OpenJDK-sources.tar.gz'))
try {
    foreach ($name in @('OpenJDK-sources.part01', 'OpenJDK-sources.part02')) {
        $input = [IO.File]::OpenRead((Join-Path $PWD $name))
        try { $input.CopyTo($output) } finally { $input.Dispose() }
    }
} finally { $output.Dispose() }
Get-FileHash ./OpenJDK-sources.tar.gz -Algorithm SHA256
```

Expected SHA-256: @SOURCE_HASH@

This is the complete, unmodified source archive of the Eclipse Temurin version included in this release. Its licenses and build scripts are retained.
'@
[IO.File]::WriteAllText((Join-Path $output 'OpenJDK-SOURCES.md'),$sourceInstructions.Replace('@SOURCE_HASH@',$expected),[Text.UTF8Encoding]::new($false))
Write-Output 'Prepared LGPL corresponding sources and verified matching OpenJDK source archive'
