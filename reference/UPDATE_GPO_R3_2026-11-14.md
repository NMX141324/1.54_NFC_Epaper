# r3-gpo：取消持续 I²C 轮询

用户授权修改并烧录。基于外接 3.3V 的 A/B/A 对照：轮询时手机反复发现标签，暂停 I²C 后稳定，恢复轮询后复现。

实现保持上电单次六色色块、等待新指令、操作码 6 显示 1234。删除每 250ms 命令轮询和每 2 秒 UID 读取，启动时识别 UID、建立旧命令基线。

ST25DV04K 静态 GPO（系统 0x57:0x0000）配置为 0xC0，仅 RF_WRITE_EN 与 GPO_EN。值相同不重复写 EEPROM。配置不同才用工厂全零 I²C 密码的 Present Password（0x0900，17 字节，验证码 09）授权，配置后用不同密码关闭会话；不执行 Write Password（07），不改密码或锁定位。动态 GPO_CTRL_Dyn（用户 0x53:0x2000）只写 bit7，并读回校验为 0xC0。

PB3/EXTI3 下降沿中断只累加事件计数，不在 ISR 内访问 I²C。捕获默认约 188us 的短脉冲，并记录屏幕刷新期间的写入。最后一次事件后等待 300ms，间隔 10ms 连读两次。读期间又有事件则重新等待。NACK/不一致每 150ms 重试，最多 4 次后停止，等下个事件。无事件时完全不访问 NFC。

保留校验、序号去重、两次刷新最短 5 秒间隔和 EEPROM 回执。回执延后 500ms，最多 3 次；新写入优先。初始化最多尝试 8 次，失败通过 nfc_init_error 报告，禁止回退到持续轮询。自定义 I²C 密码需相应适配。

## 软件与烧录验证

python tests/run.py 通过，覆盖 GPO 配置授权/关闭、相同配置不重复写入、空闲千次调用零 I²C、去抖、读期间 IRQ、重复序号、有限 NACK 重试/新事件恢复、刷新期间积累事件、初始化失败保持安静，以及已有屏幕与协议测试。

Debug Flash 36,976 B；Release Flash 34,136 B；链接器 RAM 均 2,168 B。版本 colors-wait-nfc-1234-r3-gpo。已用 ST-Link/V2 SN 37FF71064E573436AC5F1043 下载 Debug ELF，读回校验成功并软件复位。用户确认外接 3.3V 下手机不再反复提示发现标签。

## 五次写入不刷新的排查

早期 GDB 连接失败，随后 ST-Link USB 拔插恢复。通过 CubeProgrammer NORMAL 读取此前保留的 RAM：GPO ready=1、init_error=0、GPO events=10、read_attempts=10、read_errors=0、command_count=0、refresh_count=1。证明写入通知与 I²C 读取已经发生，尚无有效新命令被执行；不能再归因于 GPO 未触发。

随后受控 GDB 连接成功，停在启动色块之前。初始化状态正常，I²C 实际读到 A5 06 0B A8；当前 tracker 的启动基线序号为 11。因此测试时改用序号 12，且保持 MCU 运行，避免再次复位后将测试命令登记为旧命令。此前失败的连接可能扰动启动时序，不能断言最初每次不刷新都是同一原因。

序号12测试（十进制，按顺序）：64→0、65→6、66→12、67→175、64→165。完整包 A5 06 0C AF。新命令每次换序号；上电前已有的有效命令不自动重放。

原始跟踪见 r3_packet_diagnosis_2026-11-14.log；先前连接失败的 r3_gpo_board_2026-11-14.log 中本地 ELF 的变量值不能当作运行证据。后续 r4 添加无需断点的诊断字段并重新烧录，在 MCU 独立运行时，用户确认序号13成功显示1234，见 UPDATE_GPO_R4_2026-11-14.md。

依据：[ST25DVxxK DS10925 Rev11，第35–38页 GPO、第70页 Present Password](https://www.st.com.cn/resource/en/datasheet/st25dv04k.pdf)、[ST 官方开源 ST25DV 驱动](https://github.com/STMicroelectronics/stm32-st25dv/blob/main/st25dv.c)。只采用 K 型号寄存器，不加入 KC 分支。
