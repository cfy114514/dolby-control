# 验证

## 宿主检查

```sh
python -m unittest discover -s tests -v
python -m compileall -q tools tests
python tools/build.py --unsigned
python tools/check_package.py
```

原生测试编译并调用实际 C++ 事务/解析代码：Windows 使用 NDK Clang 生成 DLL，Linux 使用宿主 C++ 编译器生成共享库。它们不需要手机或 Root。后台测试编译并运行实际纯 Java 辅助类，检查时间限额、队列取消和锁行为。CI 在 Windows 与 Linux 各运行一次。

## 原厂库校验

原厂 ELF 是开发者自行取得的只读输入，不包含在 Git 中：

```sh
python tools/verify_table_spec.py --library /path/to/original-libspatializerparamstorage.so --require-library
```

也可设置 `DCL_STOCK_LIBRARY`。工具检查精确哈希、44 个槽位、完整前缀、原厂值及范围，确认渲染记录未进入可写集合。未指定文件时明确跳过；CI 不把这一项声明为通过。

## 真机验收

下列操作只用于已适配、已安装及已授权的测试手机。先保存入口设置，准备 Apple Music Dolby Atmos 曲目并确认实际输出设备。ADB 多设备时设置 `DCL_ADB_SERIAL`；构建/部署工具不会自行选择一台设备。

```sh
python tools/build.py
python tools/build_probe.py --deploy
python tools/device_probe.py settings
python tools/playback_check.py 2 --execute
python tools/playback_check.py 8 --execute
python tools/spatial_check.py --execute
python tools/lifecycle_check.py --execute
```

播放检查要求先在系统页选到对应档位并正在播放。空间参数检查要求当前处于 Spatial Audio；它会写入多组参数，并在 `finally` 中恢复入口参数。生命周期检查会操作原生杜比界面并覆盖安装当前已签名构建；坐标仅适用于原测试 S23 的 1080×2340 布局，执行前必须核对当前页面和布局。中途断线或恢复失败时保留证据，并手动核对/恢复入口档位和杜比开关。不要在日常手机上盲跑这些开发脚本。

额外人工回归：

1. 暂改一个参数后旋转或重建 Activity：草稿保留，未点击应用前不写入。
2. 暂改后去系统页换档，再回来：重新读取上下文，陈旧草稿不能覆盖新档位。
3. 读取或应用失败后：旧表不可继续应用，重新读取恢复。
4. 离开 SoundAlive 页面后停止轮询，返回后恢复；不重复插入第五行。
5. 区分“保存”“等待激活”“实时未确认”，不能把参数表写入等同于实际 DSP 效果。
6. 杜比关闭时，播放、手选保持和后台重启不能主动打开它；Auto 解除手选保持。
7. 需要整机重启、耳机断连重连的验证应单独记录；后台进程重启不能代替这两项。

工具输出写入忽略的 `build/`。这些记录可能含歌曲和设备运行信息，不要直接上传到公开仓库。
