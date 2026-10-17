# Dolby Control 0.6.1

已安装在这台 Samsung S23 上的独立音效应用。KernelSU 提供应用自己的 Root 授权；LSPosed 只作用于 `com.sec.android.app.soundalive`，为三星杜比页面增加 Spatial Audio。旧 `dolby_control_lab` KernelSU WebUI 模块已卸载，备份保留在手机 Root 私有备份目录。

## 日常使用

- 档位在系统“杜比全景声”页面选择。Music 和 Spatial Audio 的手选会跨暂停、继续、切歌和页面重开保持；手选“自动”解除保持。
- 应用首页只有增强处理和声音调节入口。关闭增强保留空间渲染，且不重置数值设置。
- 声音调节显示当前档位自己的参数；共用强度和目标值明确标记为所有档位共用。修改先暂存，点“应用调整”后批量写入并回读。
- 三星预设恢复经过验证的原厂参数。Spatial Audio 保留自己的耳机渲染调校，没有将它替换成 Music。

原厂 Music 开启智能均衡、关闭对白增强与其元数据引导；原厂 Spatial Audio 相反。两者还使用不同的耳机渲染参数。当前用户的“关闭增强”会覆盖对应增强开关，因此不应将关闭增强后的表现当作原厂默认。

## 已验证

- APK 签名验证与安装文件 SHA256 一致；LSPosed 已启用，作用域仅三星音效。
- 44 条固定参数均与这台手机的 SYSTEM 库逐项核对：35 个增强开关、5 个档位音量均衡强度、4 个共用数值。未将耳机或扬声器渲染记录加入写入范围。
- 21 项 C++ 事务和参数解析测试通过，包括回滚、并发改动、进程身份改变、非法档位与越界值拒绝。
- AirPods、Apple Music《克卜勒》Dolby Atmos 播放：Music 和 Spatial Audio 的暂停、继续、切歌、音乐页面重开及系统杜比页面重开保持验证通过。
- Spatial Audio 的关闭增强、独立智能均衡/对白增强、音量均衡强度、共用对白强度和输出目标均观察到对应 DSP setter。调整后原设置已恢复。
- 关闭杜比后播放/重新断言不会启用杜比；重新开启恢复手选；手选自动后播放不会被强制切到 Music。
- 相同 APK 覆盖安装触发真实系统包更新事件，独立后台重启并恢复全部 44 项数值与手选；新进程绑定新 APK 路径。应用进程冷开耗时约 276 ms，不包括后台参数读取。

证据位于 `build/spatial-live-evidence.json`、`build/lifecycle-live-evidence.json`、`build/playback-profile-2.json`、`build/playback-profile-8.json`、`build/installed-integrity.json`。最后两份播放保持报告来自先前的同一切档实现，空间档写入和生命周期报告来自当前版本。

DDP/Atmos 分支会强制关闭实际音量均衡；保存开关为开不代表播放时 DSP 一定开启。共用数值会影响所有档位。尚未通过整机重启或耳机断连重连验证；已验证的后台重启采用系统包更新事件。

## 构建和边界

仅适配已校验的 S23 当前 Android 16 固件与 SYSTEM 库。原生 helper 在写入前验证固件、库哈希、映射身份、进程启动时间、固定记录前缀和数值范围；不修改系统文件或重启音频服务。

已有 Android SDK 37、Build Tools 36、NDK 30 与 Android Studio JBR 环境下：

```powershell
python tools/verify_table_spec.py
python tests/test_table_transaction.py
python tools/build_native.py
python tools/build.py
```

APK 在本任务的 `outputs/Dolby_Control_v0.6.1.apk`。`private/` 保存本地升级签名材料，不能公开、删除或替换。`vendor/xposed-api-101.jar` 仅编译使用，不打入 APK。

后台状态独立位于 `/data/adb/dolby_control_app`。Root socket 只接受应用自身和核验过的三星音效进程；三星音效只能查状态、选择档位或恢复手选，不能运行任意 Root 命令。开机与包更新共用受保护广播接收器，仅在首次成功启用后启动。
