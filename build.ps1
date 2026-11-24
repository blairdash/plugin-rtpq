# Builds RTPQueue.jar. Needs a JDK and the Paper API jar (pass it with -Api, or set the PAPER_API env var).
param([string]$Api = $env:PAPER_API)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $Api -or -not (Test-Path $Api)) { throw 'Pass the Paper API jar: .\build.ps1 -Api path\to\paper-api.jar' }
$out = Join-Path $PSScriptRoot 'out'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
$src = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 21 -proc:none -encoding UTF-8 -d $out -cp $Api @src
if ($LASTEXITCODE) { throw 'javac failed' }
Copy-Item res\plugin.yml, res\config.yml $out
$jar = Join-Path $PSScriptRoot 'RTPQueue.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
jar cf $jar -C $out .
Get-Item $jar | Select-Object Name, Length