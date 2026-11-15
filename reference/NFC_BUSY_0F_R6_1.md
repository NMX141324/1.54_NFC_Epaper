# 华为 YAL-AL10：Mailbox 0F 与重试修正

## 已有证据与判断范围

用户手机：HUAWEI YAL-AL10 / Android 10；本次实际为同一手机提供线圈电源，未进行外接 3.3V 对照。

- 失败位置包括 AE 开启 Mailbox，以及 AA 在第 4、9、10、15 段的第一包。
- 后续失败前已握手成功，并确认 6144、16384、18432 或 28672 字节。不能用启动准备不及解释所有失败。
- `MB_CTRL=41` 中 MB_EN=1，HOST_PUT_MSG 与 RF_PUT_MSG 均为 0，表示 Mailbox 已启用且没有待读取的消息；bit6 是消息归属状态，不是本次报错的错误位。
- ST AN5262 及 ST 员工说明：当 I²C 访问芯片时，非寻址 RF 请求可能被拒绝并返回 01 0F。0F 本身是通用错误，不能仅凭它断言所有失败均来自仲裁，更不能据此证明供电稳定。

APP 1.2 明确只为 A0/AD/AB 重试 0F，AE/AA/AC 直接退出。此实现缺口与日志吻合。r6 固件将每次 I²C 瞬时 NACK、CRC 重试均触发三秒 PA1 错误闪烁，所以有时仍正常传输但出现 PA1 提示。

## APP 1.3 / versionCode 4

- A0/AD/AB/AE/AA/AC 收到芯片明确的 0F 拒绝时，最多尝试 12 次，等待 20/40/60/80ms，后续上限 80ms；总额外等待最多 760ms。
- TagLost、I/O 异常、空响应和其他错误不重发；尤其不盲重发可能已经执行的 AA/AC。
- 连接后预留 300ms，等待 MB_MODE 初始化最多两秒，再启用 Mailbox 并进行 MCU 握手。
- 写包后留 50ms；读完 MCU 回执后留 30ms，让 RF_GET_MSG 对应的 I²C 交接完成。状态轮询间隔 30ms。
- 仍以 Mailbox 所有权、段 CRC ACK 与最终 85 81 回执判断成功，保留段级重传和整图校验。
- 增加 busyRetries 诊断。传图协议封装成可注入时钟/RF 的 St25ImageSession，执行完整会话模拟测试。

## 固件 r6.1-arbitration

- GPO 事件后至少留 5ms 再访问 I²C，避免紧贴 RF 交接末尾抢占。
- 空闲无 I²C 轮询；原有 20 次有限 I²C 尝试耗尽才记录 link_failures，之后等待新事件。
- 新增诊断计数 link_retries、link_failures、protocol_errors、crc_retries，原结构前部调试偏移保持不变。
- PA0：空闲短闪、传输快闪；PA1：刷屏常亮。可恢复 NACK / CRC 重试不再使 PA1 闪烁；协议终止、整图校验失败、超时、重试耗尽或屏幕错误仍提示。
- 仍无启动刷图，仍保留连续刷新至少五秒间隔。

## 验证和产物

- 22 项 JVM 测试通过；完整会话模拟覆盖 AE 忙、多个段边界 AA 忙、ACK 后 I²C 忙、延迟初始化、小包长、断电状态、响应丢失及有界超时。模拟收到的全部 28833 字节与原数据一致，确认不会重复执行已成功的命令。
- Android Debug APK 与 Lint 任务通过；Lint 保留项目既有警告（含依赖的部分 Lint 检查器不兼容），不表示所有设备兼容性已验证。
- Cortex-M0+ 模拟通过：APP 真实分包与像素流、完整图片 CRC、GPO 延时、瞬时错误恢复、重试耗尽后空闲不访问总线、错误灯分类。
- Debug Flash 44088 B；Release Flash 38196 B；RAM 均 32320 B（含链接器预留堆栈与堆）。
- APK：`output/nfc_busy_fix/epaper_nfc_app_1_3.apk`。
- 固件：`output/firmware/Release/epaper_NFC_r6_1_arbitration.hex`。
- 本轮未检测到 ST-Link 或 ADB 手机，没有烧录 r6.1 或安装 APK，也没有新的实物传输结果。

## 实物对照顺序

1. 先覆盖安装 APP 1.3，保留当前板上 r6，测试相同图片，避免同时改变两个变量。1.3 同样兼容旧 r5.1/r6。
2. 隔离线圈供电输出后使用稳定外接 3.3V，固定手机位置，连续传十张并记录成功次数；再用纯线圈重复同样测试。
3. 外供电稳定而线圈失败，需继续查手机 RF 断场、3.3V 下陷和储能。两者仍失败时，根据 busyRetries、MCU 新诊断计数、reset_flags 及 RF/I²C 波形继续定位。
4. 连接 ST-Link、稳定供电后可烧录 r6.1，核对新的指示灯行为；PA1 闪烁是否减少不能独立证明 RF 或供电问题已消除。
5. 手机显示收图成功只证明传输与校验完成；线圈供电还要保持供电直到屏幕 BUSY 结束。Android 关闭连接或切换页面后的 RF 场连续性尚未实测。

依据：[ST AN5262](https://www.st.com/resource/en/application_note/an5262-how-to-manage-simultaneous-ic-and-rf-data-transfer-with-an-st25dvxxk-device-stmicroelectronics.pdf)、[ST AN4910](https://www.st.com/resource/en/application_note/an4910-data-exchange-between-wired-ic-and-wireless-rf-iso-15693-using-fast-transfer-mode-supported-by-st25dvi2c-series-stmicroelectronics.pdf)、[ST 员工关于 RF/I²C 碰撞与 0F 的说明](https://community.st.com/st25-nfc-rfid-tags-and-readers-54/ftm-firmware-upgrade-issue-with-feig-mr102-reader-168503)。
