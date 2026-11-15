# STM32G070 + ST25DV04K + 六色墨水屏

当前开发版本为 **r7 固件 + APP 1.4**，已编译并通过软件测试，**本轮未烧录或安装**；上一次已烧录的版本为 r6.1。

- 上电保持屏幕 RESET，不自动刷色块、UID 或历史图片；收到完整新图后才执行完整刷新。
- APP 区分“图片接收成功”和“屏幕刷新完成”，保持 NFC 连接等待 MCU 的实际刷屏结果，再显示完成界面。
- 刷新中断后不自动续刷或重发。增加 BUSY 稳定判断，异常短刷新报错；偶发故障的实际电压/BUSY 波形尚未测量。
- [APP 1.4](output/refresh_result_r7/epaper_nfc_app_1_4.apk) 与 [r7 Release HEX](output/firmware/Release/epaper_NFC_r7_refresh_result.hex) 需要配套升级。
- [本轮排查、协议及测试记录](reference/REFRESH_POWER_R7_2026-11-15.md)。27 项 APP JVM 测试、Cortex-M0+ 模拟、Debug/Release 编译及 Android Lint 任务通过，硬件验证待完成。

以下保留历史指令和硬件说明；当前启动与传图行为以上面的 r7 记录为准。

## 历史 r4/r5：上电自动显示与手机触发 1234

下载后每次上电或复位，**只自动刷新一次六色色块，然后保留该画面等待 NFC 命令**。启动不再固定等待 5 秒，也不自动显示 UID。UID 仍可经 I²C 读取用于识别芯片和 ST-Link 调试，不会因此刷新屏幕。六色屏完整刷新此前实测约 30 多秒，实际以 BUSY 结束为准。

当前取消每 250ms 的命令轮询和每 2 秒的 UID 读取。启动识别 NFC、建立旧命令基线并配置 GPO 后，空闲不访问 I²C。PB3 下降沿中断记录 RF EEPROM 写入事件，最后一次事件后等待 300ms，再连读两次校验。通讯失败每 150ms 重试，最多 4 次，之后等待下一次写入事件。初始化失败记录 `nfc_init_error`，不自动退回持续轮询；移开手机后复位可重新初始化。

六色色块显示完成后，在 ST25 NFC Tap 的“写存储器”单字节十进制页面，依次填写下表，每行点一次 OK。命令为 `A5 06 09 AA`，完整写入并通过校验后显示居中的白底黑字 **1234**。仅线圈供电时，写入后还需保持供电磁场，直到刷新完成。

| 顺序 | 字节地址（十进制） | 数值（十进制） |
|---|---:|---:|
| 1 | 64 | 0 |
| 2 | 65 | 6 |
| 3 | 66 | 9 |
| 4 | 67 | 170 |
| 5 | 64 | 165 |

这是序号 9 的命令。同一序号不重复执行；下一次使用序号 10 时，按同样顺序写入，只将第三行数值改为 10、第四行改为 169（完整命令 `A5 06 0A A9`）。如果 EEPROM 已有序号 9，第一次也应直接使用新序号。复位后不会自动重放 EEPROM 中的旧命令。

刷新完成后最少休息 5 秒。收到新命令时若间隔未满，程序等满 5 秒再刷新，自动保留当前已接收的命令，不再以旧版的 30 秒规则拒绝它。一次只发送一条，等刷新结束再发送下一条。

通过 `python tools/nfc_command.py digits 9` 可生成命令及十进制写入表。调试时 `g_debug.boot_phase`：1=刷色块、4=等待指令、5=启动失败。保留的旧字段 `boot_uid_start_ms` 为 0，表示未自动刷新 UID。

## 先看这些硬件事项

**只用同一手机给线圈供电并通信时，反复出现色块→UID不代表程序在等命令超时后主动复位。** 当前没有这种复位逻辑，也没有启动看门狗。先排查手机断场或 3.3V 下陷；诊断固件已增加 `g_debug.reset_flags`，启动时保存并清除 RCC_CSR 复位标志。电源复位标志为 bit27，看门狗为 bit29/30，软件复位为 bit28。首次正常上电同样会有电源复位标志，需结合重复复位与电压波形判断。详见 [线圈供电复位排查](reference/COIL_POWER_RESET_2026-11-14.md)。当前固件已包含复位标志诊断。

1. **MCU 去耦需要补齐或核实 PCB 上已有。** 图中 U4 附近没有画 VDD/VDDA 去耦；按 ST 数据手册在电源脚旁放 100nF + 4.7µF，并核实 VREF+ 去耦。墨水屏升压电流容易影响 MCU；先接稳定外部 3.3V。
2. **外供电接 3.3V 网络和 GND，不接 PW_IN。** 断开 R5（或确认未装），隔离 U2 输出，避免外供电倒灌。R6 保持 NC，暂不将 ST25DV 的 V_EH 接到系统电源。首轮不启用线圈供电。
3. **屏幕电容耐压要核实。** 佳显规格书列出的升压相关 1µF 电容要求 25V 等级；原理图只写容量，不能据此判断耐压合格。D1–D3 需核对反压、正向压降及电流能力；47µH 电感还要核对饱和电流和 DCR。
4. **FPC 接法没有看到明显的脚号错误。** 15 脚为 3.3V，17 脚及电容公共端为 GND，8 脚接地选择四线 SPI；21/23 脚分别接正/负栅极电压。放大图见 `reference/fpc_detail.png`。仍需对实板排线接触面、1 脚方向、Q3 管脚顺序逐项检查，不能只凭符号外形判断封装。
5. **BUSY 极性有资料冲突。** 压缩包代码默认低有效，佳显规格书第 7 页却写高电平忙。固件默认跟随例程，通过 `g_debug.busy_active_low` 调整，必须先看实测波形。没有自动翻转，也不会忽略 BUSY；刷新后还要求 BUSY 确实进入忙状态，防止断线被当成成功。
6. **SWD 建议增加 NRST 引出。** 当前图里只有 SWDIO、SWCLK、电源和地，没有调试复位脚。可先正常连接下载，连接失败时需要 NRST 来使用“复位下连接”。PA13/PA14 在代码中始终保留调试功能。R13 是 PA14/BOOT0 外部下拉，暂不改动。
7. LED 串联 10k，亮度可能很低，不能单凭“不亮”认定 MCU 没运行。以 `g_debug.heartbeat` 为准。

MCU 与 NFC 供电、I²C 上拉和地线在图上是完整的。以上只审核了所给原理图 PNG，没有 PCB、BOM 和线圈参数，尚不能确认实际布局和射频性能。

## 接线与引脚

| 功能 | MCU | 屏幕/芯片脚 |
|---|---|---|
| EPD SDA / MOSI | PA2 | FPC 14 |
| EPD SCL / CLK | PA3 | FPC 13 |
| EPD CS | PA4 | FPC 12 |
| EPD DC | PA5 | FPC 11 |
| EPD RST | PA6 | FPC 10 |
| EPD BUSY | PA7 | FPC 9 |
| NFC SCL | PB6 | ST25DV 6 |
| NFC SDA | PB7 | ST25DV 5 |
| NFC GPO | PB3 | ST25DV 7，RF 写入完成下降沿中断 |
| LED1 / LED2 | PA0 / PA1 | 高电平点亮 |
| SWDIO / SWCLK | PA13 / PA14 | ST-Link SWD |

屏幕接线不对应 SPI1 的 MOSI/SCK，采用**软件 SPI，Mode 0，MSB first**。初测优先可观察的低速波形，速度取决于编译和 GPIO 开销。I²C 同样采用开漏软件实现，适配现有 10k 上拉，支持重复 START、时钟拉伸超时和 9 脉冲总线恢复。无需 HAL 或 CubeMX 生成代码；使用官方 CMSIS 寄存器定义。MCU 用内部 HSI16，16MHz，无需晶振。

ST-Link 与板子共地；SWDIO 接 PA13，SWCLK 接 PA14。带 VTref 的探针接板上 3.3V 作为电平参考。若克隆 ST-Link 的“3.3V”脚是电源输出，不要与外部电源并联；先核实探针接口定义。屏幕刷新由外部电源供电。

## 编译、下载和调试
 
脚本自动发现本机 `%LOCALAPPDATA%/stm32cube/bundles` 中的工具。当前环境已找到 GCC 14.3.1、CMake、Ninja 和 STM32CubeProgrammer。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/flash.ps1
```

输出在 `build/Debug/epaper_NFC.elf`、`.hex`、`.bin`。烧录脚本使用低速 SWD（请求 140kHz，当前探针实际 125kHz）、NORMAL 和软件复位，下载后校验并复位；没有 NRST 的板子不能假定支持复位下连接。脚本没有修改选项字节、读保护或擦除整片芯片的单独操作。

VS Code：先 `Ctrl+Shift+B` 编译，选择 **STM32G070: ST-Link Debug** 后 F5，运行到 main；继续运行使 SysTick 初始化完成。在 WATCH 添加：

```text
g_debug
epd_phase
epd_bytes_sent
epd_busy_level
```

需要改变量时，先点击调试工具栏的“暂停”，在“调用堆栈”中选择 `main` 等 C 函数栈帧，再在“调试控制台”输入下面的 GDB 命令。当前安装的 ST `stlinkgdbtarget` 扩展使用 `>` 前缀：

```text
>set variable g_debug.stage = 1
>set variable g_debug.command = 1
```

也可在变量视图直接编辑值，然后继续运行。若提示 `Evaluation of expression without frameId is not supported.`，表示调试控制台没有选中的暂停栈帧：先暂停，再点击调用堆栈里的函数后重试。不要在终端或 Live Watch 输入修改命令。**刷新过程中不要打断点或长时间暂停**，以免中断屏幕波形；优先在空闲循环观察。刷新是阻塞操作，期间心跳和 `g_debug.uptime_ms` 不更新，内部 SysTick 和 BUSY 超时继续工作。

首次写入空白 MCU 后，如果软件复位停在 `0x1FFFxxxx` 的系统 Bootloader，需清除 `FLASH_ACR.PROGEMPTY` 或完全断电再上电。本次已通过调试器清除该运行时标志，无需改选项字节。详见 `reference/BOARD_TEST_2026-11-14.md`。

固件默认 `stage=3`、`command=0`：上电自动刷新一次色块，然后等待手机新命令。不会重放 EEPROM 中的旧命令。重新上电/复位会重新执行启动色块。阶段 1 为屏幕测试，阶段 2 增加 UID 读取，阶段 3 增加手机命令；三个阶段都不自动显示 UID。也可指定其他阶段重新编译：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Stage 2
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Stage 1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Stage 3
```

F5 的默认构建任务会按阶段 3 重建；日常可直接用手机发指令，也可通过 WATCH 切换阶段。Release 使用 `-Configuration Release`。本块屏已在 BUSY 低有效设置下实测通过，保持 `-BusyActiveLow 1`。

## 第一步：外部 3.3V 验证刷图

1. 断电完成硬件核对，R5/R6 隔离。先不上手机、不用线圈供电。测量 3.3V 与 GND 是否短路，确认排线方向。接稳定且限流可调的外部 3.3V，关注异常电流和升压器件发热。
2. 下载后运行，`g_debug.magic` 应为 `0x45504431`，`heartbeat` 约每 500ms 增加。初次排查屏幕时手动设置 `stage=1`（或用 `-Stage 1` 编译），不需要 NFC 芯片成功才能刷屏；已经验收的这块板日常默认阶段 3。
3. 继续运行后会自动显示六个竖色块，依次黑、白、黄、红、蓝、绿。后续需要重测时，启动流程结束后设置 `g_debug.command=1` 再继续运行。
4. 逻辑分析仪看 PA2/PA3/PA4/PA5：空闲 CLK 低，CS 低期间发送 8 位，DC=0 为命令。正常命令序列为 `E9 01`（初始化）、`10 + 28800 字节`、`04`、`12 00`、`02 00`、`07 A5`。
5. 升压阶段用示波器看 3.3V 是否下陷、VGH/VGL 是否建立；高压端按厂家参考设计测量，避免探针短路。软件 `epd_error=0` 还需配合肉眼图案和电源波形验收，单向 SPI 没有读回芯片 ID 的路径。
6. 成功后 `refresh_count` 增加，`epd_bytes_sent=28800`，`epd_phase=0`。每次完成后至少间隔 5 秒；若提前收到新命令，程序自动等满间隔再执行。

| `g_debug.command` | 动作 |
|---|---|
| 1 | 六色色条 |
| 2 | 全白 |
| 3 | NFC UID，两行十六进制，需阶段 ≥2 |
| 4 | 压缩包自带六色图片 |
| 5 | 黑白棋盘 |
| 6 | 白底黑字 1234 |

命令被取走后 `command` 自动归零。用 1、4、2 顺序验收即可，不做无限循环刷屏。屏幕每次刷新完成会关升压并深睡，下次通过 RST 唤醒。

`epd_error=-1/-2/-3/-4/-5/-6` 分别是复位/初始化/写帧前/上电/刷新/掉电阶段 BUSY 超时；`-9` 表示刷新命令后 1 秒内未看到 BUSY 进入忙状态。保留 `epd_phase`，不发送后续刷新命令。故障后先断电查电源、排线和 BUSY，不能只反复改极性重试。`LED2` 刷新期间亮，屏幕错误后保持亮；`fault=1` 表示 HardFault。

## 第二步：MCU 读取 ST25DV

1. 第一步通过后，暂停将 `g_debug.stage=2`，继续运行。约 2 秒内读取 UID。
2. 观察 `nfc_present=1`、`nfc_error=0`、`uid_hex` 为以 `E00224` 开头的 16 位字符串；本型号 ST25DV04K-IE 的工厂 UID 产品字节为 0x24。原始寄存器顺序保存在 `uid_lsb_first[8]`，与手机显示顺序相反。
3. 默认系统区 **7 位地址 0x57**，用户区 **0x53**；不要与总线字节 `AE/AF`、`A6/A7` 混淆。UID 从系统区 `0x0018..0x001F` 读取。已改过 I2C_CFG 的芯片需相应修改 `Inc/st25dv.h`；这里不盲目扫描/改写系统配置。
4. 设置 `command=3`，UID 应显示到屏幕，两行各 8 位，下方是六色色块。等待刷新结束，与 ST25 NFC Tap 扫描出的 UID 对照。

I²C 错误：`-1` NACK（也可能是手机 RF 正忙），`-2` 线路/时钟拉伸超时，`-3` UID 型号不符，`-5` 两次命令读取不一致，`-6` 工厂 I²C 密码未能打开配置会话，`-7` GPO 配置校验失败。先不用 RF 磁场也应能通过外供电读 UID。启动配置静态 GPO 为 `0xC0`（只启用 RF_WRITE 和 GPO_EN），仅当值不同时写入；使用默认全零 I²C 密码的 Present Password 命令授权，不修改密码或锁定位。若已设自定义 I²C 密码，需适配初始化代码。观察 `nfc_gpo_ready=1`、`nfc_init_error=0`；写入时 `nfc_gpo_events` 应增加。

## 第三步：ST25 NFC Tap 发简单命令

继续保持外接 3.3V。优先用 Android 的 **ST25 NFC Tap**，确认手机支持 ISO15693 / NFC-V。使用内存页的原始块读写，界面名称可能是 MEMORY、MEMORY DUMP 等；不使用图像传输 Demo，也不使用 NDEF 文本替代原始字节。不要锁定标签或改密码。

1. 默认 `stage=3`，等待启动六色色块显示完成（`boot_phase=4`）。MCU 在启动自动刷图之前记录 EEPROM 的旧序号，防止复位后自动重放上一次刷图。启动期间到达的新命令保留在 EEPROM，启动完成后处理。
2. 手机进入用户内存，写 **RF 块 0x10（十进制 16）**，对应 I²C 字节偏移 **0x0040**。支持块写时一次写完整 4 字节，区分块地址和字节偏移。如果页面只有“字节地址”和“数值”，且仅能输入数字，则按下面的单字节十进制方法操作。
3. 格式是 `A5 操作码 序号 异或校验`，校验为前三字节 XOR。每条新指令更换序号，0..255 可循环。同一序号只执行一次，即使操作码不同也不重复执行。

| 动作 | 写入 0x10 块的 4 个十六进制字节 |
|---|---|
| 色条，序号 1 | `A5 01 01 A5` |
| 白屏，序号 2 | `A5 02 02 A5` |
| UID，序号 3 | `A5 03 03 A5` |
| 例程图片，序号 4 | `A5 04 04 A5` |
| 棋盘，序号 5 | `A5 05 05 A5` |
| 显示 1234，序号 7 | `A5 06 07 A4` |

**ST25 NFC Tap 单字节十进制页面：** 以色条、序号 6（`A5 01 06 A2`）为例，按下表逐行点 OK。先将魔数清零，数据写完后最后提交魔数，避免 MCU 提前处理未完成的命令。

| 写入顺序 | 字节地址（十进制） | 数值（十进制） |
|---|---:|---:|
| 1 | 64 | 0 |
| 2 | 65 | 1 |
| 3 | 66 | 6 |
| 4 | 67 | 162 |
| 5 | 64 | 165 |

不能把十六进制 `A5` 当作数值 5；它对应十进制 165。每次新测试还需更换序号并重新计算校验，例如色条、序号 7 为 `A5 01 07 A3`，地址 66 写 7、地址 67 写 163，其余顺序相同。

如果旧 EEPROM 刚好是相同序号，使用新序号。可生成任意测试指令：

```powershell
python tools/nfc_command.py bars 6
# 输出 A5 01 06 A2
python tools/nfc_command.py digits 7
# 输出 A5 06 07 A4 及十进制写入表
```

4. MCU 只在 GPO 写入事件后读取命令，间隔 10ms 连读两次一致且校验正确才处理。每次新事件重新等待 300ms，刷新期间的事件由中断保留。刷新期间串行处理，**每次只发一条，等执行完成再发下一条**；当前不是队列，持续写入会覆盖尚未处理的指令。空闲不读取 UID、命令或中断状态寄存器。
5. 完成后读 **RF 块 0x11（十进制 17）**，I²C 偏移 **0x0044**。回执为 `5A 序号 状态 XOR`，例如 1234、序号 7 成功时为 `5A 07 00 5D`。状态：0=完成，1=预留（当前版本不再拒绝短间隔命令），2=未知操作码，3=UID 不可用，4=屏幕失败。失败指令也消耗序号；修复后换新序号重发。
6. `command_count`、`last_sequence`、`last_status` 可通过 ST-Link 查看。回执写入会验证读回，碰到 RF 占用可重试最多 3 次；`ack_error!=0` 表示回执未确认，不能据此直接判断屏幕没刷。移开手机后 MCU 可继续运行，稍后重新贴近读取回执。

此测试暂占用户区 0x40..0x47，不自动格式化 NDEF，不写密码或静态系统配置。若标签已有内容，确认这 8 字节可用于测试；手机也不要对整片做填充/格式化。**关闭 FTM / Mailbox 模式后测试 EEPROM 命令**：ST25DV mailbox 启用时 EEPROM 写入被禁止。

## 双线圈与后续传图

双线圈不是仅按“供电/通信”命名就能保证互不影响。后续要测两个线圈的电感、Q 值、间距/同轴耦合、13.56MHz 谐振、整流负载加载后的失谐，以及手机贴近不同位置时的识别率和 3.3V 波形。并联谐振电容应根据实测电感和芯片/PCB 寄生电容确定，不能只照搬 100pF。

当前 PW_IN 只看到桥式整流、电容和 LDO，没有看到明确的输入过压钳位/电源就绪控制。恢复 R5 和线圈供电前，应按 ME6214 实际规格核对 PW_IN 峰值、压差和反向电流；记录一次完整六色刷新的峰值电流、能量和掉压。手机的 NFC 场能否支撑整个刷新周期，需要实测；三个 100µF 电容不能先假定足够。

ST25DV04K 的 EEPROM 仅 **512 字节**，这块屏幕的例程像素流是 **28,800 字节（每像素 4 位，两像素一字节）**，无法整图存入标签 EEPROM。后续通过 **256 字节 FTM mailbox 分包 + 序号/长度/CRC/确认/超时**传输，搭配 MCU RAM 或外部存储。G070 的 36KB RAM 理论上可放此像素帧，但届时还需重新核算栈、缓冲区和协议占用。第三步只演示手机选择 MCU 内置图案，没有实现手机上传整张图片。

## 验证范围与文件

- `Src/board.c`：时钟、GPIO、软件 SPI/I²C；`Src/epd.c`：屏幕波形命令和图案。
- `Src/st25dv.c`：UID、命令读取和 EEPROM 回执；`Src/command.c`：校验与序号去重。
- `Inc/app_debug.h`：ST-Link 观察/控制接口；`tools/`：构建、下载及命令生成。
- `tests/`：Cortex-M0+ 模拟执行实际驱动逻辑，假 GPIO/I²C 注入成功、超时和错误；不能代替实板。
- `reference/SOURCES.md`：厂家资料、资料冲突及图像来源。

软件测试：安装 Python `unicorn` 后运行 `python tests/run.py`（当前机器已放在 `tests/build/python`）。覆盖指令校验、序号去重/回绕、启动旧命令不重放、28,800 字节色条/白屏、SPI 命令顺序、两种 BUSY 极性、BUSY 卡死/不响应、UID 地址/格式与回执错误。板级供电、GPIO 时序、刷屏效果、RF 和手机 App 操作仍需按三步逐项验收。

资料：[STM32G070 数据手册](https://www.st.com/resource/en/datasheet/stm32g070cb.pdf)、[ST25DVxxK 数据手册](https://www.st.com/resource/en/datasheet/st25dv04k.pdf)、[佳显屏幕页面](https://www.good-display.com/product/983.html)、[ST25 NFC Tap](https://www.st.com/en/embedded-software/stsw-st25001.html)。
