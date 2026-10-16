package dev.bilicraft.director.server;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Message.ObserveState;
import dev.bilicraft.director.protocol.Protocol;
import dev.bilicraft.director.protocol.SnapshotPages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** All Bukkit and observation state transitions run on the server thread. */
public final class DirectorPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private static final String DATA_PERMISSION = "bilicraftdirector.data.read";
    private static final String BLACKLIST_PERMISSION = "bilicraftdirector.blacklist.manage";
    private static final String WORLDS_PERMISSION = "bilicraftdirector.worlds.manage";
    private static final String IDLE_PERMISSION = "bilicraftdirector.idle.manage";
    private static final String MAP_PERMISSION = "bilicraftdirector.map.manage";
    private static final long NORMAL_COOLDOWN_MILLIS = 30 * 60_000L;
    private static final long REJECT_COOLDOWN_MILLIS = 24 * 60 * 60_000L;
    private final ObservationSessions sessions = new ObservationSessions();
    private final Set<UUID> handshakes = new HashSet<>();
    private final Map<UUID, RateWindow> rateLimits = new HashMap<>();
    private final Map<UUID, Long> lastSnapshot = new HashMap<>();
    private final Map<UUID, Long> activity = new HashMap<>();
    private final Map<UUID, Location> activityLocations = new HashMap<>();
    private final Map<UUID, WorldContext> worldContexts = new HashMap<>();
    private WorldCatalog worlds;
    private PrivacyStore privacy;
    private MapSources mapSources;
    private IdlePoints idlePoints;
    private boolean storageUnavailable;

    @Override
    public void onEnable() {
        try {
            if (Bukkit.getPluginManager().getPlugin("BiliCraftAI") != null) {
                throw new IllegalArgumentException("请先移除旧版 BiliCraftAI 插件 JAR，避免同时加载两个导播服务");
            }
            migrateDataDirectory(getDataFolder().toPath());
            saveDefaultConfig();
            // Unlike reloadConfig(), explicit loading reports malformed YAML instead of using defaults.
            getConfig().load(getDataFolder().toPath().resolve("config.yml").toFile());
            worlds = new WorldCatalog(getDataFolder().toPath().resolve("worlds.yml"));
            if (!worlds.initialized()) {
                if (!(getConfig().get("world-name") instanceof String legacyName)) {
                    throw new IllegalArgumentException("首次导入的 world-name 必须是实际世界名称");
                }
                World legacyWorld = Bukkit.getWorld(legacyName);
                if (legacyWorld != null && legacyWorld.getName().equals(legacyName)) {
                    WorldCatalog.Profile profile = new WorldCatalog.Profile(legacyWorld.getUID(), legacyWorld.getName(), true,
                            SceneConfiguration.parseEntries(getConfig().get("scenes", List.of())),
                            SceneConfiguration.parseExclusions(getConfig().get("exclude-regions", List.of())));
                    buildWorldContext(legacyWorld, profile);
                    worlds.put(profile);
                } else {
                    worlds.save();
                    getLogger().warning("旧 world-name 对应世界未加载，已初始化空世界名单；请用 /director worlds add <实际世界名> 明确添加。未加载或猜测世界。");
                }
            }
            for (WorldCatalog.Profile profile : worlds.profiles()) {
                World loaded = Bukkit.getWorld(profile.id());
                if (loaded != null) {
                    WorldContext context = buildWorldContext(loaded, profile);
                    if (!profile.lastKnownName().equals(loaded.getName())) {
                        worlds.put(new WorldCatalog.Profile(profile.id(), loaded.getName(), profile.automaticPatrol(), profile.scenes(), profile.exclusions()));
                    }
                    worldContexts.put(profile.id(), context);
                }
            }
            for (World world : Bukkit.getWorlds()) {
                worldContexts.putIfAbsent(world.getUID(), new WorldContext(world, List.of(), List.of()));
            }
            privacy = new PrivacyStore(getDataFolder().toPath().resolve("privacy.properties"));
            mapSources = new MapSources(getDataFolder().toPath().resolve("map-sources.yml"));
            idlePoints = new IdlePoints(getDataFolder().toPath().resolve("idle-points.yml"));
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "BiliCraft 导播配置或隐私数据读取失败，停止服务，保留原文件供修复。", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        PluginCommand command = getCommand("director");
        if (command == null) {
            throw new IllegalStateException("plugin.yml 缺少 director 命令");
        }
        command.setExecutor(this::onViewCommand);
        command.setTabCompleter((sender, ignored, alias, args) -> {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("stop", "blacklist", "status"));
                if (sender.hasPermission(WORLDS_PERMISSION)) {
                    options.add("worlds");
                }
                if (sender.hasPermission(MAP_PERMISSION)) options.add("map");
                if (sender.hasPermission(IDLE_PERMISSION)) options.add("idle");
                return options.stream()
                        .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            }
            if (args[0].equalsIgnoreCase("idle") && sender.hasPermission(IDLE_PERMISSION) && args.length == 2) {
                return List.of("set", "clear", "info").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            if (args[0].equalsIgnoreCase("map") && sender.hasPermission(MAP_PERMISSION)) {
                if (args.length == 2) return List.of("set", "clear", "status").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
                if (args.length == 3) {
                    Set<String> names = new HashSet<>();
                    Bukkit.getWorlds().forEach(world -> names.add(world.getName()));
                    worlds.profiles().forEach(profile -> names.add(profile.lastKnownName()));
                    mapSources.sources().forEach(source -> names.add(source.lastKnownName()));
                    return names.stream().filter(name -> name.startsWith(args[2])).sorted().toList();
                }
            }
            if (args[0].equalsIgnoreCase("worlds") && sender.hasPermission(WORLDS_PERMISSION)) {
                if (args.length == 2) {
                    return List.of("list", "add", "remove").stream()
                            .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
                }
                if (args.length == 3) {
                    List<String> names = args[1].equalsIgnoreCase("add")
                            ? Bukkit.getWorlds().stream().filter(world -> worlds.get(world.getUID()) == null
                                    || !worlds.get(world.getUID()).automaticPatrol()).map(World::getName).toList()
                            : args[1].equalsIgnoreCase("remove")
                            ? worlds.profiles().stream().filter(WorldCatalog.Profile::automaticPatrol)
                                    .map(WorldCatalog.Profile::lastKnownName).toList() : List.of();
                    return names.stream().filter(name -> name.startsWith(args[2])).sorted().toList();
                }
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("blacklist")) {
                List<String> options = sender.hasPermission(BLACKLIST_PERMISSION)
                        ? List.of("on", "off", "add", "remove") : List.of("on", "off");
                return options.stream().filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("blacklist")
                    && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))
                    && sender.hasPermission(BLACKLIST_PERMISSION)) {
                boolean adding = args[1].equalsIgnoreCase("add");
                return Bukkit.getOnlinePlayers().stream()
                        .filter(player -> privacy.blacklisted(player.getUniqueId()) != adding)
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
            return List.of();
        });
        Bukkit.getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            recordActivity(player);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("BiliCraft 导播服务已启动，自动巡游 " + worlds.profiles().stream().filter(WorldCatalog.Profile::automaticPatrol).count() + " 个世界，当前已加载 "
                + worldContexts.size() + " 个；本阶段不采集热力图、不提供远程传送。");
    }

    static void migrateDataDirectory(Path directory) throws IOException {
        Path legacyDirectory = directory.resolveSibling("BiliCraftAI");
        if (Files.notExists(legacyDirectory)) {
            return;
        }
        if (Files.exists(directory)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("新旧导播数据目录同时存在，停止服务以保护隐私数据；请停服核对并合并后移走旧目录");
                }
            }
            Files.delete(directory);
        }
        Files.move(legacyDirectory, directory);
    }

    @Override
    public void onDisable() {
        if (privacy != null) {
            for (ObservationSessions.Session session : sessions.all()) {
                finish(session, "服务端插件停止", NORMAL_COOLDOWN_MILLIS);
            }
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player camera, byte[] payload) {
        if (!Protocol.CHANNEL.equals(channel)) {
            return;
        }
        long now = System.currentTimeMillis();
        RateWindow rate = rateLimits.computeIfAbsent(camera.getUniqueId(), ignored -> new RateWindow());
        if (now - rate.startedAt >= 1_000) {
            rate.startedAt = now;
            rate.messages = 0;
        }
        if (++rate.messages > 12) {
            if (rate.messages == 13) {
                send(camera, new Message.Error("请求过于频繁，每秒最多 12 条"));
                endCamera(camera.getUniqueId(), "客户端请求限流");
            }
            return;
        }
        Message message;
        try {
            message = Codec.decode(payload);
        } catch (IOException exception) {
            getLogger().warning("拒绝 " + camera.getName() + " 的无效摄影机消息：" + exception.getMessage());
            send(camera, new Message.Error("无效协议消息"));
            endCamera(camera.getUniqueId(), "无效协议消息");
            return;
        }
        // Revocation/stop always acts only on the real sender's own camera session.
        if (message instanceof Message.Stop) {
            endCamera(camera.getUniqueId(), "摄影机主动停止");
            return;
        }
        boolean allowed = cameraAllowed(camera);
        if (message instanceof Message.Hello hello) {
            boolean compatible = hello.version() == Protocol.VERSION;
            if (compatible && allowed) {
                handshakes.add(camera.getUniqueId());
                sendWorldPolicy(camera);
            } else {
                handshakes.remove(camera.getUniqueId());
                endCamera(camera.getUniqueId(), "协议或摄影机权限不满足");
            }
            send(camera, new Message.HelloReply(Protocol.VERSION, compatible && allowed, camera.getWorld().getKey().toString(),
                    !compatible ? "协议版本不兼容" : allowed ? "数据访问正常" : cameraDeniedReason()));
            return;
        }
        if (message instanceof Message.DurationRequest request) {
            requestDuration(camera, request, now);
            return;
        }
        if (message instanceof Message.MapRequest request) {
            String worldKey = camera.getWorld().getKey().toString();
            if (!allowed || !handshakes.contains(camera.getUniqueId())) {
                send(camera, new Message.MapSource(request.requestId(), worldKey, false, "", "", List.of(),
                        allowed ? "请先完成握手" : cameraDeniedReason()));
                return;
            }
            MapSources.Source source = mapSources.get(camera.getWorld().getUID());
            WorldContext context = worldContexts.get(camera.getWorld().getUID());
            if (source == null) {
                send(camera, new Message.MapSource(request.requestId(), worldKey, false, "", "", List.of(),
                        "该世界尚未配置 Dynmap 外链映射，请联系管理员"));
            } else {
                List<Message.RegionBounds> exclusions = context.exclusions().stream().map(bounds ->
                        new Message.RegionBounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ())).toList();
                send(camera, new Message.MapSource(request.requestId(), worldKey, true, source.baseUrl(), source.dynmapWorld(), exclusions,
                        "地图由客户端直连外链下载，服务端仅提供映射与排除区"));
            }
            return;
        }
        if (!allowed || !handshakes.contains(camera.getUniqueId())) {
            endCamera(camera.getUniqueId(), "摄影机权限或连接失效");
            handshakes.remove(camera.getUniqueId());
            send(camera, new Message.HelloReply(Protocol.VERSION, false, camera.getWorld().getKey().toString(),
                    allowed ? "请先完成握手" : cameraDeniedReason()));
            return;
        }
        if (message instanceof Message.Heartbeat) {
            ObservationSessions.Session session = sessions.forCamera(camera.getUniqueId());
            if (session != null) {
                session.heartbeatAt = now;
            }
            sendWorldPolicy(camera);
            send(camera, new Message.HelloReply(Protocol.VERSION, true, camera.getWorld().getKey().toString(), "连接正常"));
        } else if (message instanceof Message.SnapshotRequest request) {
            long previous = lastSnapshot.getOrDefault(camera.getUniqueId(), 0L);
            if (now - previous < 1_000) {
                send(camera, new Message.Error("候选列表每秒最多请求一次"));
                return;
            }
            lastSnapshot.put(camera.getUniqueId(), now);
            List<Message.PlayerSummary> candidates = PlayerRoster.snapshot(camera, Bukkit.getOnlinePlayers(), privacy,
                    sessions, activity, this::insideViewingArea, now);
            Location cameraLocation = camera.getLocation();
            WorldContext context = worldContexts.get(camera.getWorld().getUID());
            SceneConfiguration.Bounds worldBorder = currentWorldBorder(context.world());
            List<Message.SceneView> nearbyScenes = context.scenes().stream()
                    .filter(scene -> SceneConfiguration.fitsPatrolArea(scene, worldBorder, context.exclusions()))
                    .sorted(Comparator.comparingDouble(scene -> cameraLocation.distanceSquared(
                            new Location(context.world(), scene.x(), scene.y(), scene.z())))).toList();
            List<Message.SnapshotPage> pages;
            try {
                pages = SnapshotPages.create(request.requestId(), candidates, nearbyScenes);
            } catch (IllegalArgumentException exception) {
                getLogger().warning("无法构建完整展示目标快照：" + exception.getMessage());
                send(camera, new Message.Error("完整目标快照超出容量或内容无效，未发送裁剪数据"));
                return;
            }
            for (Message.SnapshotPage page : pages) send(camera, page);
        } else if (message instanceof Message.ObserveRequest request) {
            requestObservation(camera, request.targetId(), request.requestedDurationSeconds(), now);
        } else if (message instanceof Message.ObserveReady request) {
            confirmObservationReady(camera, request, now);
        } else {
            send(camera, new Message.Error("客户端不能发送此类消息"));
        }
    }

    private void requestObservation(Player camera, UUID targetId, int requestedSeconds, long now) {
        if (!observationCameraEligible(camera)) {
            send(camera, new Message.Error("玩家观察需要观察者模式，且摄影机须在世界边界内、排除区外"));
            return;
        }
        if (requestedSeconds != 0 && (requestedSeconds < Protocol.MIN_FOLLOW_SECONDS || requestedSeconds > Protocol.MAX_FOLLOW_SECONDS)) {
            send(camera, new Message.Error("玩家跟随总时长必须为 180～480 秒"));
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (sessions.forCamera(camera.getUniqueId()) != null) {
            send(camera, new Message.Error("请先结束当前观察"));
            return;
        }
        if (target == null || !targetEligible(camera, target) || !privacy.available(targetId, now)
                || sessions.forTarget(targetId) != null
                || now - activity.getOrDefault(targetId, 0L) >= 10_000) {
            send(camera, new Message.Error("目标当前不可观察、跨世界或已无动作超过 10 秒"));
            return;
        }
        long duration = (requestedSeconds == 0
                ? ThreadLocalRandom.current().nextLong(Protocol.MIN_FOLLOW_SECONDS, Protocol.MAX_FOLLOW_SECONDS + 1)
                : requestedSeconds) * 1_000;
        try {
            // Reserve the full session plus cooldown before notifying. A restart cannot erase it.
            privacy.setCooldown(targetId, now + ObservationSessions.NOTICE_MILLIS + ObservationSessions.APPROACH_MILLIS
                    + duration + NORMAL_COOLDOWN_MILLIS);
        } catch (IOException exception) {
            failStorage("隐私状态", exception);
            send(camera, new Message.Error("隐私状态无法保存，观察已禁用"));
            return;
        }
        // Disk latency must not eat into the player's 15-second notice window.
        ObservationSessions.Session session = sessions.begin(camera.getUniqueId(), targetId, System.currentTimeMillis(), duration);
        Component reject = Component.text("[拒绝/停止]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/director reject " + session.token))
                .hoverEvent(HoverEvent.showText(Component.text("取消本次观察，24 小时内不再选中你")));
        Component blacklist = Component.text("[加入长期黑名单]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/director block " + session.token))
                .hoverEvent(HoverEvent.showText(Component.text("持续禁止跟随，直到你执行 /director blacklist off")));
        target.sendMessage(Component.text("[BiliCraft 导播] " + camera.getName()
                        + " 将在 15 秒预告结束后接近你，镜头就绪再开始跟随展示，计划持续 " + duration / 1_000
                        + " 秒（3～8 分钟内可调整）。未拒绝将自动开始。\n", NamedTextColor.AQUA)
                .append(reject).append(Component.space()).append(blacklist)
                .append(Component.text("\n也可输入 /director stop 或 /tell " + camera.getName()
                        + " 停止跟随，开始后同样随时有效。", NamedTextColor.GRAY)));
        send(camera, new Message.ObserveUpdate(ObserveState.NOTICE, targetId, session.noticeDeadline,
                (int) (session.followDurationMillis / 1_000), "等待目标玩家 15 秒拒绝窗口", session.token, null));
    }

    private void confirmObservationReady(Player camera, Message.ObserveReady request, long now) {
        ObservationSessions.Session session = sessions.forCamera(camera.getUniqueId());
        if (session == null || !session.token.equals(request.sessionId()) || !session.target.equals(request.targetId())) {
            return; // A delayed ready from an ended session cannot disturb its successor.
        }
        Player target = Bukkit.getPlayer(session.target);
        String invalidReason = invalidSessionReason(session, camera, target, now);
        if (invalidReason != null) {
            finish(session, invalidReason, NORMAL_COOLDOWN_MILLIS);
            return;
        }
        if (!session.approaching || session.following) return;
        if (camera.getLocation().distanceSquared(target.getLocation()) > 96 * 96) {
            finish(session, "摄影机尚未到达获准玩家附近", NORMAL_COOLDOWN_MILLIS);
            return;
        }
        if (sessions.startFollowing(camera.getUniqueId(), request.sessionId(), request.targetId(), now,
                () -> invalidSessionReason(session, camera, target, System.currentTimeMillis()) == null
                        && camera.getLocation().distanceSquared(target.getLocation()) <= 96 * 96)) {
            send(camera, new Message.ObserveUpdate(ObserveState.FOLLOWING, session.target, session.followDeadline,
                    (int) (session.followDurationMillis / 1_000), "已到达并获准跟随", session.token, null));
            target.sendMessage(Component.text("[BiliCraft 导播] " + camera.getName()
                    + " 已开始跟随。可随时点击先前的拒绝按钮，或使用 /director stop。", NamedTextColor.AQUA));
        }
    }

    private void requestDuration(Player camera, Message.DurationRequest request, long now) {
        if (!cameraAllowed(camera) || !handshakes.contains(camera.getUniqueId())) {
            endCamera(camera.getUniqueId(), "摄影机权限或连接失效");
            handshakes.remove(camera.getUniqueId());
            send(camera, new Message.DurationReply(request.targetId(), false, ObserveState.ENDED, 0, 0,
                    "摄影机权限或连接失效，未调整时长"));
            return;
        }
        ObservationSessions.Session session = sessions.forCamera(camera.getUniqueId());
        if (session == null) {
            send(camera, new Message.DurationReply(request.targetId(), false, ObserveState.ENDED, 0, 0,
                    "当前没有可调整的观察会话"));
            return;
        }
        Player target = Bukkit.getPlayer(session.target);
        String invalidReason = invalidSessionReason(session, camera, target, now);
        if (invalidReason != null) {
            finish(session, invalidReason, NORMAL_COOLDOWN_MILLIS);
            send(camera, new Message.DurationReply(request.targetId(), false, ObserveState.ENDED, 0, 0, invalidReason));
            return;
        }
        ObservationSessions.DurationChange change;
        try {
            change = sessions.changeDuration(camera.getUniqueId(), request.targetId(), request.requestedDurationSeconds(), now,
                    () -> invalidSessionReason(session, camera, target, System.currentTimeMillis()) == null,
                    followEnd -> privacy.setCooldown(session.target, Math.max(privacy.cooldownUntil(session.target),
                            followEnd + NORMAL_COOLDOWN_MILLIS)));
        } catch (IOException exception) {
            failStorage("玩家跟随时长预留", exception);
            send(camera, new Message.DurationReply(request.targetId(), false, ObserveState.ENDED, 0, 0,
                    "隐私预留无法保存，全部观察已停止"));
            return;
        }
        if (change.status() == ObservationSessions.ChangeStatus.NO_SESSION) {
            send(camera, new Message.DurationReply(request.targetId(), false, ObserveState.ENDED, 0, 0,
                    "请求目标不属于本摄影机当前观察会话"));
            return;
        }
        if (change.status() == ObservationSessions.ChangeStatus.NOT_AUTHORIZED) {
            String reason = invalidSessionReason(session, camera, target, System.currentTimeMillis());
            finish(session, reason == null ? "观察条件已失效" : reason, NORMAL_COOLDOWN_MILLIS);
            send(camera, new Message.DurationReply(request.targetId(), false, ObserveState.ENDED, 0, 0,
                    "观察条件已失效，未调整时长"));
            return;
        }
        int totalSeconds = (int) (session.followDurationMillis / 1_000);
        long deadline = session.following ? session.followDeadline
                : session.approaching ? session.approachDeadline : session.noticeDeadline;
        boolean accepted = change.status() == ObservationSessions.ChangeStatus.APPLIED;
        ObserveState state = session.following ? ObserveState.FOLLOWING
                : session.approaching ? ObserveState.APPROACHING : ObserveState.NOTICE;
        String reason = accepted ? "玩家跟随时长已由服务器核准" : "总时长必须在 180～480 秒内，且每次只能增加或减少 10 秒";
        if (accepted && session.following && deadline <= System.currentTimeMillis()) {
            finish(session, "缩短后本次观察时长结束", NORMAL_COOLDOWN_MILLIS);
            state = ObserveState.ENDED;
            reason = "已核准缩短，本次观察时长已结束";
        }
        send(camera, new Message.DurationReply(request.targetId(), accepted, state, totalSeconds, deadline, reason));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (ObservationSessions.Session session : sessions.all()) {
            Player camera = Bukkit.getPlayer(session.camera);
            Player target = Bukkit.getPlayer(session.target);
            String invalidReason = invalidSessionReason(session, camera, target, now);
            if (invalidReason != null) {
                finish(session, invalidReason, NORMAL_COOLDOWN_MILLIS);
            } else if (!session.approaching && !session.following && now >= session.noticeDeadline) {
                try {
                    // A delayed tick must still reserve the whole approach and follow before granting entry.
                    privacy.setCooldown(session.target, Math.max(privacy.cooldownUntil(session.target),
                            now + ObservationSessions.APPROACH_MILLIS + session.followDurationMillis + NORMAL_COOLDOWN_MILLIS));
                } catch (IOException exception) {
                    failStorage("观察开始预留", exception);
                    return;
                }
                if (sessions.beginApproach(session.camera, now,
                        () -> invalidSessionReason(session, camera, target, System.currentTimeMillis()) == null)) {
                    Location location = target.getLocation();
                    Message.PlayerView pose = new Message.PlayerView(target.getUniqueId(), target.getName(),
                            target.getWorld().getKey().toString(), location.getX(), location.getY(), location.getZ(),
                            location.getYaw(), location.getPitch(), activity.getOrDefault(session.target, 0L));
                    send(camera, new Message.ObserveUpdate(ObserveState.APPROACHING, session.target, session.approachDeadline,
                            (int) (session.followDurationMillis / 1_000), "预告结束，允许本人在当前世界接近目标",
                            session.token, pose));
                }
            }
        }
    }

    private String invalidSessionReason(ObservationSessions.Session session, Player camera, Player target, long now) {
        if (camera == null || !cameraAllowed(camera) || !observationCameraEligible(camera) || !handshakes.contains(session.camera)) {
            return "摄影机离线或权限、模式、活动范围失效";
        }
        if (now - session.heartbeatAt >= ObservationSessions.HEARTBEAT_TIMEOUT_MILLIS) {
            return "客户端心跳超时";
        }
        if (target == null || !targetEligible(camera, target) || privacy.blacklisted(session.target)) {
            return "目标离线、隐藏、跨世界、进入排除区或禁止观察";
        }
        if (session.approaching && !session.following && now >= session.approachDeadline) {
            return "获准接近玩家超时";
        }
        if (!session.following && now - activity.getOrDefault(session.target, 0L) >= 10_000) {
            return "目标连续 10 秒无动作";
        }
        if (session.following) {
            long lastActivity = Math.max(session.followingStartedAt, activity.getOrDefault(session.target, 0L));
            if (now - lastActivity >= 10_000) {
                return "目标连续 10 秒无动作";
            }
            if (now >= session.followDeadline) {
                return "本次观察时长结束";
            }
        }
        return null;
    }

    private boolean cameraAllowed(Player camera) {
        return !storageUnavailable && camera.hasPermission(DATA_PERMISSION);
    }

    private boolean observationCameraEligible(Player camera) {
        return camera.getGameMode() == GameMode.SPECTATOR && insideViewingArea(camera.getLocation());
    }

    private String cameraDeniedReason() {
        return "需要 bilicraftdirector.data.read 数据访问权限，配置和隐私存储必须可用";
    }

    private boolean targetEligible(Player camera, Player target) {
        return PlayerRoster.eligible(camera, target, this::insideViewingArea);
    }

    private boolean insideViewingArea(Location location) {
        WorldContext context = worldContexts.get(location.getWorld().getUID());
        if (context == null || !context.world().getWorldBorder().isInside(location)) {
            return false;
        }
        for (SceneConfiguration.Bounds region : context.exclusions()) {
            if (location.getX() >= region.minX() && location.getX() <= region.maxX()
                    && location.getZ() >= region.minZ() && location.getZ() <= region.maxZ()) {
                return false;
            }
        }
        return true;
    }

    private WorldContext buildWorldContext(World world, WorldCatalog.Profile profile) {
        List<Message.SceneView> scenes = SceneConfiguration.activate(profile.scenes(), world.getKey().toString(),
                currentWorldBorder(world), profile.exclusions(), world.getMinHeight(), world.getMaxHeight());
        return new WorldContext(world, scenes, profile.exclusions());
    }

    private SceneConfiguration.Bounds currentWorldBorder(World world) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double halfSize = border.getSize() / 2;
        return new SceneConfiguration.Bounds(center.getX() - halfSize, center.getX() + halfSize,
                center.getZ() - halfSize, center.getZ() + halfSize);
    }

    private void sendWorldPolicy(Player camera) {
        World world = camera.getWorld();
        WorldCatalog.Profile profile = worlds.get(world.getUID());
        WorldContext context = worldContexts.get(world.getUID());
        List<Message.RegionBounds> exclusions = context.exclusions().stream().map(bounds ->
                new Message.RegionBounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ())).toList();
        send(camera, new Message.WorldPolicy(world.getKey().toString(), world.getUID(),
                profile != null && profile.automaticPatrol(), exclusions));
        Message.IdlePose point = idlePoints.get(world.getUID());
        send(camera, new Message.IdlePointSync(world.getKey().toString(), point != null && idlePointAllowed(world, point) ? point : null));
    }

    private void pushWorldPolicy(UUID worldId) {
        for (UUID cameraId : handshakes) {
            Player camera = Bukkit.getPlayer(cameraId);
            if (camera != null && camera.getWorld().getUID().equals(worldId) && cameraAllowed(camera)) {
                sendWorldPolicy(camera);
            }
        }
    }

    private void endCamera(UUID camera, String reason) {
        ObservationSessions.Session session = sessions.forCamera(camera);
        if (session != null) {
            finish(session, reason, NORMAL_COOLDOWN_MILLIS);
        }
    }

    private void finish(ObservationSessions.Session session, String reason, long cooldownMillis) {
        if (sessions.forCamera(session.camera) != session) {
            return;
        }
        sessions.end(session);
        if (!storageUnavailable) {
            try {
                privacy.setCooldown(session.target, System.currentTimeMillis() + cooldownMillis);
            } catch (IOException exception) {
                failStorage("隐私状态", exception);
            }
        }
        Player camera = Bukkit.getPlayer(session.camera);
        if (camera != null) {
            send(camera, new Message.ObserveUpdate(ObserveState.ENDED, session.target, 0, 0, reason, session.token, null));
        }
        Player target = Bukkit.getPlayer(session.target);
        if (target != null) {
            target.sendMessage(Component.text("[BiliCraft 导播] 观察已结束：" + reason, NamedTextColor.GRAY));
        }
    }

    private void failStorage(String section, Exception exception) {
        storageUnavailable = true;
        getLogger().log(Level.SEVERE, "BiliCraft 导播" + section + "处理失败，已禁用全部数据与观察。修复配置或存储后重启；本次未保存的设置需重新执行。", exception);
        for (ObservationSessions.Session session : sessions.all()) {
            sessions.end(session);
            Player camera = Bukkit.getPlayer(session.camera);
            if (camera != null) {
                send(camera, new Message.ObserveUpdate(ObserveState.ENDED, session.target, 0, 0,
                        "配置或隐私存储故障，禁止观察", session.token, null));
            }
            Player target = Bukkit.getPlayer(session.target);
            if (target != null) {
                target.sendMessage(Component.text("[BiliCraft 导播] 存储故障，全部观察已停止。", NamedTextColor.RED));
            }
        }
    }

    private void send(Player camera, Message message) {
        // Bukkit rejects plugin messages after JavaPlugin#setEnabled(false), before onDisable runs.
        if (!isEnabled()) {
            return;
        }
        try {
            camera.sendPluginMessage(this, Protocol.CHANNEL, Codec.encode(message));
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "无法编码发送给 " + camera.getName() + " 的摄影机消息", exception);
            handshakes.remove(camera.getUniqueId());
            endCamera(camera.getUniqueId(), "协议编码错误");
        }
    }

    private boolean onViewCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("worlds")) {
            return onWorldCommand(sender, args);
        }
        if (action.equals("map")) return onMapCommand(sender, args);
        if (action.equals("idle")) return onIdleCommand(sender, args);
        if (action.equals("blacklist") && args.length >= 2
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            if (!sender.hasPermission(BLACKLIST_PERMISSION)) {
                sender.sendMessage(Component.text("需要 " + BLACKLIST_PERMISSION + " 权限才能管理其他玩家的观察黑名单。", NamedTextColor.RED));
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(Component.text("用法：/director blacklist add/remove <玩家名|UUID>", NamedTextColor.YELLOW));
                return true;
            }
            String targetName = args[2];
            UUID targetId;
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null) {
                targetId = online.getUniqueId();
                targetName = online.getName();
            } else {
                try {
                    targetId = UUID.fromString(targetName);
                    if (!targetId.toString().equalsIgnoreCase(targetName)) {
                        sender.sendMessage(Component.text("请提供完整 UUID，格式为 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx。", NamedTextColor.RED));
                        return true;
                    }
                } catch (IllegalArgumentException exception) {
                    OfflinePlayer cached = getServer().getOfflinePlayerIfCached(targetName);
                    if (cached == null) {
                        sender.sendMessage(Component.text("找不到该在线或已缓存玩家；请使用完整 UUID，或等待玩家上线后重试。", NamedTextColor.RED));
                        return true;
                    }
                    targetId = cached.getUniqueId();
                }
            }
            boolean blocked = args[1].equalsIgnoreCase("add");
            try {
                privacy.setBlacklist(targetId, blocked);
            } catch (IOException exception) {
                failStorage("隐私状态", exception);
                sender.sendMessage(Component.text("黑名单写入失败，全部观察已禁用，请检查服务端日志。", NamedTextColor.RED));
                return true;
            }
            ObservationSessions.Session session = sessions.forTarget(targetId);
            if (blocked && session != null) {
                finish(session, "管理员将目标加入长期黑名单", NORMAL_COOLDOWN_MILLIS);
            }
            sender.sendMessage(Component.text(blocked
                    ? "已将 " + targetName + "（" + targetId + "）加入长期观察黑名单，重启后保留。"
                    : "已将 " + targetName + "（" + targetId + "）移出长期观察黑名单，已有冷却继续生效。", NamedTextColor.GREEN));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(sender.hasPermission(BLACKLIST_PERMISSION)
                    ? "控制台可使用 /director blacklist add/remove <玩家名|UUID>；自助指令请在游戏内使用。"
                    : "自助观察指令请在游戏内使用；管理其他玩家需要 " + BLACKLIST_PERMISSION + " 权限。");
            return true;
        }
        if (action.equals("status") && args.length <= 1) {
            long remaining = Math.max(0, privacy.cooldownUntil(player.getUniqueId()) - System.currentTimeMillis());
            player.sendMessage(Component.text("[BiliCraft 导播] 长期黑名单：" + (privacy.blacklisted(player.getUniqueId()) ? "已开启" : "未开启")
                    + "；冷却剩余约 " + (remaining + 59_999) / 60_000 + " 分钟。"
                    + (storageUnavailable ? " 配置或隐私存储故障，全部观察已禁用。" : ""), NamedTextColor.AQUA));
            return true;
        }
        if (action.equals("stop") && args.length == 1) {
            reject(player, sessions.forTarget(player.getUniqueId()), false);
            return true;
        }
        if ((action.equals("reject") || action.equals("block")) && args.length == 2) {
            UUID token;
            try {
                token = UUID.fromString(args[1]);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text("观察操作链接无效。", NamedTextColor.RED));
                return true;
            }
            ObservationSessions.Session session = sessions.forAction(player.getUniqueId(), token, System.currentTimeMillis());
            if (session == null) {
                player.sendMessage(Component.text("这条观察链接已过期或不属于你。请使用 /director stop 或 /director blacklist on。", NamedTextColor.GRAY));
                return true;
            }
            reject(player, session, action.equals("block"));
            return true;
        }
        if (action.equals("blacklist") && args.length == 2
                && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            boolean blocked = args[1].equalsIgnoreCase("on");
            try {
                privacy.setBlacklist(player.getUniqueId(), blocked);
            } catch (IOException exception) {
                failStorage("隐私状态", exception);
                player.sendMessage(Component.text("偏好写入失败，全部观察已禁用，请通知管理员。", NamedTextColor.RED));
                return true;
            }
            if (blocked) {
                ObservationSessions.Session session = sessions.forTarget(player.getUniqueId());
                if (session != null) {
                    finish(session, "目标加入长期黑名单", NORMAL_COOLDOWN_MILLIS);
                }
            }
            player.sendMessage(Component.text(blocked ? "已加入长期观察黑名单，重启后保留。" : "已解除长期观察黑名单，已有冷却继续生效。", NamedTextColor.GREEN));
            return true;
        }
        String usage = "用法：/director stop | blacklist on/off | status";
        if (player.hasPermission(BLACKLIST_PERMISSION)) {
            usage += " | blacklist add/remove <玩家名|UUID>";
        }
        if (player.hasPermission(WORLDS_PERMISSION)) {
            usage += " | worlds list/add/remove <实际世界名>";
        }
        if (player.hasPermission(IDLE_PERMISSION)) usage += " | idle set/clear/info";
        if (player.hasPermission(MAP_PERMISSION)) usage += " | map set/clear/status <实际世界名>";
        player.sendMessage(Component.text(usage, NamedTextColor.YELLOW));
        return true;
    }

    private boolean onIdleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(IDLE_PERMISSION)) {
            sender.sendMessage(Component.text("需要 " + IDLE_PERMISSION + " 权限才能管理服务器挂机点。", NamedTextColor.RED));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("请在游戏内使用 /director idle，按所在世界保存挂机点。");
            return true;
        }
        if (storageUnavailable) {
            sender.sendMessage(Component.text("服务端存储故障，请修复后重启；挂机点未更改。", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        World world = player.getWorld();
        if (action.equals("info") && args.length == 2) {
            Message.IdlePose point = idlePoints.get(world.getUID());
            sender.sendMessage(Component.text(point == null ? "当前世界尚未设置服务器挂机点。"
                    : "服务器挂机点：" + point.x() + " " + point.y() + " " + point.z()
                    + "，yaw=" + point.yaw() + "，pitch=" + point.pitch(), NamedTextColor.AQUA));
            return true;
        }
        boolean setting = action.equals("set");
        if (!(setting && (args.length == 2 || args.length == 7)) && !(action.equals("clear") && args.length == 2)) {
            sender.sendMessage(Component.text("用法：/director idle set [x y z yaw pitch] | clear | info（当前世界）", NamedTextColor.YELLOW));
            return true;
        }
        Message.IdlePose point = null;
        try {
            if (setting) {
                Location location = player.getLocation();
                point = args.length == 2 ? new Message.IdlePose(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch())
                        : new Message.IdlePose(Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                                Float.parseFloat(args[5]), Float.parseFloat(args[6]));
                if (!idlePointAllowed(world, point)) throw new IllegalArgumentException("机位超出世界高度、边界或位于排除区");
            }
            idlePoints.set(world.getUID(), point);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text("挂机点无效：" + exception.getMessage(), NamedTextColor.RED));
            return true;
        } catch (IOException exception) {
            failStorage("挂机点", exception);
            sender.sendMessage(Component.text("挂机点保存失败，原设置保留，请检查服务端日志。", NamedTextColor.RED));
            return true;
        }
        pushWorldPolicy(world.getUID());
        sender.sendMessage(Component.text(setting ? "已保存服务器挂机点，并同步当前世界的授权导播。" : "已清除服务器挂机点，并同步授权导播。", NamedTextColor.GREEN));
        return true;
    }

    private boolean idlePointAllowed(World world, Message.IdlePose point) {
        if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight()
                || !world.getWorldBorder().isInside(new Location(world, point.x(), point.y(), point.z()))) return false;
        for (SceneConfiguration.Bounds bounds : worldContexts.get(world.getUID()).exclusions()) {
            if (point.x() >= bounds.minX() && point.x() <= bounds.maxX()
                    && point.z() >= bounds.minZ() && point.z() <= bounds.maxZ()) return false;
        }
        return true;
    }

    private boolean onMapCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(MAP_PERMISSION)) {
            sender.sendMessage(Component.text("需要 " + MAP_PERMISSION + " 权限才能管理外链地图映射。", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean setting = action.equals("set");
        if ((!setting && !(action.equals("clear") || action.equals("status"))) || args.length < (setting ? 5 : 3)) {
            sender.sendMessage(Component.text("用法：/director map set <实际已加载世界名> <Dynmap世界标识> <部署base URL> | map clear/status <世界名>", NamedTextColor.YELLOW));
            return true;
        }
        String worldName = String.join(" ", Arrays.copyOfRange(args, 2, setting ? args.length - 2 : args.length));
        World loaded = Bukkit.getWorld(worldName);
        UUID worldId = loaded != null && loaded.getName().equals(worldName) ? loaded.getUID() : null;
        if (worldId == null && !setting) {
            worldId = worlds.profiles().stream().filter(entry -> entry.lastKnownName().equals(worldName))
                    .map(WorldCatalog.Profile::id).findFirst().orElse(null);
            if (worldId == null) worldId = mapSources.sources().stream().filter(entry -> entry.lastKnownName().equals(worldName))
                    .map(MapSources.Source::worldId).findFirst().orElse(null);
        }
        if (worldId == null) {
            sender.sendMessage(Component.text("请指定精确世界名；set 要求该世界已加载，clear/status 支持已保存映射。无需加入巡游名单。", NamedTextColor.RED));
            return true;
        }
        if (action.equals("status")) {
            MapSources.Source source = mapSources.get(worldId);
            sender.sendMessage(Component.text(source == null ? worldName + " 未配置外链地图映射。"
                    : worldName + " → Dynmap " + source.dynmapWorld() + " | " + source.baseUrl()
                    + "；由客户端手动直连下载，服务端不抓取地图。", NamedTextColor.AQUA));
            return true;
        }
        try {
            if (setting) mapSources.put(new MapSources.Source(worldId, loaded.getName(), args[args.length - 1], args[args.length - 2]));
            else mapSources.remove(worldId);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text("地图源无效：" + exception.getMessage(), NamedTextColor.RED));
            return true;
        } catch (IOException exception) {
            failStorage("外链地图映射", exception);
            sender.sendMessage(Component.text("地图映射保存失败，数据与观察已禁用，请检查日志。", NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text(setting ? "已保存外链映射；客户端手动拉取时直连该地址，服务端不下载地图。"
                : "已移除该世界的外链地图映射。", NamedTextColor.GREEN));
        return true;
    }

    private boolean onWorldCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(WORLDS_PERMISSION)) {
            sender.sendMessage(Component.text("需要 " + WORLDS_PERMISSION + " 权限才能查看或管理世界巡游名单。", NamedTextColor.RED));
            return true;
        }
        String action = args.length == 1 ? "list" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list") && args.length <= 2) {
            sender.sendMessage(Component.text("[BiliCraft 导播] 自动巡游世界：" + worlds.profiles().stream().filter(WorldCatalog.Profile::automaticPatrol).count()
                    + (storageUnavailable ? "；当前配置或存储故障，服务已禁用" : ""), NamedTextColor.AQUA));
            for (WorldCatalog.Profile profile : worlds.profiles().stream()
                    .sorted(Comparator.comparing(WorldCatalog.Profile::lastKnownName)).toList()) {
                World loaded = Bukkit.getWorld(profile.id());
                sender.sendMessage(Component.text("- " + (loaded == null ? profile.lastKnownName() : loaded.getName())
                        + " | " + profile.id() + " | " + (profile.automaticPatrol() ? "巡游开启" : "巡游关闭，仅保留资料/手动展示")
                        + (loaded == null ? "，未加载" : "，已加载，" + loaded.getKey()),
                        profile.automaticPatrol() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            }
            for (World loaded : Bukkit.getWorlds()) {
                if (worlds.get(loaded.getUID()) == null) {
                    sender.sendMessage(Component.text("- " + loaded.getName() + " | " + loaded.getUID()
                            + " | 已加载，未加入巡游名单，可手动展示", NamedTextColor.GRAY));
                }
            }
            return true;
        }
        String worldName = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        if (action.equals("add") && args.length >= 3) {
            World loaded = Bukkit.getWorld(worldName);
            if (loaded == null || !loaded.getName().equals(worldName)) {
                sender.sendMessage(Component.text("找不到精确名称匹配的已加载世界。请先执行 /director worlds list；导播不会加载或猜测世界。", NamedTextColor.RED));
                return true;
            }
            WorldCatalog.Profile previous = worlds.get(loaded.getUID());
            if (previous != null && previous.automaticPatrol()) {
                sender.sendMessage(Component.text("该世界已经在巡游名单中，现有场景和排除区保持不变。", NamedTextColor.YELLOW));
                return true;
            }
            try {
                WorldCatalog.Profile profile = new WorldCatalog.Profile(loaded.getUID(), loaded.getName(), true,
                        previous == null ? List.of() : previous.scenes(), previous == null ? List.of() : previous.exclusions());
                WorldContext context = buildWorldContext(loaded, profile);
                worlds.put(profile);
                worldContexts.put(profile.id(), context);
            } catch (IOException | IllegalArgumentException exception) {
                failStorage("世界巡游名单", exception);
                sender.sendMessage(Component.text("世界设置保存失败，全部数据与观察已禁用，请检查日志。", NamedTextColor.RED));
                return true;
            }
            pushWorldPolicy(loaded.getUID());
            sender.sendMessage(Component.text("已将世界 " + loaded.getName() + "（" + loaded.getUID()
                    + "）加入自动巡游名单；现有场景、排除区和手动能力保留，不会传送摄影机。", NamedTextColor.GREEN));
            return true;
        }
        if (action.equals("remove") && args.length >= 3) {
            World loaded = Bukkit.getWorld(worldName);
            WorldCatalog.Profile profile = loaded != null && loaded.getName().equals(worldName) ? worlds.get(loaded.getUID()) : null;
            if (profile == null) {
                profile = worlds.profiles().stream().filter(entry -> entry.lastKnownName().equals(worldName)).findFirst().orElse(null);
            }
            if (profile == null || !profile.automaticPatrol()) {
                sender.sendMessage(Component.text("巡游名单中没有精确名称匹配的世界，请用 /director worlds list 核对。", NamedTextColor.RED));
                return true;
            }
            try {
                worlds.setAutomaticPatrol(profile.id(), false);
            } catch (IOException exception) {
                failStorage("世界巡游名单", exception);
                sender.sendMessage(Component.text("世界设置保存失败，全部数据与观察已禁用，请检查日志。", NamedTextColor.RED));
                return true;
            }
            pushWorldPolicy(profile.id());
            sender.sendMessage(Component.text("已移除世界 " + profile.lastKnownName()
                    + " 的自动巡游资格；保留场景、排除区、地图映射及手动展示，玩家隐私规则继续有效。", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("用法：/director worlds list | add <实际已加载世界名> | remove <世界名>", NamedTextColor.YELLOW));
        return true;
    }

    private void revokeWorld(UUID worldId, String reason) {
        worldContexts.remove(worldId);
        for (ObservationSessions.Session session : sessions.all()) {
            Player camera = Bukkit.getPlayer(session.camera);
            Player target = Bukkit.getPlayer(session.target);
            if (camera != null && camera.getWorld().getUID().equals(worldId)
                    || target != null && target.getWorld().getUID().equals(worldId)) {
                finish(session, reason, NORMAL_COOLDOWN_MILLIS);
            }
        }
        for (UUID cameraId : List.copyOf(handshakes)) {
            Player camera = Bukkit.getPlayer(cameraId);
            if (camera != null && camera.getWorld().getUID().equals(worldId)) {
                handshakes.remove(cameraId);
                send(camera, new Message.HelloReply(Protocol.VERSION, false, camera.getWorld().getKey().toString(), reason));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        WorldCatalog.Profile profile = worlds.get(world.getUID());
        if (profile == null) {
            worldContexts.put(world.getUID(), new WorldContext(world, List.of(), List.of()));
            return;
        }
        try {
            WorldContext context = buildWorldContext(world, profile);
            if (!profile.lastKnownName().equals(world.getName())) {
                worlds.put(new WorldCatalog.Profile(profile.id(), world.getName(), profile.automaticPatrol(), profile.scenes(), profile.exclusions()));
            }
            worldContexts.put(profile.id(), context);
        } catch (IOException | IllegalArgumentException exception) {
            failStorage("世界 " + world.getName() + " 的配置", exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        revokeWorld(event.getWorld().getUID(), "该世界正在卸载");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player camera = event.getPlayer();
        if (handshakes.contains(camera.getUniqueId()) && cameraAllowed(camera)) sendWorldPolicy(camera);
    }

    private void reject(Player target, ObservationSessions.Session session, boolean blacklist) {
        try {
            if (blacklist) {
                privacy.setBlacklist(target.getUniqueId(), true);
            }
            privacy.setCooldown(target.getUniqueId(), System.currentTimeMillis() + REJECT_COOLDOWN_MILLIS);
        } catch (IOException exception) {
            failStorage("隐私状态", exception);
            target.sendMessage(Component.text("偏好写入失败，全部观察已禁用，请通知管理员。", NamedTextColor.RED));
            return;
        }
        if (session != null) {
            finish(session, blacklist ? "目标加入长期黑名单" : "目标拒绝观察", REJECT_COOLDOWN_MILLIS);
        }
        target.sendMessage(Component.text(blacklist ? "已加入长期观察黑名单，重启后保留。" : "已拒绝观察，24 小时内不再选中你。", NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrivateStop(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+", 3);
        if (parts.length != 3 || !parts[2].equals("停止跟随")) {
            return;
        }
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!Set.of("/tell", "/msg", "/w", "/minecraft:tell", "/minecraft:msg", "/minecraft:w").contains(command)) {
            return;
        }
        ObservationSessions.Session session = sessions.forTarget(event.getPlayer().getUniqueId());
        Player camera = Bukkit.getPlayerExact(parts[1]);
        if (session != null && camera != null && session.camera.equals(camera.getUniqueId())) {
            event.setCancelled(true);
            reject(event.getPlayer(), session, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location current = event.getTo();
        Location previous = activityLocations.get(event.getPlayer().getUniqueId());
        if (previous == null || !previous.getWorld().equals(current.getWorld())
                || previous.distanceSquared(current) >= 0.0025
                || Math.abs(Math.IEEEremainder(current.getYaw() - previous.getYaw(), 360)) >= 3
                || Math.abs(current.getPitch() - previous.getPitch()) >= 3) {
            recordActivity(event.getPlayer());
        }
        if (sessions.forTarget(event.getPlayer().getUniqueId()) != null && !insideViewingArea(current)) {
            finish(sessions.forTarget(event.getPlayer().getUniqueId()), "目标进入排除区或离开世界边界", NORMAL_COOLDOWN_MILLIS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        ObservationSessions.Session targetSession = sessions.forTarget(event.getPlayer().getUniqueId());
        if (targetSession != null && (!event.getFrom().getWorld().equals(event.getTo().getWorld())
                || event.getFrom().distanceSquared(event.getTo()) >= 32 * 32)) {
            finish(targetSession, "目标传送到远处", NORMAL_COOLDOWN_MILLIS);
        }
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        endCamera(id, "摄影机断线");
        ObservationSessions.Session targetSession = sessions.forTarget(id);
        if (targetSession != null) {
            finish(targetSession, "目标离线", NORMAL_COOLDOWN_MILLIS);
        }
        handshakes.remove(id);
        rateLimits.remove(id);
        lastSnapshot.remove(id);
        activity.remove(id);
        activityLocations.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        // Air interaction may be pre-cancelled by vanilla; its visible animation still counts.
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        recordActivity(event.getPlayer());
    }

    private void recordActivity(Player player) {
        activity.put(player.getUniqueId(), System.currentTimeMillis());
        activityLocations.put(player.getUniqueId(), player.getLocation());
    }

    private static final class RateWindow {
        long startedAt;
        int messages;
    }

    private record WorldContext(World world, List<Message.SceneView> scenes, List<SceneConfiguration.Bounds> exclusions) {
    }

}
