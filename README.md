# Anri Logger · Minecraft 26.1.2 / Fabric

只负责记录和查询的服务端审计模组。主命令 `/al`，可配置启用 `/lg`。客户端无需安装；也可以安装到客户端用于其内置服务器。

## 安装

1. 使用 Java 25、Minecraft **26.1.2**、Fabric Loader **0.19.3 或更高版本**，并安装适用于 26.1.2 的 Fabric API（开发验证版本为 0.155.3+26.1.2）。
2. 将 `anri-logger-1.2.3+mc26.1.2.jar` 放入服务端 `mods/`。不要安装 `-sources.jar`。MVStore 已内嵌，无须额外下载数据库驱动或部署数据库服务。
3. 首次启动自动生成：

```text
config/
└── anri-logger/
    ├── config.json    # 配置，不是数据库
    └── history.db     # 唯一数据库：事件、字典、索引、世界映射、会话元数据
```

运行期间和正常关服后均只有一个数据库文件。没有 `.wal`、`.shm`、`.journal`、SQL trace、分片或外部索引文件。它是 MVStore 二进制文件，不能用 SQLite 浏览器打开。

## 记录内容

| 类型 | 行为 |
|---|---|
| `block-break` | 玩家真正成功破坏的方块；不记录被阻止或未完成的破坏尝试 |
| `block-place` | 方块物品的实际放置 |
| `block-use` | 成功的右键方块交互，如开关、开门、使用工具、点燃 TNT |
| `block-change` | 由该操作引发的状态变化，保留前后方块状态；每次玩家操作最多记录 10 次连带方块变化，包括落沙、爆炸、红石和活塞 |
| `container-open` | 通过方块交互打开容器界面 |
| `item-insert` / `item-remove` | 玩家放入/拿出的物品与准确数量；包括物品组件，能区分改名、附魔等差异 |
| `entity-trigger` | 归因到玩家的落沙、点燃的 TNT、投射物生成 |
| `explosion` | 可以追溯到玩家的爆炸及其产生的方块变化 |

容器支持标准容器菜单中的箱子、双箱、木桶、潜影盒、漏斗、熔炉、酿造台、载具容器等实际库存，以及末影箱。左右键、Shift、拖拽、数字键交换、丢弃和双击收集统一按照**单次点击前后的实际差额**计算。双箱定位到实际取放的那一半。相同物品仅在同一物理容器内整理、净数量不变时不会产生取放记录。玩家自身背包、工作台等虚拟输入/输出栏不作为储物容器记录。

另外处理了饰纹陶罐的直接存入、营火放食物、讲台放书和通过按钮取书。漏斗后台传输、熔炉自动加工、开箱生成战利品、界面同步不会作为玩家存入记录。未生成战利品的陶罐首次交互不做物品差额归因，以免审计读取提前生成战利品或把生成物品误当成存入。

每条记录包含独立编号、现实时间、游戏 tick、存档身份、启动会话、维度、坐标、玩家 UUID 与当时名字、动作、对象标识、数量、详细状态/物品组件、因果链 UUID。

**1.2.0 的连带记录上限：**每次玩家操作的所有分支共用 10 次额度，第 11 次及之后的连带方块变化不再记录，也不再向新的 scheduled tick / 活塞事件传播来源。重复改变同一个坐标也消耗额度，防止红石时钟无限记录。玩家直接破坏、放置、使用目标方块及物品存取继续记录，不消耗连带额度；新的玩家操作获得独立额度。此限制只影响审计记录，不阻止游戏中的实际更新，也不删除旧记录。

## 命令

```mcfunction
/al
/al help
/al inspect
/al inspect on
/al inspect off
/al inspect 100 64 -200
/al i
/al container
/al container 100 64 -200
/al search
/al search source:Steve after:1d range:32
/al search action:block-break object:minecraft:stone after:2h
/al search action:item-remove range:16
/al search action:container source:Steve after:1d range:32
/al search source:12345678-1234-1234-1234-123456789012 range:@global world:@all
/al search cause:完整的因果链UUID range:@global world:@all
/al search session:完整会话UUID range:@global world:@all
/al s source:Steve after:1h
/al page next
/al page prev
/al page 2
/al show 123
/al status
/al sessions
```

- `/al inspect` 切换检查模式，左右键点击**被点击的方块**查询；不会破坏或使用目标。已加载的双箱会同时查询两侧，避免点击一半却漏掉另一半的存取记录。每次点击独立返回结果，不受普通命令的冷却或正在进行的查询限制，不覆盖之前的点击请求。坐标版本支持相对坐标，不要求区块已加载。
- `/al container` 查询视线内 6 格以内目标位置的物品存取；坐标版本不要求容器仍然存在。只显示 `item-insert` 与 `item-remove`，不会混入打开容器或方块状态变化。已加载且仍连接的双箱，查询任意一半都会合并两侧历史，并提示「包含双箱两侧」；记录中的坐标仍保留实际存取的那一半。两侧使用精确位置索引，不包含附近其他容器。箱子已拆开、移除或区块未加载时按指定坐标查询，可分别查询两个坐标或使用范围搜索。
- Carpet TIS Addition 的 `largeBarrel` 为可选兼容：**仅在 TIS 已安装且当前规则为 true 时**，检查模式、`/al inspect` 和 `/al container` 会合并实际相连的大木桶两侧历史，并提示「包含大木桶两侧」。支持水平和垂直连接，保留记录的实际木桶坐标。未安装 TIS、规则关闭、木桶未连接或另一侧区块未加载时，仍只查指定坐标；不会主动加载区块。每次新的检查读取当前规则，分页沿用首次查询的范围与快照。Carpet 和 TIS 都不是本模组的前置，也不随本模组打包。
- 无参数 search 默认当前维度、当前位置 XYZ 各 ±16 格、全部历史；范围是立方体。
- 结果用自然语言显示，例如 `Steve 向容器存入 37 个 钻石`。玩家名为黄色，放置/存入为绿色，破坏/取出为红色，使用为青色；时间、坐标使用灰色。物品/方块名称使用客户端语言，已移除的对象安全显示原始 ID。改名和附魔等物品组件保留在详情中。
- 帮助页命令可点击，结果底部可点击上一页/下一页，以及「只看此处物品存取」。点击记录编号执行 `/al show <ID>` 查看完整详情；记录行悬停可查看完整时间、对象 ID、玩家 UUID、会话、因果链和物品组件。
- `/al sessions` 显示当前存档最近 20 次启动会话。每次启动都会创建新会话，不声称能自动判定哪次启动发生过回档。
- `page` 不带参数等于 next。只能跳到已加载页或紧接的下一页；最多缓存 1000 页。翻页固定第一次查询的最大事件编号，新事件不会把前页记录挤到后页。
- 检查指定位置和 UUID 查询使用内部索引；名字、范围和其他组合过滤使用有预算的顺序扫描。命中预算会明确提示，用 next 从断点继续，不能把不足一页误认为没有记录。

| 参数 | 含义 |
|---|---|
| `source:`，别名 `player:` | 玩家当时名字或完整 UUID；跨改名追查建议使用 UUID |
| `action:` | 上表中的一个动作标识；`container` 同时筛选存入和取出 |
| `object:` | 完整方块/物品/实体 ID；省略命名空间时补 `minecraft:` |
| `range:`，别名 `radius:` | 0–4096，或 `@global`；0 为精确位置 |
| `world:` | 维度标识，或 `@all`；不会跨到本数据库中的另一个存档身份 |
| `after:`，别名 `time:` | 最近多久，如 `1d2h30m` |
| `before:` | 早于多久以前，如 `1h`；与 after 组合筛选一段历史 |
| `session:` | 完整启动会话 UUID |
| `cause:` | 完整因果链 UUID |


## 配置

`config/anri-logger/config.json` 默认值如下；修改后重启服务端生效。

```json
{
  "permissionMode": "OP_ONLY",
  "enableLgAlias": false,
  "flushIntervalMillis": 1000,
  "queueCapacity": 32768,
  "pageSize": 10,
  "maxQueryScan": 100000,
  "queryCooldownMillis": 1000,
  "maxTrackedScheduledTicks": 100000
}
```

| 配置 | 说明与边界 |
|---|---|
| `permissionMode` | `OP_ONLY`：玩家必须在 OP 列表中（也包括权限等级为 0 的 OP；控制台可用）；`EVERYONE`：所有玩家可查询全部玩家的记录 |
| `enableLgAlias` | `true` 时启用与 `/al` 相同的 `/lg` 命令树；启动注册时发现冲突则保留已有命令并警告 |
| `flushIntervalMillis` | 50–60000 毫秒。默认约每秒提交并 fsync；累计 16384 条事件或估算未提交事件数据达到 16 MiB 时提前提交。查询不占事件批次额度 |
| `queueCapacity` | 512–1000000；有界待处理队列。队列满时反压，不静默丢弃事件 |
| `pageSize` | 1–50 条 |
| `maxQueryScan` | 100–1000000；每页最多扫描记录数；另有约 1.5 秒的处理预算 |
| `queryCooldownMillis` | 非负整数；限制同一来源的普通 search/page/show/status/sessions 查询间隔与并发。检查模式、坐标 inspect 和 container 查询不受此限制 |
| `maxTrackedScheduledTicks` | 100–1000000；每个调度器或维度的延迟因果追踪上限 |


## 单文件、小体积存储

使用内嵌 **H2 MVStore 2.3.232** 的直接映射 API，不启动 SQL/JDBC 引擎。schema 2 将事件按最多 2048 条、估算约 1 MiB 分块，使用 DEFLATE 高压缩；时间、tick、坐标和前驱编号采用差分与变长整数编码，规范 UUID 保存为 16 字节。完整物品组件仍保留，超大单条记录可独占一块。

字典局限于每个压缩块，不再把每个因果 UUID、独有物品详情永久复制到双向全局字典。位置索引使用世界/维度短编号和二进制坐标；位置/玩家索引只保留最新事件编号，块中保留前驱链接。尾块在定时提交时写入，未满块可在之后继续填充。所有映射统一提交到 `history.db`，事件和索引保持原子一致；读取使用有界的解压块缓存。

## 回档与备份

- 数据库在 config 下，独立于世界目录。仅还原世界文件时，审计历史保留。
- 编号来自数据库，**不使用游戏 tick、当前区块状态、运行时方块注册表数字 ID 作为唯一键**。回档后的相同坐标、相同游戏 tick 会追加新记录。
- 使用稳定的完整资源标识保存方块/物品，即使当前区块已不存在或相关模组移除，旧记录仍可按文本查询。
- 同一个规范化存档路径在数据库内映射到同一个存档身份。改名/移动存档目录会视作另一个存档；在原目录彻底换一个新地图会共享该路径历史，但可用 session 区分。迁移服务器应一并保留目录布局和数据库，不能承诺任意路径变更自动识别为同一世界。
- 回档前后的记录都是真实曾经发生过的历史；查询结果不表示当前世界一定仍处于该状态。
- **备份请先正常停服，再复制唯一的 `history.db`**。不要只拷贝正在写入的文件；本模组没有在线备份命令。若同时将 config 数据库也还原到旧版，自然无法保留数据库备份之后的记录。

## 因果追踪与兼容范围

同步更新使用严格的调用上下文，异常退出时也会还原上下文。调度 tick 绑定到实际的 ScheduledTick，活塞事件绑定到实际事件；TNT、落沙、投射物及移动活塞共享同一操作的剩余额度。NBT 只保存来源身份，加载时复用仍存活的共享额度，绝不从 NBT 重新发放 10 次额度。来源身份仍可保存，但**重启后、额度对象被回收或有界来源缓存淘汰后，已保存来源不再继续产生连带记录**，以免多个旧实体各自恢复一份过期额度。来源缓存最多记住 100000 个身份，不随历史无限增长。

不依靠“附近最近的玩家”推测归属。没有可证明的来源时不强行归因：模组安装前的行为、重启前遗留的连带更新、追踪容量超限、新模组绕过标准 Level/菜单入口的操作，可能没有玩家归因。此限制不会影响已有审计记录的查询。

自定义网络库存、虚拟菜单、跨线程修改世界的第三方模组需要专门适配。没有声称对任意模组行为做到全覆盖。

## 构建与验证

```powershell
.\gradlew.bat buildAndCollect
.\gradlew.bat runGameTest
```

Linux/macOS 首次构建先执行 `chmod +x gradlew`，再使用 `./gradlew`。构建锁定 Java 25、Gradle 9.7.1、Fabric Loom 1.17.20、Stonecutter 0.9.8。buildAndCollect 汇总产物在 `build/libs/`。测试模组放在独立的 gametest 源集，不会进入发行 JAR。

## Stonecutter 多版本开发

工程目录为 `anri-logger/`，使用 Kotlin DSL。当前唯一已适配并验证的版本为 **26.1.2**。

- `settings.gradle.kts`：声明 Minecraft 版本节点，`vcsVersion` 指定提交到版本库的源码版本。
- `stonecutter.gradle.kts`：Stonecutter 控制器，记录当前活动版本。
- `stonecutter.properties.toml`：统一管理模组 ID、名称、版本、Fabric Loader 以及各 Minecraft 版本的 Fabric API、Java 版本和兼容声明。
- `build.gradle.kts`：所有版本共享的构建逻辑。Minecraft、Fabric API、Java 与资源元数据从当前节点读取。
- `src/main`、`src/test`、`src/gametest`：共享源码；Stonecutter 已识别三个源码集。
- `versions/<版本>/build/`：每个版本独立的构建、测试报告及游戏测试世界。
- `build/libs/`：`buildAndCollect` 汇总带 Minecraft 版本后缀的 JAR，避免不同版本覆盖。

具体新增版本和源码条件分支流程见 [多版本开发指南](docs/MULTIVERSION.md)。

## 许可证

本项目代码采用 [MIT License](LICENSE)，许可证文本依据 [Open Source Initiative](https://opensource.org/license/mit)。第三方依赖与 Gradle Wrapper 保留各自的许可证，详见 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。
