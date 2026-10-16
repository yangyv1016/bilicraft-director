# 内部命名统一验证

2026-10-16：Java 包改为 `dev.bilicraft.director`，客户端入口为 `BiliCraftDirectorClient`，Mod ID/资源命名空间为 `bilicraftdirector`，插件名为 `BiliCraftDirector`，命令为 `/director`，权限前缀为 `bilicraftdirector`。游戏通道改为 `bilicraftdirector:director`，二进制签名为 `BCDR`，wire version 仍为 4；双端须同步升级。

## 构建与自动检查

- `clean build release` 成功；补充旧场景缓存兼容后再次 `build release` 成功。最终 201 项 JUnit 通过：client-mod 115、protocol 36、server-plugin 50。
- 新增测试覆盖插件数据目录迁移、目录冲突拒绝启动、损坏隐私数据继续拒绝加载、客户端旧镜头/AI 配置迁移及不覆盖新配置、旧场景缓存读取与拒绝玩家数据。配置测试使用自造密钥。
- 核对双端安装 JAR 的入口类、协议类、Mod ID、Mixin、WebUI 与中英文本地化资源；中英翻译引用完整。旧包与旧资源命名空间未残留在安装 JAR 中。
- `node --check tools/integration-smoke.mjs`、Git 差异检查通过。按 yangyv coding check 复核命名引用、初始化与迁移边界；保留必要的文件错误处理和隐私冲突保护，未整理无关业务逻辑。

## 真实 Paper 服务端

本机回环隔离服 Paper 1.21.11-132 / LuckPerms 5.5.87 加载 `BiliCraftDirector`。旧 `BiliCraftAI` 目录的 `config.yml`、`worlds.yml`、`map-sources.yml`、`privacy.properties` 自动迁移，首次启动后与迁移前 SHA-256 全部一致。

真实 Minecraft TCP 连接集成测试 runId `6161`，23 项通过：新权限节点的拒绝/LP 组继承/撤销、新通道握手与快照、15 秒私密预告、具体请求按钮、他人和旧 token 拒绝、私聊停止、黑名单、空闲终止及冷却。随后实际停止并重启 Paper，原有三个测试身份的黑名单与冷却重新查询通过。详细报告保存在忽略目录 `.tooling/paper-test/`，运行日志为 `.test/internal-rename-paper.log`。

这是真实服务端和协议连接验证，不加载 Fabric 图形客户端。本轮未复测真实 Fabric 界面点击、输入、运镜观感或真实 AI 提供方调用；旧版相关记录不代表本轮更名后的实测。
