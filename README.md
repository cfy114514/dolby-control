# Dolby Control

[![Build and test](https://github.com/cfy114514/dolby-control/actions/workflows/ci.yml/badge.svg)](https://github.com/cfy114514/dolby-control/actions/workflows/ci.yml)

为三星 S23 编写的独立音效应用。KernelSU 提供应用自己的 Root 权限；LSPosed 只作用于三星 SoundAlive，在系统“杜比全景声”页面加入 **Spatial Audio** 档位。无需旧的 KernelSU WebUI 模块。

## 功能

- 系统页面切档，手选 Music 或 Spatial Audio 后，暂停、继续、切歌和页面重开保持选择。手选“自动”解除保持。
- 首页只有“关闭增强 / 三星预设”和声音调节入口。关闭增强保留空间渲染及数值设置；三星预设恢复全部原厂参数。
- 当前档位独立调节增强开关与强度；共用参数明确标注。调整先暂存，再一次批量应用。Spatial Audio 保留自己的渲染参数。
- Root 后台独立保存和恢复设置；不修改系统文件，不主动开启已关闭的杜比。

Music 与 Spatial Audio 都使用现有空间处理器。原厂 Music 开启智能均衡，Spatial Audio 开启对白增强及其元数据引导，两者的耳机渲染参数不同。这不等同于 Apple 个性化空间音频或头部追踪。DDP/Atmos 播放时，系统会强制关闭实际音量均衡；保存为开启不代表 DSP 正在使用它。

## 兼容范围

目前只支持已核对的 **SM-S9110 / Android 16 / SoundAlive 16.1.32** 固件。适配器按固件指纹、SYSTEM 库哈希、进程身份、记录前缀及数值范围校验；其他固件会拒绝写入。具体版本和 44 个可写槽位见 [`table_spec.h`](src/native/table_spec.h)。不能仅去掉校验来支持其他机型。

安装自行签名的 APK，在 KernelSU 中给“音效”授予 Root，并在兼容现代 API 101 的 LSPosed 中启用模块，作用域只选 `com.sec.android.app.soundalive`。重新打开三星杜比页面，让新钩子载入。后台设置保存在 `/data/adb/dolby_control_app/`，与旧模块目录独立。

## 构建

需要 Python 3.11+、JDK 17+，以及 `project.json` 固定的 Android Platform 37.0、Build Tools 36.0.0、NDK 30.0.14904198。支持 Windows / Linux 构建；macOS 路径发现已支持，尚未验证。没有 Gradle 或运行时第三方界面库。

```sh
python tools/fetch_deps.py
python -m unittest discover -s tests -v
python tools/build.py --unsigned
python tools/check_package.py
```

下载依赖与构建分离。`fetch_deps.py` 只从 Maven Central 下载锁定的编译期 API，并核对 AAR/JAR 的 SHA256。后续构建无需下载；每次会先重新编译原生 helper，避免误装旧二进制。

SDK 自动读取 `DCL_ANDROID_SDK` / `ANDROID_SDK_ROOT` / `ANDROID_HOME`；JDK 读取 `DCL_JAVA` / `JAVA_HOME`；NDK 可用 `DCL_ANDROID_NDK` 指定。没有环境变量时查找常见本机路径。输出位于 `dist/`，构建身份位于 `build/identity.json`。

正式签名默认复用本地 `private/signing.p12` 与 `private/signing-password.txt`。新开发者第一次创建自己的签名可运行：

```sh
python tools/build.py --init-signing
```

已有签名时使用 `python tools/build.py`。密钥缺失会失败，不会静默换签；其他开发者生成的签名不能覆盖原签名应用。CI 只构建未签名 APK，不持有正式密钥。`private/`、构建物、设备证据、原厂库均不提交。

## 验证与开发

[`docs/TESTING.md`](docs/TESTING.md) 区分宿主回归、原厂 ELF 校验与真机验收。原厂库不分发，需自行提供：

```sh
python tools/verify_table_spec.py --library /path/to/original-libspatializerparamstorage.so --require-library
```

缺少原厂库时会明确显示 `SKIP`，不会算作已验证。0.6.1 曾在 S23、AirPods 与 Apple Music Dolby Atmos 播放中验证；本轮改动的结果和限制见 [`docs/REVIEW.md`](docs/REVIEW.md)。

源码结构：`src/io/github/dcl/` 为独立后台及音效适配；`src/io/github/dcl/app/` 为原生界面、Root 客户端与 SoundAlive 钩子；`src/native/` 为固定表事务；`tests/` 为宿主回归；`tools/` 为构建和开发验收工具。

项目自有源码使用 MIT 许可证，第三方权利见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。本项目与三星、Dolby、Apple 无隶属关系。
