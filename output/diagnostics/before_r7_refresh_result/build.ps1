param([ValidateSet('Debug','Release')][string]$Configuration='Debug',
      [ValidateSet(1,2,3)][int]$Stage=3,
      [ValidateSet(0,1)][int]$BusyActiveLow=1)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$bundleRoot=Join-Path $env:LOCALAPPDATA 'stm32cube/bundles'
function Find-BundleExe([string]$bundle,[string]$exe) {
    $found=Get-ChildItem -LiteralPath (Join-Path $bundleRoot $bundle) -Recurse -File -Filter $exe |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (!$found) { throw "Missing STM32 bundle: $bundle/$exe" }
    return $found.FullName
}
$cmakeExe=Find-BundleExe 'cmake' 'cmake.exe'
$gccExe=Find-BundleExe 'gnu-tools-for-stm32' 'arm-none-eabi-gcc.exe'
$ninjaExe=Find-BundleExe 'ninja' 'ninja.exe'
$oldPath=$env:PATH
try {
    $env:PATH="$(Split-Path $gccExe);$(Split-Path $ninjaExe);$oldPath"
    Push-Location $taskRoot
    try {
        # Fail before producing firmware if an old editor buffer restores the
        # previous automatic color/UID startup sequence.
        $pythonExe=(Get-Command python -ErrorAction Stop).Source
        & $pythonExe (Join-Path $taskRoot 'tests/run.py')
        if ($LASTEXITCODE) { throw 'Firmware behavior tests failed; no firmware exported' }
        & $cmakeExe --preset $Configuration "-DAPP_DEFAULT_STAGE=$Stage" "-DAPP_BUSY_ACTIVE_LOW=$BusyActiveLow"
        if ($LASTEXITCODE) { throw 'CMake configure failed' }
        & $cmakeExe --build --preset $Configuration
        if ($LASTEXITCODE) { throw 'Build failed' }
        # Refuse exports from an older saved editor buffer.
        $linkedBytes=[System.IO.File]::ReadAllBytes((Join-Path $taskRoot "build/$Configuration/epaper_NFC.bin"))
        if (![System.Text.Encoding]::ASCII.GetString($linkedBytes).Contains('wait-nfc-image-r6.1-arbitration')) {
            throw 'Linked firmware version mismatch; reopen main.c from disk before rebuilding'
        }
        $exportDir=Join-Path $taskRoot "output/firmware/$Configuration"
        New-Item -ItemType Directory -Path $exportDir -Force | Out-Null
        $artifactHashes=[ordered]@{}
        foreach ($extension in @('elf','hex','bin')) {
            $builtFile=Join-Path $taskRoot "build/$Configuration/epaper_NFC.$extension"
            $exportFile=Join-Path $exportDir "epaper_NFC_r6_1_arbitration.$extension"
            Copy-Item -LiteralPath $builtFile -Destination $exportFile -Force
            $artifactHashes[$extension]=(Get-FileHash -LiteralPath $exportFile -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $buildInfo=[ordered]@{
            firmware_id='wait-nfc-image-r6.1-arbitration'
            configuration=$Configuration
            stage=$Stage
            busy_active_low=$BusyActiveLow
            built_at=(Get-Date).ToString('o')
            flow=@('power_on_or_reset','preserve_panel_image_no_startup_refresh','wait_for_new_valid_nfc_command','opcode_6_displays_1234','FTM_file_to_RAM','validate_image_CRC','refresh_received_pixels')
            automatic_startup_refresh=$false
            leds=@{ PA0="idle:100ms/2s; transfer:100ms/200ms"; PA1="refresh:solid; error:250ms/500ms" }
            automatic_uid_screen=$false
            startup_fixed_delay_ms=0
            minimum_refresh_gap_ms=5000
            nfc_command_source='PB3_EXTI_RF_WRITE_AND_MAILBOX_GPO'
            image_transport='ST25FTM_LIGHT_COMMAND_04_OR_05_RAM_ONLY'
            image_file_bytes=28832
            image_pixel_bytes=28800
            idle_i2c_polling=$false
            adc_enabled=$false
            flashed_to_board=$false
            sha256=$artifactHashes
        }
        $manifest=$buildInfo | ConvertTo-Json -Depth 4
        $manifest | Set-Content -LiteralPath (Join-Path $exportDir 'build_info.json') -Encoding UTF8
        if ($Configuration -eq 'Debug') {
            Copy-Item -LiteralPath (Join-Path $exportDir 'epaper_NFC_r6_1_arbitration.hex') -Destination (Join-Path $taskRoot 'output/firmware/epaper_NFC_r6_1_arbitration.hex') -Force
            $manifest | Set-Content -LiteralPath (Join-Path $taskRoot 'output/firmware/build_info.json') -Encoding UTF8
        }
        Write-Host "Verified firmware exported to $exportDir"
    } finally { Pop-Location }
} finally { $env:PATH=$oldPath }
