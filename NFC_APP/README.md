# 纸间 NFC — 1.54 英寸六色墨水屏

面向本项目的 GDEH0154E01 / STM32G070CBT6 / ST25DV04K-IE6S3。最低 Android 7.0（API 24），NFC 传图需要支持 NFC-V / ISO15693 的实体手机。

## 已实现的流程

当前版本为 **1.4 / versionCode 5**：[APK](../output/refresh_result_r7/epaper_nfc_app_1_4.apk)，必须配套 r7 固件。传图后保持 NFC 连接，等待本张图片的刷屏结果，只有成功回执才显示“刷新完成”。保留 0F 有限重试；断连或失败不自动重发。详见 [断电与完成回执排查](../reference/REFRESH_POWER_R7_2026-11-15.md)。27 项 JVM 测试、APK 编译与 Lint 任务通过，尚未安装或烧录到实物。

1. **导入**：主入口“相册选图”打开系统照片选择器；“其他导入”可选择其他相册应用（系统 ACTION_PICK 应用选择）或文件。仅本机解码，读取 EXIF 方向，大图采样到最长边约 1600 像素以内。只访问所选图片。系统列出的相册取决于手机安装了哪些支持选图的应用。
2. **构图**：240×240 输出；填满裁剪 / 完整留白；双指缩放、拖动平移；缩放和位置滑杆；左右旋转 90°；重置。
3. **调色**：亮度、对比度、饱和度，后台处理并合并连续滑杆事件。
4. **手动抖动**：编辑时仅更新普通调色预览；点击“开始抖动 · 生成六色图”才执行量化。支持无抖动、蛇形 Floyd–Steinberg、Atkinson、Bayer 4×4，以及强度调节。修改任何参数后清除旧六色结果，重新抖动后才能发送或导出。
5. **预览与导出**：在调色图和六色输出之间切换；六色预览从实际打包像素重建；保存 PNG 或带双 CRC 的 E6IM .bin。
6. **发送**：点击“通过 NFC 发送图片”后才接受标签；识别 ST25DV04K-IE 的 UID；开启 Mailbox，查询 MCU，分段发送，CRC 确认和最多 3 次 CRC 尝试，用独立弹窗显示传输百分比、MCU 已确认的字节数和状态，支持取消；收图成功或失败后保留结果直到关闭。NFC 断连后重新点击发送，从整图开始。

编辑状态由 ViewModel 保留；照片 URI 与参数经 SavedStateHandle 恢复。离开前台或旋转屏幕时取消未完成的 NFC 传输；回到 APP 后手动重新发送。

## 第一次使用

1. 将 Debug APK 安装到手机，打开系统 NFC。
2. 硬件烧录上层工程 **r7-refresh-result 固件**，先用稳定外接 3.3V 验证。上电保持原画面与屏幕 RESET，PA0 慢闪表示等待传图；旧固件不支持本版本的刷屏完成协议。
3. 点击“相册选图”导入照片，在构图 / 调色 / 抖动三个页签中编辑，然后点击“开始抖动 · 生成六色图”，确认六色输出预览。
4. 点击发送，再将手机的 NFC 天线区域贴近 ST25DV 天线，保持不动。
5. 图片传完后显示“正在刷新屏幕”，保持手机贴紧和板子供电，直到收到 MCU 结果并弹出“刷新完成”。断连时仅提示结果未确认，不会自动重刷。
6. 再发送下一张；固件在连续刷新之间自动保留至少 5 秒休息时间。

手机没有 NFC 时仍可编辑和导出。导出的 .bin 可沿用上层工程的 ST25 NFC Tap / FTM 文件发送流程。不要把 PNG/JPEG 直接交给 MCU 接收器。

**APP 1.4 与固件 r7 需一起升级。** 图片 E6IM 文件格式保持不变，新增命令 09 与刷屏结果回执；固件仍兼容旧客户端的命令 04/05。

## 协议约定

- 图像：240×240，行优先，每像素 4 位，左像素占字节高半部。
- 颜色编码：0 黑、1 白、2 黄、3 红、5 蓝、6 绿；4 不合法。
- 文件：28,832 字节 = 32 字节 E6IM 头 + 28,800 字节像素。
- 头部小端：E6IM / version=1 / format=1 / header_size=32 / width=240 / height=240 / pixel_length=28800 / image_id / pixel_crc32 / flags=0 / header_crc32。
- 两个文件 CRC 均是 IEEE CRC32；头 CRC 覆盖前 28 字节。
- APP 1.2 的 ST 自定义配置 / Mailbox 命令 A0 / AD / AE / AA / AB / AC 使用高数据率非寻址 flags=02，不附带 UID，避免 MCU I²C 访问时的 UID 寻址兼容问题；只贴近当前目标板。
- 注意 **RF 的 MB_CTRL_Dyn 寄存器地址是 0x0D**，MCU I²C 侧地址为 0x2006，不能混淆。
- ST25FTM 使用显式 payload 长度、总长度大端、交替段 ID、每段 2048 字节、段 IEEE CRC32 大端。包长根据手机 maxTransceiveLength 扣除 NFC 命令头调整，最高 240 字节。
- 先发送 ST Light 00 查询 capability bit0，再发送 **09 + E6IM**；接收器校验后在 RAM 中提交图片，不涉及 MCU Flash 更新。
- `89 81` 仅表示收图成功，手机继续保持连接，直到核对本图的 `89 90` 刷屏成功包；`89 91` 为驱动失败。CRC 段确认 80 与应用状态不可混用。完整包格式见 r7 记录。
- APP 只更新易失 Mailbox 的 MB_EN，不写标签 EEPROM、密码或锁定位。

屏幕 RGB 是与上层图片工具一致的近似值；实际颜料、光照和批次会影响颜色。后续可通过实拍色卡校准六色 RGB。整图靠手机线圈供电的能量、断场行为和不同手机 NFC 天线兼容性需要实板验证。

## 文件

- app/src/main/java/com/example/epapernfc/EditorScreen.kt：Compose 中文编辑界面。
- EditorViewModel.kt：图片任务、状态恢复、导出和传输状态。
- ImageProcessor.kt：有界解码、EXIF、构图和调色。
- EpaperCodec.kt：六色量化、像素打包、文件头和 FTM 编码（可做 JVM 测试）。
- St25Transfer.kt：NFC-V Mailbox 读写与 FTM 发送。
- app/src/test/java/com/example/epapernfc/EpaperCodecTest.kt：格式、算法、CRC、包长边界与互通测试数据生成。
- app/src/testInterop/android_app_interop.h：让 Kotlin 生成的真实分包进入已有 Cortex-M0+ 接收器，核对回执及屏幕像素流。
- 上层 tests/firmware_test.c：可选包含 APP 互通测试，APP 或生成数据不存在时保留原有测试能力。

## 构建与验证

在 Android Studio 中同步并执行：

```
:app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

也可在已配置 JDK 的终端执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK：app/build/outputs/apk/debug/app-debug.apk。

JVM 测试生成 app/build/interop/android_image_fixture.h 后，在上层固件目录运行既有测试：

```powershell
python tests/run.py
```

它会用实际 Cortex-M0+ C 接收逻辑处理 APP 生成的图像和分包，再比较 epd_show_frame 输出的全部 28,800 字节。测试生成物位于 build 目录，生产固件源码保持原样。

## 1.2 更新

- 修正 Mailbox 射频交互：自定义命令改为非寻址，完整计算 RF 包长。
- 写包后留 40ms 给 MCU 软件 I²C / 主循环交接，轮询间隔 20ms。
- 增加失败阶段、最后命令 / flags / 长度、MB_CTRL 和 MCU 握手结果，便于区分 RF 无响应与 MCU 无回执。
- 14 项 JVM 测试通过，包括模拟 I²C 仲裁下寻址命令无响应；手机是否恢复成功仍需实测。
- versionCode=3 / versionName=1.2，覆盖安装即可。此版本不改生产固件，不需要重新烧录。
- 本次 0% / Tag was lost 的排查证据与步骤见 [NFC_DIAGNOSE.md](NFC_DIAGNOSE.md)。

## 1.1 更新

- 由实时抖动改为手动按钮触发，普通预览与量化任务分离。
- 调参后旧六色结果失效，发送 / 导出按钮禁用，直到重新生成。
- 传输弹窗显示百分比、确认字节数、等待 / 发送 / 最终确认 / 成功 / 失败状态；CRC 重试保持在上一段已确认的进度。
- 增加系统照片选择器及其他相册应用入口，保留文件导入。
- versionCode=2，versionName=1.1，安装新版 APK 更新原 APP 即可；不需要烧录 MCU。

## 验证记录（2026-11-14）

- Debug APK 构建成功，约 15.3 MiB。
- JVM 测试 7 项通过，0 失败 / 0 跳过。
- Android Lint 无错误；仍有项目旧依赖 / 模板资源警告，以及一个依赖 Lint 检查器兼容性警告（其两条 Lifecycle 专用检查未运行）。
- Cortex-M0+ 固件仿真通过；ELF 已包含 android_image_file / android_image_wire，确认执行了 APP 生成数据的接收及全部像素流比对。
- 393×852 dp 中文首页 Compose 预览渲染成功。
- 未连接 Android 真机，尚未完成本 APP 的真实 RF 收发、照片方向与屏色验收；固件已有的 ST25 NFC Tap 实板验证不能替代此次 APP 实测。

## 后续联调顺序

1. 先用外接 3.3V 在实体手机发送照片，核对屏幕方向、六色色条和实际颜色。
2. 连续发送第二张，确认无需复位 MCU。
3. 测试传输中移开、取消、锁屏和重试，确认不显示半张图。
4. 最后再验证双线圈供电、刷新能量和不同手机的识别距离。

协议资料：[ST25DVxxK 数据手册](https://www.st.com/resource/en/datasheet/st25dv04k.pdf)、[ST25FTM UM2062](https://www.st.com/resource/en/user_manual/um2062-firmware-for-the-st25dvdiscovery-boards-stmicroelectronics.pdf)、[Android NFC-V 技术访问](https://developer.android.com/develop/connectivity/nfc/advanced-nfc)。
