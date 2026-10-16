# BiliCraft 导播双端共享协议

纯 Java 21 模块，无 Minecraft、Fabric、Paper 或 JSON 库依赖。通过游戏连接的
`bilicraftdirector:director` custom payload 传输 `Codec.encode` 产生的原始字节。

每包依次包含 4 字节 magic `0x42434452`（`BCDR`）、2 字节 wire version `6`、1 字节消息类型，
随后为对应消息的固定顺序字段，数值采用大端序。固定字段布局不存在重复命名字段；
任何额外字段作为尾随数据拒绝，Snapshot 同时禁止重复玩家 UUID 和重复场景 `(world, id)`。

`Hello.version` 和 `HelloReply.version` 是应用握手版本，不是包头的 wire version。
Codec 接受未知的握手版本，便于服务端返回明确的版本不匹配拒绝；未知 wire version
则无法安全解释字段，直接拒绝解码。v2 新增场景列表，v3 新增玩家计划时长与时长授权调整，
v4 新增客户端直连外链所需的地图源元数据，v5 新增独立的世界巡游政策，v6 新增服务端挂机点同步。双端必须一起升级；不接受 v1～v5 网络包头，不把缺失字段当成默认值。本机场景缓存可单独迁移 v4/v5 场景快照，世界规则缓存可迁移v5政策，不代表网络兼容。

消息编号：Hello=0、HelloReply=1、SnapshotRequest=2、Snapshot=3、ObserveRequest=4、
ObserveUpdate=5、Stop=6、Heartbeat=7、Error=8、DurationRequest=9、DurationReply=10、MapRequest=11、MapSource=12、WorldPolicy=13、IdlePointSync=14。
ObserveState 顺序为 NOTICE=0、FOLLOWING=1、ENDED=2。

HelloReply 的字段依次是 version（32 位整数）、allowed（布尔）、worldKey（最多 256 UTF-8
字节）、detail（最多 1024 UTF-8 字节）。worldKey 是服务端摄影机当前世界的实际 registry key，
例如 `minecraft:overworld`，客户端只接受与当前世界匹配的授权回复。
PlayerView.world 和 SceneView.world 同样传 registry key；不使用展示名称或存档目录名替代。

字符串是 2 字节无符号 UTF-8 字节数加完整字节序列，严格拒绝非法 UTF-8；玩家 name 最多
64 字节，world 最多 256 字节，detail/reason 最多 1024 字节。UUID 为两个 64 位值。
布尔仅接受字节 0、1。Snapshot 依次为：

1. 2 字节无符号玩家数量（最多 128），随后为原 PlayerView 列表：UUID、name、world、
   x/y/z（各 64 位浮点）、yaw/pitch（各 32 位浮点）、lastActivityEpochMillis（64 位整数）。
2. 2 字节无符号场景数量（最多 64），随后为 SceneView 列表：id、name、kind、world、
   x/y/z/radius（各 64 位浮点）。场景 id 最多 64 UTF-8 字节、name 最多 256 字节、world
   最多 256 字节。kind 为 1 字节枚举，VIEWPOINT=0（观景点）、AREA=1（巡游区域）。

场景按 `(world, id)` 联合唯一，两个字段分别按原字符串精确比较；同一世界内不能重复 id，
不同世界允许使用相同 id。客户端缓存可以保留多个世界的场景，切换或执行时需选择当前世界。
场景坐标和 radius 必须有限，
radius 范围为 6～48（含两端）；kind 必须存在并使用已知枚举。
所有包同时受 30000 字节限制，玩家和场景的数量上限不代表它们一定能同时装入一个包；
调用方需要在发送前选择符合整体包体限制的子集，不能截断已编码的包。

ObserveRequest 依次是 targetId（UUID）、requestedDurationSeconds（32 位整数）。
初始计划为 180～480 秒，0 表示服务端随机选取同一范围；Java 单参数构造只是源码便利，
v3 字节包仍必须包含这 4 字节，旧包不会自动补齐。

ObserveUpdate 依次是 state（1 字节）、是否存在 targetId（布尔）、可选 UUID、
deadlineEpochMillis（64 位整数）、durationSeconds（32 位整数）、reason。
仅 ENDED 可省略 targetId；NOTICE/FOLLOWING 的 durationSeconds 是服务端核准的总跟随秒数（180～480），
ENDED 的 durationSeconds 必须为 0。deadline 表示预告或跟随的预计结束时间，ENDED 可传 0。

DurationRequest 依次是 targetId（UUID）、requestedDurationSeconds（32 位整数，目标总跟随秒数）。
该字段在 wire 层保留完整有符号整数，让非法时长也交给专用业务回执拒绝，避免普通协议错误结束有效跟随。
服务端必须按真实发送账号查找其自有 session，确认目标匹配、180～480 秒范围，以及与当前总秒数之差恰为 ±10。
NOTICE 阶段只改计划跟随时长，不改 15 秒预告截止时间；FOLLOWING 截止时间始终等于最初开始时间加总时长，
不能把每次点击当成从当前时间再延长一段。

DurationReply 依次是 targetId（UUID，始终存在）、accepted（布尔）、state（1 字节）、
durationSeconds（32 位整数）、deadlineEpochMillis（64 位整数）、reason。
有效会话调整失败时返回 false 加当前实际 state/总秒数/截止时间，保留其合法观察，不发送普通 Error。
无自有 session 或目标不匹配时，返回 false、ENDED、0、0，不影响其他摄影机或当前另一目标。
合法缩短使新截止时间已过时，服务端正常结束观察，回执为 true、ENDED、获准总秒数、新的已过截止时间，
另发送正常 ObserveUpdate ENDED。除拒绝的缺失会话可使用 0 秒外，回执总秒数都须为 180～480；
false、ENDED 回执必须同时为 duration=0、deadline=0；其余回执不得为 0 秒。
客户端需要串行关联 targetId 与未完成的时长请求；停止或切世界后回执只负责排空，不能复活旧目标。

MapRequest 只有 requestId（64 位正整数），请求当前世界的外链映射，不触发服务端 HTTP 下载。
MapSource 依次是 requestId（原样回显的64位正整数）、worldKey（256 UTF-8字节）、configured（布尔）、
baseUrl（1024 UTF-8字节）、dynmapWorld（256 UTF-8字节）、排除区数量（2字节无符号整数，最多64）、
排除区列表、detail（512 UTF-8字节）。每个 RegionBounds 按 minX、minZ、maxX、maxZ 四个64位浮点数传输，
要求有限且 min 不大于 max。configured=false 时 URL、Dynmap世界和排除区都必须为空；超过64条排除规则时
拒绝提供映射，不能截断隐私规则。URL仅允许无账号密码、查询和片段的 HTTP(S) 部署地址。

worldKey 必须与摄影机当前世界一致，客户端同时匹配 requestId，拒绝超时、重试或跨世界的迟到回复。
服务端仍验证真实摄影机握手与数据权限，但地图映射不要求该世界加入巡游名单。地图元数据不代替玩家授权，也不更新 HelloReply 的业务状态。
客户端自行拼接 `tiles/_markers_/marker_<URL编码Dynmap世界>.json`，手动操作才直连外链；
地图文件、导入场景和玩家数据不会通过这两个消息在服务器中转。

WorldPolicy 依次是 worldKey（最多256 UTF-8字节）、worldId（UUID）、automaticPatrol（布尔）、
排除区数量（2字节无符号整数，最多64）及完整 RegionBounds 列表。worldKey 必须非空白且不含控制字符，
区域数值有限且 min 不大于 max。成功握手先发送当前世界政策再发送 HelloReply，心跳与名单变更也会同步政策；
不依赖配置地图外链。客户端仅接受当前世界的政策，按服务器与世界独立缓存，不保存玩家数据。
automaticPatrol=false 只暂停自动巡游及自动/AI补点，保留手动选点；所有入口继续检查排除区、
玩家隐私和本人 Minecraft 命令权限。规则失联保留已知缓存，首次插件规则同步期间暂停自动选点。

玩家位置与角度必须有限；解码拒绝截断、非法类型/枚举、过大集合、额外数据。

本模块只定义数据与编解码，不授权动作。服务端必须在处理消息时校验握手、账号权限、
配置与存储状态；玩家观察另校验观察者模式、排除区、玩家拒绝状态与冷却。巡游名单只限制自动行为，
名单外的手动玩家观察仍走完整服务端许可。客户端不能根据一个旧快照直接开始观察。
SceneView 是服务端提供的展示候选数据，ObserveRequest/ObserveUpdate 的 targetId 仍只表示
玩家 UUID；观景点与巡游区域不复用玩家观察许可，也不生成玩家预告。

IdlePointSync依次为worldKey（最多256 UTF-8字节、非空白）、是否有机位（布尔）；有机位时追加
x/y/z（各64位浮点）和yaw/pitch（各32位浮点）。坐标和方向须有限，x/z绝对值≤30000000，
pitch在−90～90，yaw归一化为[−180,180)。无机位表示清除客户端点位；客户端只接受当前世界。
服务端在成功握手、心跳、切世界和修改时发送，仅发给有数据权限且握手成功的导播；客户端不能用
此消息写入服务端。设置和清除通过真实玩家`/director idle`命令，独立校验`bilicraftdirector.idle.manage`。
服务器以世界UUID持久化，客户端仅内存持有；修改正在播出的机位会停止播出，旧队列姿态不回退为普通环绕。
