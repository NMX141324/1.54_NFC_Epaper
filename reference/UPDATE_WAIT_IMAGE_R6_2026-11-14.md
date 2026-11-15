当前固件为 **r6-wait-image**，已编译和软件模拟验证，**尚未烧录到板子**。适用 STM32G070CBT6、ST25DV04K-IE 和 GDEH0154E01（240×240 六色）。

## 启动与传图

上电或复位不再刷新六色色块或 UID，保留墨水屏原有画面。初始化 NFC 后直接等待手机传输。仅靠近手机不会触发刷屏。

接收完整 E6IM 图片到 RAM → 校验头部、像素 CRC 和颜色 → 手机读取最终成功回执 → 刷新图片 → 回到等待状态。没有等待超时自动重启逻辑，连续刷新之间仍至少休息 5 秒。

项目 Android APP 使用的 `05 + E6IM` 协议保持兼容；ST25 NFC Tap 的 FTM 文件发送和已有图片 BIN 保持兼容。空闲不轮询 I²C，保留 GPO 事件机制。

| 状态 | LED1 / PA0 | LED2 / PA1 |
|---|---|---|
| 空闲等待（含初始化） | 每 2 秒亮 100ms | 熄灭 |
| 接收或回复数据 | 每 100ms 亮灭交替 | 熄灭 |
| 屏幕刷新 | 熄灭 | 常亮 |
| 传输校验、超时或通讯错误 | 熄灭 | 每 250ms 亮灭交替，提示 3 秒 |
| NFC 初始化失败 | 熄灭 | 持续闪烁，移开手机后复位重试 |

SysTick 每 10ms 更新指示灯，阻塞等待屏幕 BUSY 时仍保持指示；刷屏指示优先于之前暂存的错误提示。芯片 HardFault 时 PA1 常亮，`g_debug.fault=1`。

旧 EEPROM 简单指令仍可在 Mailbox 关闭时使用，opcode 6 显示 1234。启动建立历史序号基线，不重放旧命令。`g_debug.boot_phase=4` 表示进入等待，旧字段 `boot_colors_done_ms`、`boot_uid_start_ms` 保留并置 0。

## 编译与验证

- Debug、Release 均运行 Cortex-M0+ 模拟测试后编译。
- 验证启动不发送 SPI、无屏幕复位操作、无启动等待；屏幕忙或 NFC 缺失也不触发开机刷屏。
- 验证定时灯光、阻塞刷屏指示、错误提示恢复、计时回绕。
- 回归完整 28,832 字节图片收发、CRC 重试、丢包超时、非法图片拒绝及实际像素流。
- 包含项目 Android APP 已生成的真实分包互通测试。

Debug Flash 44,028 字节，Release Flash 38,208 字节；RAM 均为 32,304 字节（包含链接器预留的堆栈和堆）。没有更改传图格式、密码或 NFC 芯片配置流程。

烧录文件位于 `output/firmware/Debug/epaper_NFC_r6_wait_image.hex` 和 `output/firmware/Release/epaper_NFC_r6_wait_image.hex`。这些 HEX 用于 ST-Link 烧录 MCU；手机仍发送 E6IM 图片 BIN。

本次没有连接调试器或执行烧录。r5.1 的两张整图连续传输此前已获用户实板确认；r6 本次仅完成软件验证，烧录后需用 APP 完成一次实板传图验证。
