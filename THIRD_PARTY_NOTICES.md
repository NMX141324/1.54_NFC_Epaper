# Third-party components

- `Drivers/CMSIS/Include`: Arm CMSIS Core headers. See [Arm license](Drivers/CMSIS/LICENSE.txt).
- `Drivers/CMSIS/Device/ST/STM32G0xx`: STMicroelectronics STM32G0 device definitions. See [ST license](Drivers/CMSIS/Device/ST/STM32G0xx/LICENSE.md).
- STM32 startup and runtime support retain the copyright and licensing notices included in their source files.
- Android dependencies are declared in `NFC_APP/app/build.gradle.kts` and `NFC_APP/gradle/libs.versions.toml`; their respective licenses apply.

The display driver follows the normal initialization and OTP waveform sequence for the [Good Display GDEH0154E01](https://www.good-display.com/product/983.html).
