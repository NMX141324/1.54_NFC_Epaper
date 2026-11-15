param([ValidateSet('Debug','Release')][string]$Configuration='Debug')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$cli=Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'stm32cube/bundles/programmer') -Recurse -Filter STM32_Programmer_CLI.exe -File |
    Sort-Object FullName -Descending | Select-Object -First 1
if (!$cli) { throw 'STM32CubeProgrammer not found' }
$elf=Join-Path $taskRoot "build/$Configuration/epaper_NFC.elf"
if (!(Test-Path -LiteralPath $elf)) { throw 'Build firmware first with tools/build.ps1' }
& $cli.FullName -c port=SWD mode=NORMAL reset=SWrst freq=140 -d $elf -v -rst
if ($LASTEXITCODE) { throw 'ST-Link download failed' }
Write-Host 'Firmware programmed, verified and reset.'
