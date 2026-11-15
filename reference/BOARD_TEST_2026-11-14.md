# 2026-11-14 实板调试记录

## 硬件与程序

- ST-Link/V2，固件 V2J46S7，探针序列号 `37FF71064E573436AC5F1043`。
- 工具报告目标电压 3.30V；这不是对刷新峰值掉压的示波器测量。
- MCU IDCODE `0x20016460`，STM32G07x/08x，128KB Flash。
- 实板 NFC 为 **ST25DV04K-IE**，用户已确认。当前源码只接受产品字节 `0x24`。
- NFC UID：`E0022400F09FD8CF`。系统区 0x14..0x17 读到 `7F 00 03 24`：128 个 4 字节块、IC_REF=0x24。

## 发现与处理

1. HOTPLUG 首次读 ID 为 `0x003`，RAM/Flash 重复读出 `0x00000003`。NORMAL、软件复位、请求 SWD 140kHz（探针实际 125kHz）后正确识别。尚未单独证明是哪项连接条件导致，不能据此认定芯片损坏。
2. GDB 连接后停在系统 ROM `PC=0x1FFF2404`；`FLASH_ACR=0x00050601`，PROGEMPTY（bit16）仍为 1。先备份并核对实际 Flash 内容，确认已有有效程序，再清除此运行时标志，软件复位后进入 `Reset_Handler` 和 `main`。未改写选项字节、RDP 或密码。
3. 原始 Flash 备份 `board_flash_before.bin` 共 35,556 字节，SHA256 `5fc070c77d7ecfb99b221ac2ccf3c40524033d54b6f923a564eb7ad8fe332578`，与当时 Debug bin 逐字节一致。GDB `compare-sections` 报告不一致，但实际二进制回读一致，因此以完整回读比较为准。
4. 首版识别代码使用了不符实板的产品码，导致 I²C ACK 正常、UID 数据正确却报 -3。已改为实板 ST25DV04K-IE 的产品码 0x24 并重新编译、下载。

## 已完成

| 测试 | 结果 |
|---|---|
| 六色色条 | 用户确认正确显示；epd_error=0，epd_bytes_sent=28800，epd_phase=0 |
| BUSY 极性 | 本块屏在 active-low=1 时完成刷新并正常结束，空闲读到高电平 |
| MCU 读取 NFC | nfc_present=1、nfc_error=0，UID 正确 |
| 屏幕显示 UID | 用户确认两行 E0022400 / F09FD8CF 与下方六色色块；驱动返回 0 |
| 手机写命令触发刷新 | 用户按单字节十进制表写入后确认显示色条；command_count=1、last_sequence=6、last_opcode=1、last_status=0 |
| 执行回执 | ack_error=0；I²C 回读 0x40..0x47 为 A5 01 06 A2 5A 06 00 5C，刷新和 EEPROM 回执均成功 |
| 双线圈供电、射频性能、供电峰值波形 | 尚未测量 |

参考：[ST AN5096，Empty check](https://www.st.com/resource/en/application_note/an5096-getting-started-with-stm32g0-series-hardware-development-stmicroelectronics.pdf)。完全断电再上电也会重新检查 Flash 是否为空；不要为解决这一运行时标志而修改读保护或锁定启动选项。

ST25 NFC Tap 实际页面只有“字节地址”和“数值”，使用十进制单字节写入。测试顺序是 64→0、65→1、66→6、67→162、64→165，每行确认一次。先清除魔数，最后提交魔数；不能在这个页面整包输入十六进制字符串。

三步验收后将构建默认阶段改为 3，command 仍为 0：上电等待新 NFC 命令。保留阶段 1/2 供后续排查；不自动重放 EEPROM 已有命令。

最终固件已重新下载，软件复位后无需调试器改变量即可进入 stage=3，nfc_present=1、nfc_error=0、protocol_armed=1。首次命令基线为旧序号 6，command_count=0，确认没有自动重放。

最终 Flash 回读 `board_flash_final.bin` 与 Debug BIN 逐字节一致，共 35,556 字节，SHA256 `e8243e76201ef12d36784fe812673fa96559fba990335e9b3f7f34ca7e63274e`。精简机器可读结果见 `board_test_result.json`。调试退出时已清空断点并 detach，让 MCU 继续等待手机指令。
