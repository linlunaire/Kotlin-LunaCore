# Kotlin LunaCore

为 Minecraft 模组提供共享的 Kotlin 运行库和基础工具，支持 Fabric 与 NeoForge。当前版本面向 Minecraft 26.2，内置 Kotlin stdlib 2.4.0。

## 安装

需要 Java 25。在客户端和服务端的 `mods` 文件夹中放入对应加载器的 JAR：

| 加载器 | 文件 |
| --- | --- |
| Fabric Loader 0.19.3+ | `Kotlin-LunaCore-fabric-26.2-0.3.0.jar` |
| NeoForge 26.2.0.75+ | `Kotlin-LunaCore-neoforge-26.2-0.3.0.jar` |

每个实例只安装一份 LunaCore。模组 ID 为 `transit_core`。

Minecraft 1.21.1 的兼容构建由 [辉月重铸](https://github.com/linlunaire/Luna-Reforged)提供，使用 Java 21。

## 开发接入

将对应的 LunaCore JAR 放入项目的 `libs` 目录，并添加编译依赖：

```groovy
dependencies {
    implementation files('libs/Kotlin-LunaCore-fabric-26.2-0.3.0.jar')
}
kotlin {
    coreLibrariesVersion = '2.4.0'
}
```

NeoForge 项目使用对应的 NeoForge JAR；需要重映射的旧版 Loom 项目使用 `modImplementation`。LunaCore 和 Kotlin stdlib 由前置提供，无需再次打包进你的模组。

在现有模组配置中声明依赖。Fabric 的 `fabric.mod.json`：

```json
"depends": {
  "transit_core": ">=0.3.0"
}
```

NeoForge 的 `neoforge.mods.toml`：

```toml
[[dependencies.your_mod_id]]
modId = "transit_core"
type = "required"
versionRange = "[0.3.0,)"
ordering = "NONE"
side = "BOTH"
```

入口使用普通 Kotlin 类：Fabric 实现 `ModInitializer`，NeoForge 使用 `@Mod`。LunaCore 不提供 Kotlin `object` 入口适配器；需要 FLK 或 KFF 的其他模组仍应安装各自的前置。

随包运行库为 `kotlin-stdlib`。Reflection、coroutines 和 serialization 等额外库需由使用它们的模组声明依赖。

## 基础工具

- `FrameGeometryCache`：缓存帧几何数据并管理资源回收。
- `FrameMembership`：跟踪帧成员变化。
- `BoundedTaskDispatcher`：限制并发任务与待处理结果的数量。

接口及用法见 [架构说明](docs/architecture.md)。

## 构建

使用 JDK 25 和仓库内的 Gradle wrapper：

```sh
./gradlew build
```

模组 JAR 输出至 `build/release/`，构建时会运行 API、打包和加载器依赖解析检查。

纯 JVM 项目可先执行 `./gradlew :core:publishToMavenLocal`，再通过 `mavenLocal()` 引用 `io.github.linlunaire:transit-core:0.3.0`。

## 许可证

[LGPL-3.0](LICENSE)
