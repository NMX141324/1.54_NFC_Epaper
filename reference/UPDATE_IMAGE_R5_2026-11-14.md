# r5：ST25 NFC Tap 整图传输测试

用户要求生成可由ST App传输的图片数据、预览和固件逻辑，验证大文件收发与刷屏。用户已确认手机具有“FTM演示 → 固件更新”及本地文件选择。

## 数据与流程

输出目录为output/image_test。图1、图2均为240×240、六色4bpp，两像素一字节，行优先、左像素在高4位。有效编码0/1/2/3/5/6，分别为黑/白/黄/红/蓝/绿。预览PNG直接解码实际传输像素生成。

每个.bin为28,832字节：32字节E6IM文件头 + 28,800字节像素。文件头为小端：magic E6IM；version=1；format=1；header_size=32；width=240；height=240；pixel_length=28800；image_id；pixel_crc32；flags=0；前28字节的header_crc32。CRC为IEEE CRC32。图1像素CRC为97224EB2，图2为A598D107。

手机使用FTM“固件更新”的选文件入口作为二进制文件发送器。演示口令12345678。MCU只接收RAM中的图片，不写Flash、不改选项字节、不跳转执行文件。收到ST Light命令04、05或01时，仅接受E6IM格式和正确尺寸/校验/像素编码。手机App的JPEG图片发送并不兼容这个专用像素包，不能混用。

## 固件实现

- image_transfer.c实现ST25FTM分包接收、段CRC确认/重发、奇偶段去重、边界检查、15秒中断超时，以及ST Light命令00/07/04/05/01/06/02。接收缓存只保留一张图，分段重传时在该缓存回退覆盖；半包和错误图不会触发刷新。
- 支持ST旧版按32位字处理及按字节处理的CRC接口差异：探测CRC/MPEG2不填充、MPEG2字补零、STM32小端字补零、IEEE四种32位CRC配置；一条命令内锁定已匹配的配置。图文件本身始终额外验证明确的IEEE CRC32。手机上实际使用的配置尚待实测，不把仿真当作与所有SDK版本的认证。
- 轻量命令成功响应与段ACK严格区分（应用CMD_OK=81；段SEGMENT_OK=80）。只有图完整有效、手机读走最终应用响应后，才提交刷新；App立即关闭Mailbox时通过RF_GET_MSG状态确认已读。
- 命令06/02可将已验收E6IM文件完整读回，发送使用无段ACK的合法FTM数据帧；文件CRC仍可验证读回内容。该路径已软件仿真，手机UI能否保存文件需实际界面验证。
- image_link.c只在PB3/GPO通知后处理Mailbox，在忙/NACK时5ms间隔有限重试，无空闲I²C轮询。RF_PUT_MSG、RF_GET_MSG和RF_WRITE分别用于传输、完成通知及旧EEPROM命令。
- ST25DV04K配置GPO=F0、MB_MODE=1、MB_WDG=0，配置相同不重复写EEPROM。开始配置前将MB_EN清0。开机保留Mailbox关闭，App开启后自动进入FTM；App关闭后恢复旧的EEPROM指令处理。Mailbox启用时不写EEPROM回执。
- epd_show_frame复用此前实测的正常屏幕初始化/OTP波形，直接发送已验证的28,800字节像素。没有BUSY旁路或自动翻转极性。

## 验证与边界

Debug Flash 43,492字节，Release Flash 37,804字节。链接器RAM占用32,288字节，含512字节堆预留和2,048字节栈预留；36KB SRAM尚有4,576字节余量。image_link_service静态栈312字节，无递归或动态分配。该余量不是示波器电源测试或运行时最大栈深度实测。

软件测试使用独立Python发送器产生249字节帧/4951字节段，将完整E6IM文件输入真实Cortex-M0+代码：段CRC错误重传、已确认中段/最终段重发、四种CRC配置、完整H2R读回、图文件CRC拒绝、错误长度/超大声明拒绝、半包超时恢复、空闲零I²C、App读取完响应立即关闭Mailbox、完整SPI像素字节流一致均通过。保留原有色块、1234、UID、BUSY异常与简单协议测试。

手机实测尚未完成。首次尝试时未检测到ST-Link，用户随后重新连接，但探针可识别、MCU核心ID读取失败，未开始擦写。用户调整后重试成功，已通过ST-Link烧录r5 Debug ELF、读回校验通过并执行软件复位，回执见FLASH_R5_2026-11-14.json。当前已请用户开启Mailbox，通过FTM固件更新入口发送图1，等待结果；测试时没有连接GDB。

操作说明和手机包见output/image_test/PHONE_README.md、ST25_NFC_sixcolor_images.zip。首轮使用外接3.3V。整图在线圈供电下的能量/断场恢复尚未实测。

## r5.1 实板初始化修复

首轮用户在口令提交后收到“命令失败”，进度0。恢复ST-Link后读取MCU RAM：nfc_gpo_ready=0、nfc_init_error=-1、g_image_debug全零，证明未进入FTM包接收，而不是图文件校验失败。

在手机移开、运行到启动色块之前，逐项I²C核对：GPO静态C0、MB_MODE=0、MB_WDG=7、MB_CTRL_Dyn=0均可读；再次写MB_EN=0返回NACK。分别写GPO=F0、MB_MODE=1、MB_WDG=0则均成功。原程序无条件先写“关闭Mailbox”，在本板初始MB_MODE=0时终止了整套初始化。

r5.1修复st25_mailbox_enable：先读MB_CTRL，已是目标状态则跳过写入。补充模拟本板MB_MODE=0时拒绝MB_EN写的测试，并验证从工厂状态完整初始化成功。没有放宽CRC、图像格式或传输校验。

已编译、烧录r5.1并读回校验通过。实板g_debug地址0x20000080读到ready=1、0x20000084错误码=0，版本指针也与本次ELF一致。读取后软件复位，已让用户等待色块结束再次传输同一图1文件。尚待本轮手机结果。

用户随后明确确认“已传完并显示IMAGE 1”：图1 28,832字节端到端实板传输与刷新通过，测试期间MCU独立运行、未连接GDB。已发起图2不复位连续更新测试，等待结果。r5.1资源：Debug Flash 43,544字节，Release Flash 37,832字节，RAM仍为32,288字节（含预留堆栈）。

图2随后也由用户确认“已更新IMAGE 2”，两张完整数据包连续传输和显示均通过，过程中未复位MCU，右侧图案按数据包改变。没有在确认后再次复位，实物保留图2。整图H2R读回仅完成软件仿真，未要求用户再做手机读回；线圈供电仍未验收。

原始诊断见r5_init_diagnosis.log。图像数据文件与r5完全相同，不需要重生成手机文件。

## 协议依据

- [ST UM2062 Appendix A](https://www.st.com/resource/en/user_manual/um2062-firmware-for-the-st25dvdiscovery-boards-stmicroelectronics.pdf)：FTM包、段ACK与ST Light命令。
- [ST AN5512](https://www.st.com/resource/en/application_note/an5512-st25-fast-transfer-mode-embedded-library-stmicroelectronics.pdf)：FTM组件及接口。
- [ST25DVxxK DS10925](https://www.st.com.cn/resource/en/datasheet/st25dv04k.pdf)：GPO、Mailbox配置、MB_EN与EEPROM写互斥、一次性消息写入与读末字节释放。
- 在reference/st25ftm_upstream保存ST版权的历史ST25FTM实现作接口核对，来自公开npm包@displode/stsw-st25r-lib-xpack 0.3.3-s1.4.0；生产固件未编译这些旧库文件。旧接收库带长度字段的总长度字节序等实现差异未直接照搬。
