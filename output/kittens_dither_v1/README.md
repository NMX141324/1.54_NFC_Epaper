# 小猫六色抖动测试图

发送到手机的文件：**kittens_sixcolor_fs_image03.bin**。

它与已经实板通过的r5.1图片接收程序兼容，继续用ST25 NFC Tap的“FTM演示 → 固件升级 → 获取手机保存的固件”，选择这个.bin，演示口令12345678。它只作为图片数据传入RAM。保持外接3.3V，等待完整传输及屏幕刷新，无需重新烧录MCU。

文件为28,832字节：32字节E6IM头 + 28,800字节4bpp像素；图像编号3，像素CRC32为27CF90E4。FTM整图协议允许重复发送同一文件，不需要像旧的4字节EEPROM命令那样手动更换序号。

## 文件

- kittens_original.png：内置imagegen生成的彩色原图，1254×1254。
- kittens_sixcolor_fs_image03_240.png：实际240×240六色点阵。
- kittens_sixcolor_fs_image03_preview.png：点阵3倍最近邻放大，便于观察抖动。
- kittens_sixcolor_fs_image03_comparison.png：从左至右为缩小原图、不抖动六色量化、FS六色抖动。
- kittens_sixcolor_fs_image03.bin：手机FTM实际发送的文件。
- kittens_sixcolor_fs_image03_manifest.json：调色板、像素统计、CRC和SHA256。
- generation_prompt.txt：本次内置生图提示词。

## 转换

原图使用内置imagegen生成。中心裁剪正方形，用System.Drawing高质量双三次缩放到240×240，再在线性光RGB中使用蛇形Floyd–Steinberg误差扩散，邻点权重7/16、3/16、5/16、1/16。

六色编码沿用现有驱动：0黑、1白、2黄、3红、5蓝、6绿；高4位是左像素，按行从左到右存储。没有使用未支持的编码4。预览从最终打包的像素重新解码，已验证与量化结果逐像素一致。

预览采用与此前色块测试相同的近似RGB调色板，尚未通过仪器标定这块墨水屏的真实反射颜色；实物色调和对比度可能不同。放大后的彩色点是误差扩散的预期效果。

校验命令：

    python tools/check_image_file.py output/kittens_dither_v1/kittens_sixcolor_fs_image03.bin

可复用转换工具：tools/resize_for_epaper.ps1、tools/dither_epaper.py。没有改动或重新烧录固件；本次生成文件通过格式、尺寸、两级CRC、合法像素编码与解码一致性校验，新的小猫图实物效果等待用户传输验证。
