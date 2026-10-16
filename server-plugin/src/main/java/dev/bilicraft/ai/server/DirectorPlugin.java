package dev.bilicraft.ai.server;

import dev.bilicraft.ai.protocol.Codec;
import dev.bilicraft.ai.protocol.Message;
import dev.bilicraft.ai.protocol.Message.ObserveState;
import dev.bilicraft.ai.protocol.Protocol;
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
    private static final String DATA_PERMISSION = "bilicraftai.data.read";
    private static final String BLACKLIST_PERMISSION = "bilicraftai.blacklist.manage";
    private static final String WORLDS_PERMISSION = "bilicraftai.worlds.manage";
    private static final String MAP_PERMISSION = "bilicraftai.map.manage";
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
    private boolean storageUnavailable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            // Unlike reloadConfig(), explicit loading reports malformed YAML instead of using defaults.
            getConfig().load(getDataFolder().toPath().resolve("config.yml").toFile());
            worlds = new WorldCatalog(getDataFolder().toPath().resolve("worlds.yml"));
            if (!worlds.initialized()) {
                if (!(getConfig().get("world-name") instanceof String legacyName)) {
                    throw new IllegalArgumentException("首次导入的 world-name 必须是实际世界名称");
                }
                World legacyWorld = Bukkit.getWorld(legacyName);
                if (legacyWorld != null && legacyWorld.getName().equals(legacyName)) {
                    WorldCatalog.Profile profile = new WorldCatalog.Profile(legacyWorld.getUID(), legacyWorld.getName(),
                            SceneConfiguration.parseEntries(getConfig().get("scenes", List.of())),
                            SceneConfiguration.parseExclusions(getConfig().get("exclude-regions", List.of())));
                    buildWorldContext(legacyWorld, profile);
                    worlds.put(profile);
                } else {
                    worlds.save();
                    getLogger().warning("旧 world-name 对应世界未加载，已初始化空世界名单；请用 /aiview worlds add <实际世界名> 明确添加。未加载或猜测世界。");
                }
            }
            for (WorldCatalog.Profile profile : worlds.profiles()) {
                World loaded = Bukkit.getWorld(profile.id());
                if (loaded != null) {
                    WorldContext context = buildWorldContext(loaded, profile);
                    if (!profile.lastKnownName().equals(loaded.getName())) {
                        worlds.put(new WorldCatalog.Profile(profile.id(), loaded.getName(), profile.scenes(), profile.exclusions()));
                    }
                    worldContexts.put(profile.id(), context);
                }
            }
            privacy = new PrivacyStore(getDataFolder().toPath().resolve("privacy.properties"));
            mapSources = new MapSources(getDataFolder().toPath().resolve("map-sources.yml"));
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "BiliCraft 导播配置或隐私数据读取失败，停止服务，保留原文件供修复。", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        PluginCommand command = getCommand("aiview");
        if (command == null) {
            throw new IllegalStateException("plugin.yml 缺少 aiview 命令");
        }
        command.setExecutor(this::onViewCommand);
        command.setTabCompleter((sender, ignored, alias, args) -> {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("stop", "blacklist", "status"));
                if (sender.hasPermission(WORLDS_PERMISSION)) {
                    options.add("worlds");
                }
                if (sender.hasPermission(MAP_PERMISSION)) options.add("map");
                return options.stream()
                        .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            }
            if (args[0].equalsIgnoreCase("map") && sender.hasPermission(MAP_PERMISSION)) {
                if (args.length == 2) return List.of("set", "clear", "status").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
                if (args.length == 3) return worlds.profiles().stream().map(WorldCatalog.Profile::lastKnownName)
                        .filter(name -> name.startsWith(args[2])).sorted().toList();
            }
            if (args[0].equalsIgnoreCase("worlds") && sender.hasPermission(WORLDS_PERMISSION)) {
                if (args.length == 2) {
                    return List.of("list", "add", "remove").stream()
                            .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
                }
                if (args.length == 3) {
                    List<String> names = args[1].equalsIgnoreCase("add")
                            ? Bukkit.getWorlds().stream().filter(world -> worlds.get(world.getUID()) == null).map(World::getName).toList()
                            : args[1].equalsIgnoreCase("remove")
                            ? worlds.profiles().stream().map(WorldCatalog.Profile::lastKnownName).toList() : List.of();
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
        getLogger().info("BiliCraft 导播服务已启动，允许 " + worlds.profiles().size() + " 个世界，当前已加载 "
                + worldContexts.size() + " 个；本阶段不采集热力图、不提供远程传送。");
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
            } else {
                handshakes.remove(camera.getUniqueId());
                endCamera(camera.getUniqueId(), "协议或摄影机权限不满足");
            }
            send(camera, new Message.HelloReply(Protocol.VERSION, compatible && allowed, camera.getWorld().getKey().toString(),
                    !compatible ? "协议版本不兼容" : allowed ? "已授权" : cameraDeniedReason()));
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
            if (source == null || context.exclusions().size() > Protocol.MAX_MAP_EXCLUSIONS) {
                send(camera, new Message.MapSource(request.requestId(), worldKey, false, "", "", List.of(), source == null
                        ? "该世界尚未配置 Dynmap 外链映射，请联系管理员" : "排除区超过64个，拒绝下发不完整隐私规则"));
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
            send(camera, new Message.HelloReply(Protocol.VERSION, true, camera.getWorld().getKey().toString(), "连接正常"));
        } else if (message instanceof Message.SnapshotRequest) {
            long previous = lastSnapshot.getOrDefault(camera.getUniqueId(), 0L);
            if (now - previous < 1_000) {
                send(camera, new Message.Error("候选列表每秒最多请求一次"));
                return;
            }
            lastSnapshot.put(camera.getUniqueId(), now);
            List<Message.PlayerView> candidates = Bukkit.getOnlinePlayers().stream()
                    .filter(target -> targetEligible(camera, target)
                            && privacy.available(target.getUniqueId(), now)
                            && sessions.forTarget(target.getUniqueId()) == null
                            && now - activity.getOrDefault(target.getUniqueId(), 0L) < 10_000
                            && camera.getLocation().distanceSquared(target.getLocation()) <= 128 * 128)
                    .sorted(Comparator.comparingDouble(target -> camera.getLocation().distanceSquared(target.getLocation())))
                    .limit(100)
                    .map(target -> {
                        Location location = target.getLocation();
                        return new Message.PlayerView(target.getUniqueId(), target.getName(), target.getWorld().getKey().toString(),
                                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
                                activity.get(target.getUniqueId()));
                    }).toList();
            Location cameraLocation = camera.getLocation();
            WorldContext context = worldContexts.get(camera.getWorld().getUID());
            SceneConfiguration.Bounds worldBorder = currentWorldBorder(context.world());
            List<Message.SceneView> nearbyScenes = context.scenes().stream()
                    .filter(scene -> SceneConfiguration.fitsPatrolArea(scene, worldBorder, context.exclusions()))
                    .filter(scene -> cameraLocation.distanceSquared(new Location(context.world(), scene.x(), scene.y(), scene.z()))
                            <= (128 - scene.radius()) * (128 - scene.radius()))
                    .filter(scene -> sceneChunksLoaded(context.world(), scene))
                    .sorted(Comparator.comparingDouble(scene -> cameraLocation.distanceSquared(
                            new Location(context.world(), scene.x(), scene.y(), scene.z())))).toList();
            send(camera, new Message.Snapshot(candidates, nearbyScenes));
        } else if (message instanceof Message.ObserveRequest request) {
            requestObservation(camera, request.targetId(), request.requestedDurationSeconds(), now);
        } else {
            send(camera, new Message.Error("客户端不能发送此类消息"));
        }
    }

    private void requestObservation(Player camera, UUID targetId, int requestedSeconds, long now) {
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
                || camera.getLocation().distanceSquared(target.getLocation()) > 128 * 128) {
            send(camera, new Message.Error("目标当前不可观察，或不在附近加载范围内"));
            return;
        }
        long duration = (requestedSeconds == 0
                ? ThreadLocalRandom.current().nextLong(Protocol.MIN_FOLLOW_SECONDS, Protocol.MAX_FOLLOW_SECONDS + 1)
                : requestedSeconds) * 1_000;
        try {
            // Reserve the full session plus cooldown before notifying. A restart cannot erase it.
            privacy.setCooldown(targetId, now + ObservationSessions.NOTICE_MILLIS + duration + NORMAL_COOLDOWN_MILLIS);
        } catch (IOException exception) {
            failStorage("隐私状态", exception);
            send(camera, new Message.Error("隐私状态无法保存，观察已禁用"));
            return;
        }
        // Disk latency must not eat into the player's 15-second notice window.
        ObservationSessions.Session session = sessions.begin(camera.getUniqueId(), targetId, System.currentTimeMillis(), duration);
        Component reject = Component.text("[拒绝/停止]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/aiview reject " + session.token))
                .hoverEvent(HoverEvent.showText(Component.text("取消本次观察，24 小时内不再选中你")));
        Component blacklist = Component.text("[加入长期黑名单]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/aiview block " + session.token))
                .hoverEvent(HoverEvent.showText(Component.text("持续禁止跟随，直到你执行 /aiview blacklist off")));
        target.sendMessage(Component.text("[BiliCraft 导播] " + camera.getName()
                        + " 将在 15 秒后开始跟随展示，计划持续 " + duration / 1_000 + " 秒（3～8 分钟内可调整）。未拒绝将自动开始。\n", NamedTextColor.AQUA)
                .append(reject).append(Component.space()).append(blacklist)
                .append(Component.text("\n也可输入 /aiview stop 或 /tell " + camera.getName()
                        + " 停止跟随，开始后同样随时有效。", NamedTextColor.GRAY)));
        send(camera, new Message.ObserveUpdate(ObserveState.NOTICE, targetId, session.noticeDeadline,
                (int) (session.followDurationMillis / 1_000), "等待目标玩家 15 秒拒绝窗口"));
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
        long deadline = session.following ? session.followDeadline : session.noticeDeadline;
        boolean accepted = change.status() == ObservationSessions.ChangeStatus.APPLIED;
        ObserveState state = session.following ? ObserveState.FOLLOWING : ObserveState.NOTICE;
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
            } else if (!session.following && now >= session.noticeDeadline) {
                try {
                    // The actual follow may start after a delayed tick, so renew its restart reservation first.
                    privacy.setCooldown(session.target, Math.max(privacy.cooldownUntil(session.target),
                            now + session.followDurationMillis + NORMAL_COOLDOWN_MILLIS));
                } catch (IOException exception) {
                    failStorage("观察开始预留", exception);
                    return;
                }
                if (sessions.startFollowing(session.camera, now,
                        () -> invalidSessionReason(session, camera, target, System.currentTimeMillis()) == null)) {
                    send(camera, new Message.ObserveUpdate(ObserveState.FOLLOWING, session.target, session.followDeadline,
                            (int) (session.followDurationMillis / 1_000), "预告结束，允许跟随"));
                    target.sendMessage(Component.text("[BiliCraft 导播] " + camera.getName()
                            + " 已开始跟随。可随时点击先前的拒绝按钮，或使用 /aiview stop。", NamedTextColor.AQUA));
                }
            }
        }
    }

    private String invalidSessionReason(ObservationSessions.Session session, Player camera, Player target, long now) {
        if (camera == null || !cameraAllowed(camera) || !handshakes.contains(session.camera)) {
            return "摄影机离线或权限、模式、活动范围失效";
        }
        if (now - session.heartbeatAt >= ObservationSessions.HEARTBEAT_TIMEOUT_MILLIS) {
            return "客户端心跳超时";
        }
        if (target == null || !targetEligible(camera, target) || privacy.blacklisted(session.target)) {
            return "目标离线、隐藏、跨世界、进入排除区或禁止观察";
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
        return !storageUnavailable && camera.hasPermission(DATA_PERMISSION)
                && camera.getGameMode() == GameMode.SPECTATOR && insidePatrolArea(camera.getLocation());
    }

    private String cameraDeniedReason() {
        return "需要 bilicraftai.data.read 权限、观察者模式及已允许世界的有效区域；配置和隐私存储也必须可用";
    }

    private boolean targetEligible(Player camera, Player target) {
        return !camera.getUniqueId().equals(target.getUniqueId()) && target.isOnline()
                && camera.getWorld().equals(target.getWorld())
                && target.getGameMode() != GameMode.SPECTATOR && camera.canSee(target)
                && !target.isInvisible() && target.getMetadata("vanished").stream().noneMatch(value -> value.asBoolean())
                && insidePatrolArea(target.getLocation());
    }

    private boolean insidePatrolArea(Location location) {
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

    private boolean sceneChunksLoaded(World world, Message.SceneView scene) {
        int minChunkX = ((int) Math.floor(scene.x() - scene.radius())) >> 4;
        int maxChunkX = ((int) Math.floor(scene.x() + scene.radius())) >> 4;
        int minChunkZ = ((int) Math.floor(scene.z() - scene.radius())) >> 4;
        int maxChunkZ = ((int) Math.floor(scene.z() + scene.radius())) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
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
            send(camera, new Message.ObserveUpdate(ObserveState.ENDED, session.target, 0, 0, reason));
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
                send(camera, new Message.ObserveUpdate(ObserveState.ENDED, session.target, 0, 0, "配置或隐私存储故障，禁止观察"));
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
            if (message instanceof Message.Snapshot snapshot) {
                int overhead = Codec.encode(new Message.Snapshot(List.of(), List.of())).length;
                int usedBytes = overhead;
                List<Message.PlayerView> selectedPlayers = new ArrayList<>();
                List<Message.SceneView> selectedScenes = new ArrayList<>();
                // Interleave nearby players and scenes so neither category consumes the whole packet.
                for (int index = 0; index < Math.max(snapshot.players().size(), snapshot.scenes().size()); index++) {
                    if (index < snapshot.players().size()) {
                        Message.PlayerView player = snapshot.players().get(index);
                        int size = Codec.encode(new Message.Snapshot(List.of(player), List.of())).length - overhead;
                        if (usedBytes + size <= Protocol.MAX_PACKET_BYTES) {
                            selectedPlayers.add(player);
                            usedBytes += size;
                        }
                    }
                    if (index < snapshot.scenes().size()) {
                        Message.SceneView scene = snapshot.scenes().get(index);
                        int size = Codec.encode(new Message.Snapshot(List.of(), List.of(scene))).length - overhead;
                        if (usedBytes + size <= Protocol.MAX_PACKET_BYTES) {
                            selectedScenes.add(scene);
                            usedBytes += size;
                        }
                    }
                }
                message = new Message.Snapshot(selectedPlayers, selectedScenes);
            }
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
        if (action.equals("blacklist") && args.length >= 2
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            if (!sender.hasPermission(BLACKLIST_PERMISSION)) {
                sender.sendMessage(Component.text("需要 " + BLACKLIST_PERMISSION + " 权限才能管理其他玩家的观察黑名单。", NamedTextColor.RED));
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(Component.text("用法：/aiview blacklist add/remove <玩家名|UUID>", NamedTextColor.YELLOW));
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
                    ? "控制台可使用 /aiview blacklist add/remove <玩家名|UUID>；自助指令请在游戏内使用。"
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
                player.sendMessage(Component.text("这条观察链接已过期或不属于你。请使用 /aiview stop 或 /aiview blacklist on。", NamedTextColor.GRAY));
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
        String usage = "用法：/aiview stop | blacklist on/off | status";
        if (player.hasPermission(BLACKLIST_PERMISSION)) {
            usage += " | blacklist add/remove <玩家名|UUID>";
        }
        if (player.hasPermission(WORLDS_PERMISSION)) {
            usage += " | worlds list/add/remove <实际世界名>";
        }
        if (player.hasPermission(MAP_PERMISSION)) usage += " | map set/clear/status <实际世界名>";
        player.sendMessage(Component.text(usage, NamedTextColor.YELLOW));
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
            sender.sendMessage(Component.text("用法：/aiview map set <实际允许世界名> <Dynmap世界标识> <部署base URL> | map clear/status <世界名>", NamedTextColor.YELLOW));
            return true;
        }
        String worldName = String.join(" ", Arrays.copyOfRange(args, 2, setting ? args.length - 2 : args.length));
        World loaded = Bukkit.getWorld(worldName);
        WorldCatalog.Profile profile = loaded != null && loaded.getName().equals(worldName) ? worlds.get(loaded.getUID()) : null;
        if (profile == null && !setting) profile = worlds.profiles().stream()
                .filter(entry -> entry.lastKnownName().equals(worldName)).findFirst().orElse(null);
        if (profile == null) {
            sender.sendMessage(Component.text("请指定允许名单中的精确世界名；set 还要求该世界已加载。请用 /aiview worlds list 核对。", NamedTextColor.RED));
            return true;
        }
        if (action.equals("status")) {
            MapSources.Source source = mapSources.get(profile.id());
            sender.sendMessage(Component.text(source == null ? worldName + " 未配置外链地图映射。"
                    : worldName + " → Dynmap " + source.dynmapWorld() + " | " + source.baseUrl()
                    + "；由客户端手动直连下载，服务端不抓取地图。", NamedTextColor.AQUA));
            return true;
        }
        try {
            if (setting) mapSources.put(new MapSources.Source(profile.id(), loaded.getName(), args[args.length - 1], args[args.length - 2]));
            else mapSources.remove(profile.id());
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
            sender.sendMessage(Component.text("需要 " + WORLDS_PERMISSION + " 权限才能查看或管理世界允许名单。", NamedTextColor.RED));
            return true;
        }
        String action = args.length == 1 ? "list" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list") && args.length <= 2) {
            sender.sendMessage(Component.text("[BiliCraft 导播] 允许世界：" + worlds.profiles().size()
                    + (storageUnavailable ? "；当前配置或存储故障，服务已禁用" : ""), NamedTextColor.AQUA));
            for (WorldCatalog.Profile profile : worlds.profiles().stream()
                    .sorted(Comparator.comparing(WorldCatalog.Profile::lastKnownName)).toList()) {
                World loaded = Bukkit.getWorld(profile.id());
                sender.sendMessage(Component.text("- " + (loaded == null ? profile.lastKnownName() : loaded.getName())
                        + " | " + profile.id() + " | " + (loaded == null ? "允许，未加载"
                        : "允许，已加载，" + loaded.getKey()), NamedTextColor.GREEN));
            }
            for (World loaded : Bukkit.getWorlds()) {
                if (worlds.get(loaded.getUID()) == null) {
                    sender.sendMessage(Component.text("- " + loaded.getName() + " | " + loaded.getUID()
                            + " | 已加载，未允许", NamedTextColor.GRAY));
                }
            }
            return true;
        }
        String worldName = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        if (action.equals("add") && args.length >= 3) {
            World loaded = Bukkit.getWorld(worldName);
            if (loaded == null || !loaded.getName().equals(worldName)) {
                sender.sendMessage(Component.text("找不到精确名称匹配的已加载世界。请先执行 /aiview worlds list；导播不会加载或猜测世界。", NamedTextColor.RED));
                return true;
            }
            if (worlds.get(loaded.getUID()) != null) {
                sender.sendMessage(Component.text("该世界已经在允许名单中，现有场景和排除区保持不变。", NamedTextColor.YELLOW));
                return true;
            }
            try {
                WorldCatalog.Profile profile = new WorldCatalog.Profile(loaded.getUID(), loaded.getName(), List.of(), List.of());
                WorldContext context = buildWorldContext(loaded, profile);
                worlds.put(profile);
                worldContexts.put(profile.id(), context);
            } catch (IOException | IllegalArgumentException exception) {
                failStorage("世界允许名单", exception);
                sender.sendMessage(Component.text("世界设置保存失败，全部数据与观察已禁用，请检查日志。", NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("已允许世界 " + loaded.getName() + "（" + loaded.getUID()
                    + "）。场景和排除区为空，可在 worlds.yml 中独立配置；不会传送摄影机。", NamedTextColor.GREEN));
            return true;
        }
        if (action.equals("remove") && args.length >= 3) {
            World loaded = Bukkit.getWorld(worldName);
            WorldCatalog.Profile profile = loaded != null && loaded.getName().equals(worldName) ? worlds.get(loaded.getUID()) : null;
            if (profile == null) {
                profile = worlds.profiles().stream().filter(entry -> entry.lastKnownName().equals(worldName)).findFirst().orElse(null);
            }
            if (profile == null) {
                sender.sendMessage(Component.text("允许名单中没有精确名称匹配的世界，请用 /aiview worlds list 核对。", NamedTextColor.RED));
                return true;
            }
            try {
                worlds.remove(profile.id());
            } catch (IOException exception) {
                failStorage("世界允许名单", exception);
                sender.sendMessage(Component.text("世界设置保存失败，全部数据与观察已禁用，请检查日志。", NamedTextColor.RED));
                return true;
            }
            revokeWorld(profile.id(), "该世界已从导播允许名单移除");
            sender.sendMessage(Component.text("已移除世界 " + profile.lastKnownName()
                    + " 的允许记录及场景配置，重启不会自动重新添加；玩家隐私数据保留。", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("用法：/aiview worlds list | add <实际已加载世界名> | remove <世界名>", NamedTextColor.YELLOW));
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
            return;
        }
        try {
            WorldContext context = buildWorldContext(world, profile);
            if (!profile.lastKnownName().equals(world.getName())) {
                worlds.put(new WorldCatalog.Profile(profile.id(), world.getName(), profile.scenes(), profile.exclusions()));
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
        if (sessions.forTarget(event.getPlayer().getUniqueId()) != null && !insidePatrolArea(current)) {
            finish(sessions.forTarget(event.getPlayer().getUniqueId()), "目标进入排除区或离开允许世界", NORMAL_COOLDOWN_MILLIS);
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
