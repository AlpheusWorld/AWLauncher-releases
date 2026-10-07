param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Set JAVA_HOME to a JDK 21 installation' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$storeRoot = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.aw-release-signing'
$storePath = Join-Path $storeRoot 'ed25519-key.dpapi.json'
$publicPath = Join-Path $projectRoot 'src/main/resources/update/public-key.txt'
if (Test-Path -LiteralPath $storePath) { throw 'A release key already exists; do not rotate it accidentally' }
Add-Type -AssemblyName System.Security
$captured = & (Join-Path $JavaHome 'bin/java.exe') (Join-Path $PSScriptRoot 'SignUpdate.java') keygen
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
$pair = $captured | ConvertFrom-Json
$bytes = [Convert]::FromBase64String($pair.privateKey)
$protected = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
[IO.Directory]::CreateDirectory($storeRoot) | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $storeRoot -AclObject $acl
$record = @{ publicKey = $pair.publicKey; protectedKey = [Convert]::ToBase64String($protected) } | ConvertTo-Json
[IO.File]::WriteAllText($storePath, $record, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($publicPath, $pair.publicKey + "`n", [Text.UTF8Encoding]::new($false))
[Array]::Clear($bytes, 0, $bytes.Length)
$pair = $null; $captured = $null
Write-Output 'Generated an Ed25519 release key, protected with Windows DPAPI; only the public key is embedded'
