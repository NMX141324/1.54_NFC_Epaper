# r4-gpo：保留收到的指令和判定结果

r3 已修复持续轮询导致手机重复发现标签，但早期手机写入测试未刷新。实板读取确认 GPO ready=1，events=10，read_attempts=10，read_errors=0，command_count=0；因此通知和读取链路已运行，需要排查指令内容和序号。

受控 GDB 中实际读到 EEPROM 0x40..0x43 为 A5 06 0B A8。刚完成初始化的 tracker 已把 11 登记为旧指令。改用 12 逐条断点跟踪，观察到 00 06 0B A8、00 06 0C A8 等分次写入状态，随后 ST-Link 断点插入失败并丢失连接。这轮调试可能暂停 MCU，不能作为正常运行的指令测试。

r4 保留 r3 的写入事件驱动逻辑，增加 RAM 诊断：

- nfc_last_packet[4]：实际读到且两次一致的指令字节。
- nfc_packet_result：0 尚未处理，1 magic/校验无效，2 重复或启动旧序号，3 已接受，4 读取过程中又收到写入事件。
- nfc_boot_sequence：启动时旧指令序号；256 表示启动时没有有效包。

这些字段只记录数据，不增加 I²C 访问。以后不需要逐条断点即可检查分次写入结果。旧命令不自动重放，新命令更换序号；不能把启动基线等同于已执行序号。

## 验证

Debug 和 Release 均编译通过；Cortex-M0+ 仿真覆盖空闲零 I²C、GPO 去抖/竞争、有限重试、重复序号、半包拒绝及诊断字段、显示字节流与已有协议测试。Debug Flash 37,128 B，Release Flash 34,256 B，链接器 RAM 均 2,184 B。

小铝壳 ST-Link/V2 连续调试后曾无法读取探针序列号/固件版本。单拔插 USB 未恢复；用户将 USB 与板侧连线全部断开后重新接回，探针恢复 SN 37FF71064E573436AC5F1043、V2J46S7。此次未升级探针固件或更改驱动。Windows 的单设备重启因权限不足未成功。

随后已烧录 Debug r4（colors-wait-nfc-1234-r4-gpo），CubeProgrammer 读回校验成功，并执行软件复位。测试期间不启动 GDB，保持板子独立运行。

测试新指令 A5 06 0D AE，序号13。ST25 NFC Tap 单字节十进制依次写：64→0、65→6、66→13、67→174、64→165。用户明确确认“已刷新1234”。本轮保持外接 3.3V、MCU 独立运行、不连接 GDB，端到端命令链路通过。确认后没有再次复位 MCU，保留 1234 画面。

下次新指令用序号14：64→0、65→6、66→14、67→173、64→165。重复序号不重新执行，上电前已存的命令不自动重放。最初不刷新时的每个瞬间未全部抓到，不能断言只有单一原因；已证实的因素是旧序号基线和不稳定调试连接对测试的干扰。

## 用户提供的参考固件

工作区 stm32G070_290Z94.hex 是 Intel HEX，转换后的连续镜像 44,928 字节。保留分析文件 stm32G070_290Z94.bin 和带偏移字符串列表 stm32G070_290Z94_strings.txt，未烧录该第三方固件。

镜像可见字符串 MX_NFC4_MAILBOX_Process、src\\app_nfc.cpp、Mailbox is activated、NFC transfer data timeout、NFC is busy、EPD VOL、EPD290Z94。这支持其包含 NFC mailbox 和屏幕电压检查逻辑；没有源码/符号，不能据此还原完整协议、确认引脚兼容或直接移植。它标记的屏幕型号也不是本项目 GDEH0154E01，不能替换当前屏幕初始化和波形。

已核对 [ST 官方开源 ST25DV 驱动](https://github.com/STMicroelectronics/stm32-st25dv/blob/main/st25dv.c) 及 [K 系列数据手册](https://www.st.com.cn/resource/en/datasheet/st25dv04k.pdf)。当前测试继续使用 EEPROM 4字节命令；整图传输所需 FTM mailbox 留待简单指令稳定后实现。
