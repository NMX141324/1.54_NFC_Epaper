# 手机 NFC 磁场与线圈供电实测记录

本次只读调试，没有下载固件、写标签 EEPROM 或修改 GPO/EH 配置。读取 ST25DV04K 的 EH_CTRL_Dyn（I²C 用户区 0x53，地址 0x2002），bit2 是 FIELD_ON、bit3 是 VCC_ON。

## 已取得的原始数据

原始日志：`rf_field_baseline_gdb.log`；解析结果：`rf_field_baseline_summary.json`。

| 项目 | 结果 |
|---|---:|
| 已记录采样 | 57 |
| 有场采样 | 47 |
| 无场采样 | 8 |
| I²C NACK（未作为无场数据） | 2 |
| 有效相邻采样中的场状态切换 | 3（两次有→无，一次无→有） |

具体记录包括：第 17→18 条由 0x0C 变成 0x08，第 19→20 条由 0x08 恢复 0x0C，第 50→51 条再次变成 0x08，直到第 56 条仍为 0x08。全部有效采样中 VCC_ON=1，RCC_CSR=0。0x08 表示当时标签仍检测到 VCC，但没有检测到 RF 场。

可以确认标签端检测到的 RF 场并非一直存在。手机位置不变时，这支持手机间断发场的解释；若线圈位置、角度或耦合也发生变化，也会影响检测，不能把这些记录当作已经解析了手机 Inventory 等轮询命令。

## 测试边界与断连

测试开始时用户确认 ST-Link 的 3.3V 也连接板子。用户在采样期间/结束附近移除了这根线，确切墙钟时间边界没有记录，因此不能把整段数据标为纯线圈供电。GDB 日志的 tick_ms 只在目标 CPU 执行调试函数时前进，采样包含主机调试开销，不能据此推算真实 RF 断场持续时间。

第 57 条附近，GDB 报 `Could not read registers; remote failure reply '31'`，采样未完成计划的 80 条。移除外部 3.3V 后，两次 HOTPLUG 连接均报 `Unable to get core ID / No STM32 target found`。这与目标供电不足相符，但还需确认探针这根线是否为供电输出或 VTref 电平参考；若断开的是 VTref，调试通信也可能直接失效。

工具仍打印 Voltage=3.30V，但无法同时读取有效 CPU ID/设备 ID，不将该单独打印值视为 MCU 电源的可靠测量。

## 复位证据的限制

连接过程中捕获过 g_debug 中的正常色块/UID完成状态和复位快照 0x1C000000、0x14000000。NORMAL/GDB 连接也触发了软件复位，因此这些标志不能归因于手机导致的自然重启。进入受控采样前 main 已记录并清除粘滞标志；有效采样的 RCC_CSR 保持 0，没有在这段有效记录中观察到新复位。

下一步：只保留线圈供电并确保手机正在发场后，重新读有效核心 ID 和启动 reset_flags；配合 MCU 旁 3.3V、PW_IN、NRST 的示波器波形，确认断场是否实际导致掉电复位。调试信号可能影响极低功耗取能电路，最终供电稳定性仍应以电压波形为准。

## 用户后续确认

- 探针为常见小铝壳 ST-Link/V2，断开的是标注 3.3V 的引脚，按此型号常见接法是供电输出，并非所述官方探针的 VTref 接法。
- 用户描述：发送/准备显示 1234 时，PA0 的 LED 闪烁约十秒后又重启；此前纯外接 3.3V 没有这一现象。
- PA0/LED1 在代码中是正常心跳，每 500ms 切换一次；没有闪烁计数达到阈值后复位的代码。刷新指示是 PA1/LED2。
- 此后又尝试 NORMAL 方式读取设备与复位状态，同样返回 Unable to get core ID / No STM32 target found。没有取得“1234 时重启”的新复位标志，不能将当前断连当作已经测到欠压阈值。

本轮结论：RF 场有间断已获得寄存器采样支持；这些间断是否使 3.3V 跌落并直接造成所述重启，仍需恢复后复位标志或电压波形验证。未发现无命令超时复位或 PA0 闪灯计数复位路径，但尚不能声称所有软件原因均已排除。当前没有遗留运行中的 GDB/烧录进程，本轮没有下载或改写固件。

来源：[ST25DV04K 数据手册](https://www.st.com/resource/en/datasheet/st25dv04k.pdf)和[ST AN4913](https://www.st.com/resource/en/application_note/dm00329035-energy-harvesting-delivery-impact-on-st25dvxxx-behavior-during-rf-communication-stmicroelectronics.pdf)。
