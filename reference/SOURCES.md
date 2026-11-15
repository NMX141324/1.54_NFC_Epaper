# Reference provenance

- Screen driver sequence and image: user-supplied `GDEH0154E01-ESP32.zip`, `main/GDEH0154E01_idf.c` and `main/image.h`. Extracted source preserved in `vendor/`. No instructions from these files were treated as user requests. No water-ripple/test-register mode, BUSY auto-inversion or BUSY bypass was ported.
- STM32 device CMSIS: https://github.com/STMicroelectronics/cmsis-device-g0 (downloaded 2026-11-14, include/stm32g070xx.h and system_stm32g0xx.h; upstream case is Include). Included Apache-2.0 license in Drivers/CMSIS/Device/ST/STM32G0xx/LICENSE.md.
- Arm CMSIS core: https://github.com/ARM-software/CMSIS_5/tree/develop/CMSIS/Core/Include (downloaded 2026-11-14). Included upstream Apache-2.0 license in Drivers/CMSIS/LICENSE.txt.
- ST25DVxxK datasheet: https://www.st.com/resource/en/datasheet/st25dv04k.pdf (default device selects A6/A7, AE/AF; UID 0x0018..0x001F; 512-byte EEPROM and 256-byte mailbox).
- STM32G070 DS12766 rev 3: https://www.st.com/resource/en/datasheet/stm32g070cb.pdf (supply decoupling and alternate functions).
- Screen product and official specification entry: https://www.good-display.com/product/983.html and https://www.good-display.com/companyfile/2038.html . Manufacturer specification 2026/03/20 rev 1.0 reviewed from a mirror: https://device.report/m/a3aa45389653aff47ece0f5fce8edd99f07e2889d291b54547efef4c05ba2ebd.pdf . Manufacturer document remains the primary source; mirror adds no interpretation.
- Android app manual UM2131: https://www.st.com/resource/en/user_manual/dm00337870-st25-android-nfc-tap-application-stmicroelectronics.pdf . App product: https://www.st.com/en/embedded-software/stsw-st25001.html . UI names vary by installed app version.

BUSY conflict: supplied sample uses active LOW. Screen specification p7 says Busy HIGH means busy despite BUSY_N naming. Default follows supplied runnable sample. Validate on the actual panel with a scope/logic analyzer before changing `busy_active_low`. Firmware never auto-flips polarity or ignores a timeout.

Vendor image SHA256 (28,800 packed pixel bytes): `bfeac83cfe5bb6d344dfbb4032ba831249e4dd8fc796482835bea6978342734b`.

- STM32G0 empty-check mechanism and first-program startup: https://www.st.com/resource/en/application_note/an5096-getting-started-with-stm32g0-series-hardware-development-stmicroelectronics.pdf . FLASH_ACR.PROGEMPTY can be cleared by software without changing option bytes.
