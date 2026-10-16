# Dynmap 只读数据核验记录

核验日期：2026-10-16。以下是本轮通过 `Invoke-WebRequest` 对用户提供站点进行实际读取后的结果。
本文件记录数据来源与设计依据，不表示 Mod 或导播服务已经接入这些 HTTP 数据。

## 已确认的入口

用户地图入口是 [BiliCraft Hyperion 地图](https://map.bilicraft.com/s2/hyperion/)。
实际读取 [standalone/config.js](https://map.bilicraft.com/s2/hyperion/standalone/config.js) 后，确认该部署使用
[standalone/dynmap_config.json](https://map.bilicraft.com/s2/hyperion/standalone/dynmap_config.json)
作为配置来源，标注数据位于 `tiles/_markers_/marker_{world}.json`。

`dynmap_config.json` 中列出的世界为 `Paralon`、`artist`、`artvoid`、`HyperionVoid`；世界名应保留实际大小写。
这些是地图部署使用的世界标识；转换为 `SceneView.world` 时，仍需由服务端已加载世界确认对应的游戏 registry key，不能直接按名称猜测。
对 [up/configuration](https://map.bilicraft.com/s2/hyperion/up/configuration) 的实际请求返回 **404**。
后续接入应以该站点已验证的加载链路为依据，不直接假定标准 Dynmap API 路径可用。

## 已读取的标注数据

| 数据源 | 响应正文大小 | 点标注 | 区域标注 |
| --- | ---: | ---: | ---: |
| [Paralon](https://map.bilicraft.com/s2/hyperion/tiles/_markers_/marker_Paralon.json) | 604,572 字节 | 564 | 360 |
| [HyperionVoid](https://map.bilicraft.com/s2/hyperion/tiles/_markers_/marker_HyperionVoid.json) | 30,319 字节 | 68 | 17 |

Paralon 的统计来自：

- `stargate`：122 个点标注。
- `markers`：442 个点标注，另有 1 个区域标注。
- 公会：195 个区域标注。
- `residence`：164 个区域标注。

这组统计合计为 **564 个点、360 个区域**。本轮没有将 Paralon 或 HyperionVoid 的统计推算为其他世界的数量。
响应存在 `ETag`，后续集中缓存可以保存该值，通过 `If-None-Match` 发起条件请求；这些查询结果不代表已实现定时抓取器。

## 数据能说明什么

以上 JSON 提供地图标注、传送点和领地区域，可作为观景点或巡游区域候选。
它们不是建筑实体识别结果，也不能仅凭一个领地边界就断言其中建筑密集、外观适合展示或摄影机路线可通行。
实际候选仍需要按世界区分，并结合排除区、管理员筛选及客户端已加载区块约束使用。

## 当前取向与接入状态

用户最新要求避免游戏服务器地图流量：导播服务只下发外链与世界映射，Mod 手动直连该外链，原始 JSON、瓦片及全量候选不经过游戏连接。客户端 5 分钟缓存、ETag 与同源在途任务复用，不持续轮询。

**最新版客户端已实现 HTTP 标记导入、候选转换与本机缓存；服务端不下载或转发 Dynmap JSON，热力图仍未实现。**
已使用正式 `DynmapMapClient` 从上述 Paralon 外链直接导入 564 个点、360 个区域，共 924 个候选，并验证第二次读取复用同一份内存缓存；未通过 Minecraft 服务器读取数据。这是纯 Java 导入链路实测，不代表已在真实 Fabric 画面中逐个展示。
