# VS Code CMake Path NUL 报错处理

错误：Node 创建 CMake 子进程前拒绝 options.env['Path']，因为它包含 NUL 字符。该错误发生在 CMake 配置命令实际执行之前，与 STM32 上的重启、NFC 命令或线圈供电无直接关系。

检查结果：当前终端进程、Windows User/Machine 的 Path 均没有 NUL；项目 settings、presets、CMakeCache 文件也没有 NUL。用户级 cmake.environment.PATH 包含 ST 扩展自动注入的工具目录和动态 PATH 展开。没有在当前命令行环境复现 VS Code 扩展宿主内部的 NUL 来源，因此不将其归因于某个磁盘目录或声称某种变量语法必然错误。

项目内修复 `.vscode/settings.json`：

- cmake.cmakePath 指向已安装的原生 CMake 4.4.0，不再通过 cube-cmake 包装器。
- 删除 configureArgs 中强制 CMAKE_COMMAND=cube-cmake 的覆盖。
- 项目级 cmake.environment.PATH 明确列出当前 GCC、Ninja、CMake 和 Windows 系统目录，不引用 PATH 自身。
- CMAKE_MAKE_PROGRAM 明确指向当前 Ninja。
- 保留用户全局配置，避免影响其他项目。

已验证：JSON 可解析、展开后的所有路径存在、无 NUL；Node child_process.spawnSync 使用此 Path 成功启动 CMake；使用同一项目环境 CMake Debug 配置与构建成功。固件逻辑和 HEX 内容没有修改。

VS Code 界面端尚需用户执行 Ctrl+Shift+P → Developer: Reload Window（开发人员：重新加载窗口），然后 Ctrl+Shift+B 构建。调试 MCU 时选择 STM32G070: ST-Link Debug 后 F5。本轮未通过界面实际触发配置，因此不能把命令行验证当作界面已验收。

本项目配置使用当前本机安装的 bundle 版本；未来移除这些版本后需更新 settings 路径。tools/build.ps1 仍可自动发现本机工具并构建。

参考：[CMake Tools 设置说明](https://github.com/microsoft/vscode-cmake-tools/blob/main/docs/cmake-settings.md)、[ST 对全局 cmake.environment 注入行为的说明](https://community.st.com/stm32cubeide-for-visual-studio-code-mcus-133/stm32cube-cmake-support-incorrectly-configures-several-cmake-tools-settings-160555)。
