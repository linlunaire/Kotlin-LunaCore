# Kotlin LunaCore

Minecraft 模组共用的 Kotlin 运行库与少量 JVM 工具。目前提供 Minecraft 26.2 的 Fabric、NeoForge 构建，内置 Kotlin stdlib 2.4.0。安装后不添加方块、物品或玩法。

## 安装

在客户端和服务端的 `mods` 目录中，放入与加载器匹配的 **一个** JAR。

| 环境 | 文件 |
| --- | --- |
| Java 25、Fabric Loader 0.19.3+ | `Kotlin-LunaCore-fabric-26.2-0.3.0.jar` |
| Java 25、NeoForge 26.2.0.75+ | `Kotlin-LunaCore-neoforge-26.2-0.3.0.jar` |

本地构建产物在 `build/release/`。本项目原名 Transit Core，模组 ID 仍为 `transit_core`，JVM 包名也保持不变。旧版与新版不能同时安装。

LunaCore 自身不依赖 MTR、Architectury API 或其他 Kotlin 语言模组。ANTE、JCM 各自仍是 MTR 附属模组。Luna Reforged 随包提供的 1.21.1 兼容构建在该项目中维护，使用 Java 21；本仓库的原生构建目标是 26.2。

## 开发者用法

### 编译依赖

本地开发可以引用构建出的加载器 JAR，例如 Fabric 26.2：

```groovy
dependencies {
    implementation files('libs/Kotlin-LunaCore-fabric-26.2-0.3.0.jar')
}
kotlin {
    coreLibrariesVersion = '2.4.0'
}
```

使用需要重映射的旧版 Loom 时，对加载器 JAR 使用 `modImplementation`。在 NeoForge 项目中换成对应的 NeoForge JAR。消费者应将 LunaCore 作为外部模组依赖，避免再次打包 LunaCore 或 Kotlin stdlib。

纯 JVM 工程可在本仓库执行 `./gradlew :core:publishToMavenLocal`，然后通过 `mavenLocal()` 使用 `io.github.linlunaire:transit-core:0.3.0`。这个坐标用于本地发布示例，不表示已经上传到公共 Maven 仓库。纯 JVM 库、`-checks.jar` 和 `-verification.jar` 都不是可安装的模组。

### 声明模组依赖

Fabric 的 `fabric.mod.json`：

```json
{
  "schemaVersion": 1,
  "entrypoints": {
    "main": ["example.ExampleMod"]
  },
  "depends": {
    "transit_core": ">=0.3.0"
  }
}
```

上面是需要合入现有文件的片段。入口使用普通、可实例化的 JVM 类：

```kotlin
package example

import net.fabricmc.api.ModInitializer

class ExampleMod : ModInitializer {
    override fun onInitialize() {
        // 注册本模组的内容。
    }
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

NeoForge 入口同样使用普通 Kotlin 类并标注 `@Mod("your_mod_id")`。LunaCore 不提供 `kotlin` language adapter，因此不要将 FLK 的 adapter 或 Kotlin `object` 入口配置直接套在这里。

### 随包运行库

| 库 | 版本 | 许可证 |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.0 | Apache-2.0 |

运行库通过加载器的嵌套依赖机制分发，保留原 Maven 身份、类名和字节码。LunaCore 不附带 `kotlin-reflect`、coroutines、serialization，也不声明 FLK/KFF 的模组 ID。其他模组需要 FLK/KFF 时，应保留它们声明的依赖。

现有检查会调用 Fabric 和 NeoForge 的实际依赖解析器，验证与 FLK/KFF、较新 stdlib 并存时只选择一个兼容的 Kotlin 运行库。这是加载器解析验证；具体整合包仍需要启动游戏检查。

## API 与模块

`core` 包含三个不依赖 Minecraft 的策略类，提供 Java 友好的接口：

- `FrameGeometryCache`：按对象身份缓存帧几何数据。当前帧持有的资源不会为满足预算而被提前回收，因此预算是软上限。
- `FrameMembership`：跟踪帧成员变化，避免稳定场景中重复重建集合。
- `BoundedTaskDispatcher`：同时限制正在执行的任务与等待上传的已完成结果，在所有者线程处理完成回调。消费者决定线程池寿命、并发数和准入上限。

`fabric` 与 `neoforge` 负责元数据和打包，使用相同的 core 实现。API 边界及扩展原则见 [架构说明](docs/architecture.md)。历史映射源码留在固定的 `transit-core-0.1.0` 归档中，恢复步骤与校验值见 [旧版构建说明](docs/legacy-mappings.md)。

## 构建与检查

使用 JDK 25 和仓库内的 Gradle 9.5.1 wrapper：

```sh
./gradlew build
```

编译器为 Kotlin 2.4.20，运行库基线为 2.4.0。检查覆盖 Java 互操作、缓存身份与回收、异常清理、稳定路径分配、1,000 个有界任务、关闭竞争，以及最终 JAR 的运行库和许可证文件。测试与验证代码不会进入运行库模组。构建成功不代表 FPS、服务器容量或所有第三方模组的兼容性已经验证。

更多打包依据和解析器检查见 [Kotlin 运行库说明](docs/research/kotlin-runtime-packaging.md)。0.3.0 只调整发行许可与说明，保留 0.2.1 的 API 和运行行为。

## 许可证与致谢

从 **0.3.0** 起，LunaCore 采用 **LGPL-3.0-or-later**。完整条款见 [LICENSE](LICENSE) 和 [COPYING](COPYING)，来源说明见 [NOTICE.md](NOTICE.md)。已发行 MIT 版本的授权不变，MTR / ANTE 来源代码的原版权与许可通知保留在 [LunaCore-MIT.txt](licenses/LunaCore-MIT.txt)。Kotlin stdlib 保持其 Apache-2.0 许可和 JetBrains 声明。

README 的依赖、入口与随包库章节参考了 [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) 的组织方式。此项目独立维护，不代表 Fabric、NeoForge、MTR 或 FLK 官方。
