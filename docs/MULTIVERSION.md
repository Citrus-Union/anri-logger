# Anri Logger 多版本开发

使用 Stonecutter **0.9.8**，参考官方 Fabric 模板：
https://github.com/stonecutter-versioning/stonecutter-template-fabric

目前只发布与验证 **Minecraft 26.1.2 / Fabric**。Stonecutter 负责共享源码的版本切换与处理，不会自动修复 Minecraft API 差异。

## 日常命令

在工程根目录执行（Windows；其他系统改用 `./gradlew`）：

```powershell
# 所有已声明版本：编译、单元测试、收集 JAR
.\gradlew.bat buildAndCollect
# 所有已声明版本：运行游戏测试
.\gradlew.bat runGameTest
# 只构建/测试一个版本
.\gradlew.bat :26.1.2:buildAndCollect :26.1.2:runGameTest
# 切换共享 src 为一个版本对应的形态
.\gradlew.bat "Set active project to 26.1.2"
# 提交前恢复 vcsVersion 指定的源码形态
.\gradlew.bat "Reset active project"
```

根目录按名称选择的 Gradle 任务会执行所有具有该任务的版本节点。CI 使用 `buildAndCollect runGameTest`，收集根目录 `build/libs/`。

## 增加版本

1. 在 `settings.gradle.kts` 的 `versions("26.1.2")` 中加入实际要支持的版本。保持 `vcsVersion` 为团队统一的源码基准版本。
2. 在 `stonecutter.properties.toml` 增加以版本名为键的节，填写该版本的 `mod.mc_compat`、`deps.fabric_api`、`java.release`。不要沿用另一个 Minecraft 版本的 Fabric API。
3. 运行对应的 `Set active project to ...` 任务，在共享 `src/` 中适配 API；使用 Stonecutter 条件注释保留不同版本实现。不要手动编辑 versions 下生成的源码。
4. 运行新版本 `:版本:buildAndCollect :版本:runGameTest`，再运行全版本测试。既有测试也是未来适配的行为基线。
5. 恢复 `Reset active project`，提交共享源码、三个 Kotlin 构建脚本和 TOML。无需提交生成的 versions 构建目录。

条件分支示意（说明格式，不是新增受支持版本）：

```java
//? if >=26.1 {
// 当前未混淆 Minecraft API 的实现
//?} else {
/* 旧版本实现；非活动分支由 Stonecutter 注释保存 */
//?}
```

当前构建采用面向 26.1+ 的 `net.fabricmc.fabric-loom` 插件和非混淆依赖配置。如果今后回移到 1.21.x 等混淆版本，除了 Java 代码，还需接入兼容 Loom 插件/映射处理（官方模板使用 loom-back-compat），调整依赖配置、Mixin 兼容级别和发布任务；仅新增一个版本号不足以完成旧版支持。

## 产物与测试隔离

- 本版本 JAR：`versions/26.1.2/build/libs/anri-logger-1.2.3+mc26.1.2.jar`。
- 汇总 JAR：`build/libs/`。
- 单元测试：`versions/26.1.2/build/reports/tests/test/index.html`。
- 游戏测试：`versions/26.1.2/build/run/gameTest/`。
- 生产数据库路径依旧是服务端的 `config/anri-logger/history.db`；Stonecutter 不参与运行时，不会拆分数据库。

所有共享源码集均由 Stonecutter 处理，包括 main、test 和 gametest；发行 JAR 只包含 main。
