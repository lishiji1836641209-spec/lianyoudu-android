# 饮食、设备身份、自重输入：检错能力回归

显式启用的专项回归，默认构建和现有真机测试入口不变。新增测试类为 `CapabilityInstrumentation`；`-PcapabilityAudit` 使用独立包名 `com.lianyoudu.app.mutationtest`。它不能与 `deviceAudit` / `isolatedAudit` 同时指定，测试入口也拒绝非模拟器和非专用包。

推荐从项目目录运行：

```sh
python3 scripts/capability/run.py --work-dir ../capability-verification-new
```

工作目录必须不存在且位于 android-app 外。脚本仅复制构建输入和变异定义，在独立目录构建，另建 Android 35 ARM64 模拟器 `CapabilityMerged`（端口 5580/5581）和独立 ADB 服务（5041，USB 白名单为不存在的设备）。端口被占用就停止，不复用、重启或清空已有设备。SDK、Gradle/JDK 程序只读复用项目旁 tools；依赖复用 GRADLE_USER_HOME 或 tools/gradle-cache，首次运行需要联网下载，已缓存时可加 --offline。每轮通过 avdmanager 从已安装的 API 35 镜像创建 AVD。运行成功后清理本轮构建副本、AVD 和模拟器临时目录，保留 evidence；失败时留现场供定位。源码复制前后及验证结束进行指纹核对。

执行顺序：核对默认/真机/旧隔离配置 → 正常版本 → 六个独立故障 → 恢复代码并重测 → 停止自己启动的模拟器及连接服务。只在副本中故意改坏 MainActivity。测试源码在各次构建间保持相同；编译或安装失败不算检出。异常也会恢复副本源码；故障阶段异常会尝试重建并验证正常版本。

检查覆盖：

- 饮食：未记录、零热量、未知热量、123 千卡记录、删除最后一餐；核对首页摘要、营养总量、每日能量及跨页返回的实际文案和可见性。餐次图表的合法零值不误报为总摄入错误。
- 设备：已记住 A 与附近 B 的身份、A 只出现一次、点击 B 后连接目标地址确为 B。
- 自重：同一暂停训练中的徒手深蹲与哑铃划船，按卡片检查输入数量、单位和可编辑性；跨页返回及数据库重载后复查，输入次数并完成组后核对保存结果。

六个故障定义在 mutations.json：三处未知摄入错误显示零、设备筛选保留错误对象、显示 B 却连接 A、自重恢复重量输入且去除描述标签。锚点不再唯一时脚本失败，要求人工审查，不能悄悄跳过。

结果位于工作目录 evidence/：source-sha256.json、配置验证日志、baseline.run.log、各故障的 patch/build.log/run.log、restored.run.log、results.json、最终源码与 APK 指纹。Android instrumentation 命令退出成功本身不算通过，脚本检查明确 PASS/FAIL 与预期断言。

本套测试只验证已列场景。数据由夹具准备，点击通过视图 performClick；数据库重载不等于杀进程重启。模拟设备地址不证明真实蓝牙连接质量，文字可见性不证明逐像素视觉效果。本套结果不能覆盖玻璃动画、心率锁屏或性能放行条件。不要在原项目目录直接运行故障修改，也不要将此包安装到手机。
