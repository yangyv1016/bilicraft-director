# BiliCraft 导播双端共享协议

纯 Java 21 模块，无 Minecraft、Fabric、Paper 或 JSON 库依赖。通过游戏连接的
`bilicraftdirector:director` custom payload 传输 `Codec.encode` 产生的原始字节。

每包依次包含 4 字节 magic `0x42434452`（`BCDR`）、2 字节 wire version `7`、1 字节消息类型，
随后为对应消息的固定顺序字段，数值采用大端序。固定字段布局不存在重复命名字段；
任何额外字段作为尾随数据拒绝，Snapshot 同时禁止重复玩家 UUID 和重复场景 `(world, id)`。

`Hello.version` 和 `HelloReply.version` 是应用握手版本，不是包头的 wire version。
Codec 接受未知的握手版本，便于服务端返回明确的版本不匹配拒绝；未知 wire version
则无法安全解释字段，直接拒绝解码。v2 新增场景列表，v3 新增玩家计划时长与时长授权调整，
v4新增地图源元数据，v5新增世界巡游政策，v6新增挂机点，v7新增完整玩家分页、授权接近会话及到位确认。客户端0.1.7/服务端0.1.3/协议模块0.1.3须一起升级，不接受v1～v6网络包头，不把缺失字段当默认值。本机仅迁移v4/v5/v6无玩家场景快照及v5/v6世界政策，不代表网络兼容。

消息编号：Hello=0、HelloReply=1、SnapshotRequest=2、Snapshot=3、ObserveRequest=4、
ObserveUpdate=5、Stop=6、Heartbeat=7、Error=8、DurationRequest=9、DurationReply=10、MapRequest=11、MapSource=12、WorldPolicy=13、IdlePointSync=14、SnapshotPage=15、ObserveReady=16。
ObserveState顺序为NOTICE=0、APPROACHING=1、FOLLOWING=2、ENDED=3。

HelloReply 的字段依次是 version（32 位整数）、allowed（布尔）、worldKey（最多 256 UTF-8
字节）、detail（最多 1024 UTF-8 字节）。worldKey 是服务端摄影机当前世界的实际 registry key，
例如 `minecraft:overworld`，客户端只接受与当前世界匹配的授权回复。
PlayerView.world 和 SceneView.world 同样传 registry key；不使用展示名称或存档目录名替代。

字符串是 2 字节无符号 UTF-8 字节数加完整字节序列，严格拒绝非法 UTF-8；玩家 name 最多
64 字节，world 最多 256 字节，detail/reason 最多 1024 字节。UUID 为两个 64 位值。
布尔仅接受字节0、1。SnapshotRequest（tag2）含requestId（64位正整数），服务端原样回显到全部SnapshotPage；无ID的旧请求不兼容。

Snapshot（tag3）保留原PlayerView布局，仅供本机场景磁盘缓存使用，不再作为服务器在线玩家响应。其布局依次为：

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
旧Snapshot仅用于有界磁盘缓存；在线响应使用下述SnapshotPages完整分页，不得裁剪全服名单或截断编码包。

SnapshotPage（tag15）依次为：requestId（64位正整数）、pageIndex（2字节无符号，从0开始）、pageCount（2字节无符号，1～256）、玩家数量（2字节，0～32）及PlayerSummary列表、场景数量（2字节，0～32）及SceneView列表。pageIndex必须小于pageCount；单页禁止重复玩家UUID或同世界场景ID，接收方还须验证跨页唯一与所有页的count一致，全部页收齐后再应用。

PlayerSummary依次为id（UUID）、name（最多64 UTF-8字节）、world（最多256字节）、availability（1字节）。name/world须非空白且无控制字符，不带任何坐标、朝向或活动时间；PlayerAvailability顺序为AVAILABLE=0、OTHER_WORLD=1、IDLE=2、SPECTATOR=3、UNAVAILABLE=4。

SnapshotPages.create把玩家和场景分成各自最多32项的页，空响应也发1空页；最多4096玩家、8192场景且总页数≤256，任一总量超限均显式报错，不返回截断子集。场景32项在全部字符串取字节上限时为19,703字节，低于30,000字节限制。服务端配置仍每世界最多64个手工场景，此值与网络分页/本机Dynmap导入上限不同。名单来源和授权职责见[全服玩家说明](../docs/full-player-roster.md)。

ObserveRequest 依次是 targetId（UUID）、requestedDurationSeconds（32 位整数）。
初始计划为 180～480 秒，0 表示服务端随机选取同一范围；Java 单参数构造只是源码便利，
v3 字节包仍必须包含这 4 字节，旧包不会自动补齐。

ObserveUpdate（tag5）依次为state（1字节）、是否存在targetId（布尔）及可选UUID、deadlineEpochMillis（64位整数）、durationSeconds（32位整数）、reason、是否存在sessionId（布尔）及可选UUID、是否存在targetPose（布尔）及可选完整PlayerView。

NOTICE、APPROACHING、FOLLOWING必须有targetId/sessionId、正数deadline和180～480秒总计划；ENDED允许省略target/session，但duration/deadline必须均为0。只有APPROACHING必须携带targetPose，pose.id须匹配targetId；NOTICE/FOLLOWING/ENDED禁止pose。旧5参Java构造只适用于ENDED，不能省略活跃会话身份。targetPose必须位置/朝向有限、XYZ绝对值≤30000000、pitch在−90～90、活动时间非负，名称/世界非空白且无控制字符。

NOTICE的deadline是15秒拒绝窗口截止，APPROACHING的deadline是最多15秒接近截止，FOLLOWING才是正式观察截止；前两段不消耗跟随计划。APPROACHING姿态仅在预告结束且服务端复查后发给该摄影机，不在全服名单分发。ObserveReady（tag16）依次为sessionId、targetId两个UUID；服务端按真实发送摄影机查找匹配会话，检查目标实际同世界、距离≤96格以及权限/隐私条件，才转换FOLLOWING。旧会话Ready忽略，不能影响下一请求。客户端不得把单独的姿态、发出TP或Ready当作正式许可。

DurationRequest 依次是 targetId（UUID）、requestedDurationSeconds（32 位整数，目标总跟随秒数）。
该字段在 wire 层保留完整有符号整数，让非法时长也交给专用业务回执拒绝，避免普通协议错误结束有效跟随。
服务端必须按真实发送账号查找其自有 session，确认目标匹配、180～480 秒范围，以及与当前总秒数之差恰为 ±10。
NOTICE/APPROACHING阶段只改计划跟随时长，不改各自15秒截止时间；FOLLOWING 截止时间始终等于最初开始时间加总时长，
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
客户端自行拼接`tiles/_markers_/marker_<URL编码Dynmap世界>.json`；服务端映射和自定义来源手动拉取，内置默认地图在Mod初始化时异步预取；
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
