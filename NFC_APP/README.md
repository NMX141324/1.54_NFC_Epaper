# 纸间 NFC

面向 GDEH0154E01 六色墨水屏的 Android 图片编辑与 NFC 发送应用，与本仓库的 STM32 固件配套使用。

## 功能

- “选择照片”直接调用相册应用，无可用相册时使用系统文件选择器，处理照片方向。
- 裁剪、留白、缩放、平移、旋转及颜色调整。
- 手动执行六色抖动并预览实际打包结果。
- 紧凑编辑页：算法、构图、角度、对比度、亮度、饱和度按需展开；再次点击收起。
- 通过 ST25DV Mailbox 发送图片，显示传输进度和屏幕刷新结果。

图片在手机本地处理。最低 Android 7.0；NFC 发送需要支持 NFC-V / ISO15693 的手机。

## 构建

Android Studio 打开本目录，配置 JDK 25 与 Android SDK 37，然后执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。版本名称读取上级目录的 `VERSION`，更新安装时同步递增 `app/build.gradle.kts` 的 `versionCode`。

当前图像编码和传输逻辑的单元检查可通过 `:app:testDebugUnitTest` 运行；静态检查任务为 `:app:lintDebug`。

## 使用

选择照片 → 调整构图与颜色 → 生成预览 → 传输到墨水屏 → 手机贴近板子 → 等待“刷新完成”。

拖动和双指缩放直接更新画布，松手后保存参数；调色预览与生成的六色像素共用构图计算。六色抖动仅在点击“生成预览”时运行。

为适配外壳安装方向，发送用的像素数据固定旋转 180°，并按旋转后的数据计算 CRC；照片和六色预览保持正常方向，无需手动旋转照片。

图片传完不等于屏幕刷新结束。APP 会继续保持 NFC 连接，核对本张图片的刷屏结果后才提示完成。等待期间不要移开手机或切换应用。断连后不会自动重发，需重新点击发送。

## 主要代码

| 文件 | 用途 |
|---|---|
| `MainActivity.kt` | 照片选择、NFC Reader Mode 与生命周期 |
| `EditorScreen.kt` | 编辑页面及传输/刷新状态界面 |
| `EditorViewModel.kt` | 图片任务与传输状态 |
| `PhotoCanvas.kt` / `PhotoGeometry.kt` | 实时手势画布与构图计算 |
| `ImageProcessor.kt` | 解码、方向、构图与颜色处理 |
| `EpaperCodec.kt` | 六色量化、像素打包、E6IM 与 FTM 编码 |
| `St25Commands.kt` | ST25DV 射频命令及有限忙重试 |
| `St25ImageSession.kt` | 图像发送和刷新完成协议 |
| `St25Transfer.kt` | Android NFC-V 连接与异常处理 |

硬件接线与固件构建见 [项目 README](../README.md)，协议格式见 [protocol.md](../docs/protocol.md)。
