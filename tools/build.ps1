param([ValidateSet('Debug','Release')][string]$Configuration='Debug',
      [ValidateSet(0,1)][int]$BusyActiveLow=1)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$bundleRoot=Join-Path $env:LOCALAPPDATA 'stm32cube/bundles'
function Find-Tool([string]$bundle,[string]$exe) {
    $bundlePath=Join-Path $bundleRoot $bundle
    if (Test-Path -LiteralPath $bundlePath) {
        $found=Get-ChildItem -LiteralPath $bundlePath -Recurse -File -Filter $exe |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    $command=Get-Command $exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Missing tool: $exe. Install the STM32 toolchain or add it to PATH."
}
$cmakeExe=Find-Tool 'cmake' 'cmake.exe'
$gccExe=Find-Tool 'gnu-tools-for-stm32' 'arm-none-eabi-gcc.exe'
$ninjaExe=Find-Tool 'ninja' 'ninja.exe'
$firmwareId='nfc-epaper-'+(Get-Content -LiteralPath (Join-Path $taskRoot 'VERSION') -Raw).Trim()
$oldPath=$env:PATH
try {
    $env:PATH="$(Split-Path $gccExe);$(Split-Path $ninjaExe);$oldPath"
    Push-Location $taskRoot
    try {
        & $cmakeExe --preset $Configuration "-DAPP_BUSY_ACTIVE_LOW=$BusyActiveLow"
        if ($LASTEXITCODE) { throw 'CMake configure failed' }
        & $cmakeExe --build --preset $Configuration
        if ($LASTEXITCODE) { throw 'Build failed' }
        $linkedBytes=[IO.File]::ReadAllBytes((Join-Path $taskRoot "build/$Configuration/epaper_NFC.bin"))
        if (![Text.Encoding]::ASCII.GetString($linkedBytes).Contains($firmwareId)) {
            throw 'Linked firmware version does not match VERSION'
        }
        $exportDir=Join-Path $taskRoot "dist/firmware/$Configuration"
        New-Item -ItemType Directory -Path $exportDir -Force | Out-Null
        $hashes=[ordered]@{}
        foreach ($extension in @('elf','hex','bin')) {
            $target=Join-Path $exportDir "epaper_NFC.$extension"
            Copy-Item -LiteralPath (Join-Path $taskRoot "build/$Configuration/epaper_NFC.$extension") -Destination $target -Force
            $hashes[$extension]=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        [ordered]@{
            firmware_id=$firmwareId
            configuration=$Configuration
            busy_active_low=$BusyActiveLow
            built_at_utc=[DateTime]::UtcNow.ToString('o')
            sha256=$hashes
        } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $exportDir 'firmware.json') -Encoding UTF8
        Write-Host "Firmware exported to $exportDir"
    } finally { Pop-Location }
} finally { $env:PATH=$oldPath }
