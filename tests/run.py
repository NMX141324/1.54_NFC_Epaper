"""Compile the actual portable firmware modules for Cortex-M0+ and emulate.

Requires `unicorn` Python package. This tests logic and SPI byte streams,
not GPIO timing, RF circuitry, or display waveform quality.
"""
import hashlib
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'tests/build'
BUILD.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(BUILD / 'python'))
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS
from unicorn.arm_const import UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_PC

bundle = Path(os.environ['LOCALAPPDATA']) / 'stm32cube/bundles/gnu-tools-for-stm32'
gcc = sorted(bundle.glob('*/bin/arm-none-eabi-gcc.exe'))[-1]
tool = gcc.parent
elf, binary = BUILD/'firmware_test.elf', BUILD/'firmware_test.bin'
subprocess.run([sys.executable,str(ROOT/'tests/make_ftm_fixtures.py')],cwd=ROOT,check=True)
subprocess.run([str(gcc), '-mcpu=cortex-m0plus', '-mthumb', '-Os', '-g',
                '-ffreestanding', '-fno-builtin', '-nostdlib', '-Wall', '-Wextra', '-Werror',
                '-Itests/fake', '-IInc', 'tests/firmware_test.c',
                'Src/epd.c', 'Src/command.c', 'Src/st25dv.c','Src/image_transfer.c','Src/image_link.c','Src/status_led.c',
                '-Ttests/test.ld', '-lgcc', '-o', str(elf)], cwd=ROOT, check=True)
subprocess.run([str(tool/'arm-none-eabi-objcopy.exe'), '-O', 'binary', str(elf), str(binary)], check=True)
symbols = subprocess.check_output([str(tool/'arm-none-eabi-nm.exe'), str(elf)], text=True)
entry = next(int(l.split()[0],16) for l in symbols.splitlines() if l.endswith(' test_entry'))
cpu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
cpu.mem_map(0x10000, 0x40000)
cpu.mem_write(0x10000, binary.read_bytes())
cpu.mem_map(0x20000000, 0x10000)
cpu.reg_write(UC_ARM_REG_SP, 0x20010000)
cpu.reg_write(UC_ARM_REG_LR, 0x4F001)
cpu.emu_start(entry | 1, 0x4F000, count=500000000)
assert cpu.reg_read(UC_ARM_REG_PC) == 0x4F000, 'instruction limit reached before completion'
failures = cpu.reg_read(UC_ARM_REG_R0)
assert failures == 0, f'{failures} firmware assertions failed'
assert cpu.reg_read(UC_ARM_REG_SP) == 0x20010000, 'emulation did not finish'

# The exact display byte stream for pattern 4 remains identical to user image.
import re
src=(ROOT/'Inc/vendor_image.h').read_text()
image=bytes(int(x,16) for x in re.findall(r'0x([0-9a-f]{2})\b',src))
assert len(image)==28800
assert hashlib.sha256(image).hexdigest()=='bfeac83cfe5bb6d344dfbb4032ba831249e4dd8fc796482835bea6978342734b'
frame_address = next(int(l.split()[0],16) for l in symbols.splitlines() if l.endswith(' frame'))
assert bytes(cpu.mem_read(frame_address,28800)) == image, 'driver image stream differs from reference'
sys.path.insert(0,str(ROOT/'tools'))
from nfc_command import packet
assert packet(1,1)==bytes.fromhex('A5 01 01 A5')
assert packet(3,3)==bytes.fromhex('A5 03 03 A5')
assert packet(6,7)==bytes.fromhex('A5 06 07 A4')
assert packet(6,8)==bytes.fromhex('A5 06 08 AB')
print('PASS: Cortex-M0+ command replay/checksum/wrap, EPD sequence/28800-byte patterns/')
print('      both BUSY polarities/timeouts/missing-BUSY, ST25 UID/address/ACK/errors, image hash.')
print('      boot RESET hold/no replay, timed LEDs, command rest, 1234 glyphs.')
print('      GPO/password setup, no idle I2C, debounce/IRQ races, duplicates, bounded NACK retries/recovery.')
print('      full image FTM receive/readback, CRC retry/duplicate/timeout, corrupted/oversize rejection, raw panel bytes.')

print('      BUSY idle glitch filtering/short-refresh rejection, command09 receipt vs completion/failure, no reset replay.')
