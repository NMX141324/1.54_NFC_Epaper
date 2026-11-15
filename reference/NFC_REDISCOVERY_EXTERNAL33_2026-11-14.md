# 外接 3.3V：手机反复发现 NFC 标签的对照监测

固件：`colors-wait-nfc-1234-r2`。用户说明当前使用外接 3.3V。本次未修改源码、未重新烧录 Flash，只通过调试器临时改变 RAM 中的 stage。

## 观测

首次有效调试读取：UID `E0022400F09FD8CF`，refresh_count=2、command_count=1、last_sequence=10、last_opcode=6、epd_error=0、last_status=0、ack_error=0、fault=0、boot_phase=4、uptime_ms=205819，nfc_error=-1。此前色块和 1234 命令均执行成功，最新一次 NFC 访问为 NACK。调试连接本身停在 Reset_Handler，读取的 RAM 属于此前运行，不能把此次调试复位当成自然复位。

重新建立受控调试会话后，使用 main 硬件断点检测是否重新启动。初始人为复位命中一次后继续运行。

| 阶段 | MCU 状态 | 手机观测 |
|---|---|---|
| A：stage=3，正常轮询 | 色块完成时间 29865 ms；之后 ticks 到 59357 ms、86634 ms；refresh_count 保持 1，boot_phase=4，fault=0；main 断点总命中仍为初始的 1 次。两个快照 NFC 错误分别为 0、-1 | 用户确认手机总是震动，反复通知发现 NFC 标签 |
| B：在主循环边界将 stage 临时设为 0 | 停止 UID、命令 EEPROM 的 I²C 访问，保留主循环与心跳；不额外读取 RF 动态寄存器，避免监测自身增加 I²C 访问 | 用户确认“停止了，明显稳定” |
| A2：软件复位恢复 stage=3 的原固件 | 成功连接正确 MCU，复位恢复默认轮询；此次未重新进行 RAM 采样 | 用户在色块刷新完成后确认反复通知“又出现了” |

恢复阶段，GDB server 报告目标无响应并断开，未能获得 B 结束时的 RAM 快照。不能从这个连接中断推断电源故障或 MCU 自然复位。随后 STM32CubeProgrammer NORMAL/SWrst 连接成功，识别正确设备 ID 0x460，执行软件复位，使原 Flash 固件重新初始化 stage=3。此次恢复是人为复位。

原始日志：`nfc_external33_monitor_2026-11-14.log`。它记录 GDB 命令与输出，不是逻辑分析仪的总线波形。最早单次连接的 RAM 读取未包含在该日志中，其结果记录于本报告。

## 解释与边界

A/B/A 对照支持：在此次供电与手机条件下，MCU 对 NFC 的 I²C 访问是反复发现标签的重要触发因素。在 A 阶段有效监测窗口内没有观察到 MCU 再次进入 main。NACK 本身不能证明芯片损坏或 MCU 重启。

ST 的 [AN5262，第 8、12 页](https://www.st.com.cn/resource/en/application_note/an5262-how-to-manage-simultaneous-ic-and-rf-data-transfer-with-an-st25dvxxk-device-stmicroelectronics.pdf) 说明：ST25DVxxK 的 RF 与 I²C 由先到的事务占用，另一路不能同时访问；I²C 忙时 Inventory/寻址 RF 命令可能不回应，RF 忙时 I²C 会收到 NACK。手机因未响应重新发现标签，与本次观测相符，但未抓取手机 RF 命令，不能证明每次通知的具体触发帧。

现有程序在等待阶段每 250 ms 进行两次命令读取（间隔 10 ms），每 2 秒读取一次 UID。刷新期间主线程等待屏幕，没有执行这段轮询。这解释了用户报告的“刷新时不重复发现，等待时重复发现”。

后续改进方向：保留启动时识别 UID，避免重复读取不变 UID；优先用已连接的 PB3/GPO 接收 RF 写入事件后读取命令，并按 ST25DV04K 数据手册配置和验证事件，减少后台 EEPROM 轮询。需要另行实现和实板验证，当前 r2 尚未包含这些更改。此次未测量电源纹波、RF 波形，也未启用 ADC。
