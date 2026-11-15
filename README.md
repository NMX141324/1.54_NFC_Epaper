# 1.54 NFC Epaper

通过 Android 手机的 NFC 将图片发送到 1.54 英寸六色墨水屏。APP 完成图片编辑和抖动处理，STM32 接收并校验图像，驱动屏幕刷新，再将刷新结果返回手机。

## 硬件

| 部件 | 型号 |
|---|---|
| MCU | STM32G070CBT6 |
| NFC 标签 | ST25DV04K-IE6S3 |
| 墨水屏 | GDEH0154E01，240 × 240，六色 |
| 下载与调试 | ST-Link，SWD 接口 |

屏幕颜色为黑、白、黄、红、蓝、绿。板子采用独立的供电线圈和 NFC 通信线圈。

## 使用流程

1. 将配套固件烧录到板子，并在支持 NFC-V / ISO15693 的 Android 手机上安装 APP。
2. 在 APP 中选择照片，调整裁剪、方向、亮度、对比度和饱和度。
3. 点击“开始抖动”，选择六色输出效果。
4. 点击“通过 NFC 发送图片”，将手机 NFC 天线贴近板子并保持位置。
5. 图片传完后，APP 显示“正在刷新屏幕”。继续保持供电和 NFC 连接，直到显示“刷新完成”。

上电后保留屏幕原有画面，等待新图片。图片存放在 MCU RAM 中，断电后不会自动恢复或重刷。刷新中断后，需要重新发送完整图片。

纯线圈供电时，刷新结束前不要移开手机、锁屏或切换应用。使用外接 3.3V 时应隔离线圈供电输出，避免两路电源直接并联。

## APP 功能

- 相册或文件导入、EXIF 方向处理。
- 240 × 240 构图、缩放、拖动、旋转与留白。
- 亮度、对比度和饱和度调节。
- 无抖动、Floyd–Steinberg、Atkinson、Bayer 4 × 4 六色量化。
- PNG 预览与 E6IM 图片数据导出。
- NFC 分段传输、CRC 校验、有限重试和刷新结果提示。

APP 最低支持 Android 7.0。图片编辑可离线使用；NFC 发送需要支持 NFC-V 的实体手机。

## 编译固件

在 Windows 安装 STM32 工具链，包含 `arm-none-eabi-gcc`、CMake、Ninja 和 STM32CubeProgrammer。脚本会优先寻找 STM32Cube 的工具包，也可从 PATH 查找构建工具。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Configuration Release
```

输出文件：

```text
dist/firmware/Release/epaper_NFC.hex
dist/firmware/Release/epaper_NFC.bin
dist/firmware/Release/epaper_NFC.elf
dist/firmware/Release/firmware.json
```

使用 ST-Link 烧录已构建的固件：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/flash.ps1 -Configuration Release
```

VS Code 中也可使用现有的 Debug 构建任务和 ST-Link 调试配置。PA13、PA14 保留为 SWD 引脚。

## 编译 APP

使用 Android Studio 打开 `NFC_APP`。项目使用 JDK 25 和 Android SDK 37；SDK 路径由本机 `local.properties` 配置。

```powershell
cd NFC_APP
.\gradlew.bat :app:assembleDebug
```

安装包位于 `NFC_APP/app/build/outputs/apk/debug/app-debug.apk`。固件和 APP 的版本名称统一读取根目录的 [VERSION](VERSION)。APP 的 `versionCode` 在 Android 构建文件中管理。

## 引脚与指示灯

| 信号 | 引脚 |
|---|---|
| 屏幕 MOSI / SCL | PA2 / PA3 |
| 屏幕 CS / DC | PA4 / PA5 |
| 屏幕 RESET / BUSY | PA6 / PA7 |
| NFC I²C SCL / SDA | PB6 / PB7 |
| NFC GPO | PB3 |
| LED1 / LED2 | PA0 / PA1 |
| SWDIO / SWCLK | PA13 / PA14 |

SPI 使用软件实现，Mode 0、MSB first；I²C 为开漏软件接口。屏幕 BUSY 在本板上低有效。

| 状态 | PA0 | PA1 |
|---|---|---|
| 空闲等待 | 每 2 秒短亮 100ms | 熄灭 |
| 数据传输 | 每 100ms 亮灭交替 | 熄灭 |
| 屏幕刷新 | 熄灭 | 常亮 |
| 错误提示 | 熄灭 | 闪烁 |

MCU 启动时保持屏幕 RESET，接受完整图像后才释放并初始化。固件检查完整图像 CRC 和 BUSY，连续刷新之间至少间隔 5 秒。

## 项目结构

```text
Inc/                 固件接口、配置与运行状态
Src/                 NFC 接收、屏幕驱动和主循环
Drivers/             CMSIS 与 STM32G0 器件定义
NFC_APP/             Android APP
cmake/               STM32 编译配置
tools/               构建与 ST-Link 烧录脚本
docs/protocol.md     图像与传输协议
VERSION              固件与 APP 共用版本号
```

协议细节见 [图像与传输协议](docs/protocol.md)。第三方组件归属见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。构建产物、本机 SDK 配置和签名文件不纳入版本库。
