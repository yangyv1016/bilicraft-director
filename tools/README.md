# 本地网络集成验证

`integration-smoke.mjs` 通过真正的 Minecraft 1.21.11 TCP 连接验证 Paper 插件。
它不渲染游戏，不加载 Fabric Mod；可点击按钮通过读取服务端组件中的命令再发送该命令验证，
不代表已经完成真实客户端的鼠标点击、输入或运镜视觉验证。

## 准备

1. 使用 Node.js 22 或更高版本。
2. 安装测试依赖：

   ```powershell
   npm --prefix .tooling/test-client install minecraft-protocol@1.68.0 minecraft-data
   ```

3. 在专用测试服安装本工程服务端插件和官方 LuckPerms。测试服应使用 Paper 1.21.11，
   绑定 `127.0.0.1:25590`、`online-mode=false`、`enforce-secure-profile=false`，
   摄影机活动世界为默认 `world`。这些设置只用于本机离线测试。
4. 等待服务端输出 `Done`，再运行：

   ```powershell
   node tools/integration-smoke.mjs
   ```

脚本不启动、不停止服务端；只连接回环地址。`SMOKE_PORT` 可指定另一个测试端口。
依赖和测试报告放在忽略提交的 `.tooling/` 下。

## 执行

每次运行产生随机后缀的五个临时账号，避免之前的玩家冷却干扰本次测试。
脚本有三次 `CONSOLE_STEP`：初始化模式/位置、通过 LP 权限组授权、撤销组权限。
在测试服控制台执行该阶段打印的命令，然后在脚本终端输入 `continue`。

自动检查：

- 未授权握手/数据拒绝，以及 LP 组继承授权后的握手和结构化快照；
- 不兼容握手版本拒绝；
- 15 秒预告、拒绝/长期黑名单可点击命令、通知仅发送目标测试玩家；
- 他人 token 无效，之前目标的 token 不能取消另一个目标的新会话；
- 预告期拒绝取消、24 小时冷却，以及 15 秒后才进入 FOLLOWING；
- `/tell 摄影机账号 停止跟随`、长期黑名单和状态查询；
- 连续 10 秒无动作结束，普通结束冷却 30 分钟；
- 撤销继承权限后拒绝再次握手。

成功时输出 `SMOKE_SUCCESS`，并保存
`.tooling/paper-test/integration-smoke-result.json`，只记录测试摘要和临时账号，
不包含登录凭据。测试产生的 LP 组、用户偏好只存在于隔离测试服。

完成首次验证并重启隔离测试服后，可运行：

```powershell
node tools/integration-smoke.mjs --verify-persistence
```

该模式使用报告里相同的 TargetBot、BlockBot、IdleBot 身份重新连接，查询长期黑名单和
剩余冷却，结果保存为 `.tooling/paper-test/integration-persistence-result.json`。
应在首次验证后尽快执行，普通冷却到期后无法再用该断言核对。脚本不会代替操作员重启服务端。
单次 smoke 不把磁盘文件存在当作重启验证。通知定向性检查覆盖本脚本建立的其他连接。

## 协议依据

插件 payload 按 [共享协议文档](../protocol/README.md) 独立编码/解码。
当前 wire version 为5，包含 requestId 关联的地图源元数据与独立世界巡游政策；脚本编解码支持 type11/12/13，
已有 smoke 流程不会因此自动配置地图地址或下载外链。外链 HTTP、ETag、限额与失败缓存通过纯 Java 回环 HTTP 测试单独验证。
Minecraft 1.21.11 的 `custom_payload`、`system_chat` NBT 组件、`chat_command`、
`teleport_confirm` 和移动字段，以安装的 `minecraft-data` 包定义及
`minecraft-protocol/src/client` 源码为依据；升级版本前需重新核对。
