package dev.bilicraft.director.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Message.ObserveState;
import dev.bilicraft.director.protocol.Message.PlayerView;
import dev.bilicraft.director.protocol.Message.SceneView;
import dev.bilicraft.director.protocol.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringHelper;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class BiliCraftDirectorClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("bilicraft-director");
    private static final Comparator<ShowcaseTarget> TARGET_ORDER = Comparator
            .comparing(ShowcaseTarget::name, String.CASE_INSENSITIVE_ORDER).thenComparing(ShowcaseTarget::id);
    private static BiliCraftDirectorClient instance;
    final CameraSettings settings = new CameraSettings();
    private boolean cameraSettingsSaved = true;
    private final CameraMotion cameraMotion = new CameraMotion();
    final AiSettings aiSettings = new AiSettings(FabricLoader.getInstance().getConfigDir()
            .resolve("bilicraft-director-ai.local.properties"));
    private final AiHttpClient aiHttpClient = new AiHttpClient();
    private final AiStrategySession aiSession = new AiStrategySession();
    private long aiGeneration;
    private long aiObservedSettingsRevision = -1;
    private long aiTestEpoch;
    private boolean aiTestPending;
    private List<String> aiModels = List.of();
    private String aiTestStatus = "尚未测试；模型列表不代表Chat能力已验证";
    private final LocalSceneCache sceneCache = new LocalSceneCache(FabricLoader.getInstance().getConfigDir()
            .resolve("bilicraft-director-scenes"));
    private final LocalWorldPolicyCache worldPolicyCache = new LocalWorldPolicyCache(FabricLoader.getInstance().getConfigDir()
            .resolve("bilicraft-director-world-policies"));
    private Message.WorldPolicy worldPolicy;
    private boolean worldPolicyCacheFailed;
    private boolean worldPolicyReceived;
    private boolean worldPolicyPending;
    private long worldPolicyDeadline;
    private LocalControlServer localControlServer;
    private final AccountAvatar accountAvatar = new AccountAvatar();
    private final LocalChatHistory localChat = new LocalChatHistory();
    private final DynmapMapClient mapClient = new DynmapMapClient();
    private final LocalMapSettings localMapSettings = new LocalMapSettings(FabricLoader.getInstance().getConfigDir()
            .resolve("bilicraft-director-maps"));
    private LocalMapSettings.Profile localMapProfile = new LocalMapSettings.Profile("", "");
    private Message.MapSource mapSource;
    private Message.MapSource serverMapSource;
    private List<SceneView> serverScenes = List.of();
    private List<SceneView> importedScenes = List.of();
    private List<DynmapRegion> mapRegions = List.of();
    private boolean mapBootstrapPending;
    private boolean mapLoaded;
    private boolean mapMetadataPending;
    private long mapRequestSequence;
    private long mapMetadataRequestId;
    private boolean mapRefreshRequested;
    private boolean mapLoading;
    private boolean mapExternalPending;
    private long mapRequestDeadline;
    private long mapGeneration;
    private long mapFetchedAt;
    private int mapSceneCount;
    private String mapDetail = "可配置本机地图外链，无需导播服务";
    private final ShowcaseQueue showcaseQueue = new ShowcaseQueue();
    private IdlePoint idlePoint;
    private final QueueSettings queueSettings = new QueueSettings(FabricLoader.getInstance().getConfigDir()
            .resolve("bilicraft-director-queue.properties"));
    private long lastQueueRefreshAttempt;
    private boolean running;
    private boolean allowed;
    private boolean resumeAfterReconnect;
    private ServerInfo lastServer;
    private long nextReconnect;
    private long lastMessage;
    private long lastHeartbeat;
    private String currentWorldKey;
    private UUID requestedTarget;
    private UUID observeRequestTarget;
    private UUID durationRequestTarget;
    private String durationRequestEntryId;
    private ObserveState observeState = ObserveState.ENDED;
    private long observeDeadline;
    private List<ShowcaseTarget> targets = List.of();
    private List<SceneView> cachedScenes = List.of();
    private boolean sceneCacheFailed;
    private boolean sceneCacheLimited;
    private ShowcaseTarget lastSelectedTarget;
    private SceneView activeScene;
    private SceneArrival sceneArrival;
    private boolean snapshotPending;
    private String snapshotWorldKey;
    private long businessRequestDeadline;
    private boolean businessRequestCancelled;
    private int targetSwitchDirection;
    private long lastSnapshotResponse;
    private String status = "本地导播待启动";
    private Vec3d orbitCenter;
    private Vec3d retreatDirection = Vec3d.ZERO;
    private long retreatUntil;
    private final ShotTransition shotTransition = new ShotTransition();
    private Vec3d previousTargetPosition;
    private long targetMovingUntil;
    private KeyBinding toggleKey;
    private KeyBinding menuKey;
    private KeyBinding previousTargetKey;
    private KeyBinding nextTargetKey;

    public static BiliCraftDirectorClient getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("directoridle")
                .then(literal("set").executes(context -> {
                    ClientPlayerEntity player = context.getSource().getPlayer();
                    return changeIdlePoint(context.getSource(), new IdlePoint(context.getSource().getWorld().getRegistryKey().getValue().toString(), player.getX(), player.getY(),
                            player.getZ(), player.getYaw(), player.getPitch()));
                }).then(argument("x", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                        .then(argument("y", DoubleArgumentType.doubleArg())
                                .then(argument("z", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                                        .then(argument("yaw", FloatArgumentType.floatArg())
                                                .then(argument("pitch", FloatArgumentType.floatArg(-90, 90)).executes(context ->
                                                        changeIdlePoint(context.getSource(), new IdlePoint(context.getSource().getWorld().getRegistryKey().getValue().toString(),
                                                                DoubleArgumentType.getDouble(context, "x"), DoubleArgumentType.getDouble(context, "y"),
                                                                DoubleArgumentType.getDouble(context, "z"), FloatArgumentType.getFloat(context, "yaw"),
                                                                FloatArgumentType.getFloat(context, "pitch"))))))))))
                .then(literal("clear").executes(context -> changeIdlePoint(context.getSource(), null)))
                .then(literal("info").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.getNetworkHandler().sendChatCommand("director idle info");
                    return 1;
                }))));
        LocalMapSettings.Profile defaultMap = LocalMapSettings.BILICRAFT_DEFAULT;
        mapClient.fetch(defaultMap.baseUrl(), defaultMap.dynmapWorld(), "minecraft:overworld")
                .whenComplete((result, error) -> {
                    if (error != null) LOGGER.warn("Default map prefetch failed; connection bootstrap or manual refresh can retry");
                });
        settings.load(LOGGER);
        aiSettings.load();
        try {
            queueSettings.load();
        } catch (IOException | IllegalArgumentException exception) {
            queueSettings.loadFailed = true;
            LOGGER.warn("Cannot read queue configuration", exception);
        }
        PayloadTypeRegistry.playS2C().register(DirectorPayload.ID, DirectorPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DirectorPayload.ID, DirectorPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(DirectorPayload.ID, (payload, context) -> {
            if (context.player().networkHandler != context.client().getNetworkHandler()) {
                return;
            }
            try {
                Message message = Codec.decode(payload.bytes());
                receive(message);
            } catch (IOException exception) {
                LOGGER.warn("Invalid server director message", exception);
                revokeServerAccess("服务端消息无效");
            }
        });
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("bilicraftdirector", "director"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilicraftdirector.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, category));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilicraftdirector.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        previousTargetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilicraftdirector.previous_target", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, category));
        nextTargetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bilicraftdirector.next_target", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, category));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                localChat.append(message.getString(), receptionTimestamp.toEpochMilli()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) localChat.append(message.getString(), System.currentTimeMillis());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            localChat.clear();
            clearMapState();
            invalidateAiDecision();
            allowed = false;
            lastMessage = 0;
            lastHeartbeat = 0;
            lastSnapshotResponse = 0;
            snapshotPending = false;
            snapshotWorldKey = null;
            observeRequestTarget = null;
            durationRequestTarget = null;
            durationRequestEntryId = null;
            businessRequestCancelled = false;
            targetSwitchDirection = 0;
            activeScene = null;
            sceneArrival = null;
            showcaseQueue.resetSession();
            lastQueueRefreshAttempt = 0;
            cachedScenes = List.of();
            sceneCacheFailed = false;
            sceneCacheLimited = false;
            currentWorldKey = client.world == null ? null : client.world.getRegistryKey().getValue().toString();
            lastServer = client.getCurrentServerEntry();
            if (currentWorldKey != null) {
                loadLocalMapSettings();
                loadWorldPolicy();
                idlePoint = null;
            }
            try {
                cachedScenes = sceneCache.load(lastServer == null ? "singleplayer" : lastServer.address);
                status = "可手动启动本地导播；等待导播服务授权";
            } catch (IOException exception) {
                LOGGER.warn("Cannot read local scene cache", exception);
                sceneCacheFailed = true;
                status = "可继续本地基础环绕";
            }
            targets = cachedScenes.stream()
                    .filter(scene -> scene.world().equals(currentWorldKey))
                    .map(scene -> (ShowcaseTarget) new ShowcaseTarget.Scene(scene))
                    .sorted(TARGET_ORDER).toList();
            serverScenes = cachedScenes.stream().filter(scene -> scene.world().equals(currentWorldKey)
                    && !scene.id().startsWith("dynmap:")).toList();
            refreshMapTargets(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            shotTransition.clear();
            idlePoint = null;
            localChat.clear();
            clearMapState();
            invalidateAiDecision();
            resumeAfterReconnect = running || resumeAfterReconnect;
            running = false;
            allowed = false;
            requestedTarget = null;
            observeRequestTarget = null;
            durationRequestTarget = null;
            durationRequestEntryId = null;
            observeState = ObserveState.ENDED;
            targets = List.of();
            cachedScenes = List.of();
            activeScene = null;
            sceneArrival = null;
            showcaseQueue.resetSession();
            lastSelectedTarget = null;
            snapshotPending = false;
            snapshotWorldKey = null;
            businessRequestCancelled = false;
            currentWorldKey = null;
            targetSwitchDirection = 0;
            nextReconnect = System.currentTimeMillis() + 10_000;
            status = resumeAfterReconnect ? "连接中断，等待重连" : "未连接";
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            invalidateAiDecision();
            aiHttpClient.close();
            accountAvatar.close();
            mapClient.close();
            if (localControlServer != null) {
                localControlServer.close();
            }
        });
        HudElementRegistry.addLast(Identifier.of("bilicraftdirector", "transition"), (context, tickCounter) -> {
            double opacity = shotTransition.opacity(System.currentTimeMillis(), sceneArrival != null && sceneArrival.teleport());
            if (running && opacity > 0) {
                int alpha = (int) (MathHelper.clamp(opacity, 0, 1) * 255);
                context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), alpha << 24);
            }
        });
        HudElementRegistry.addLast(Identifier.of("bilicraftdirector", "target_name"), (context, tickCounter) -> {
            ShowcaseQueue.Entry current = showcaseQueue.current();
            if (!running || current == null) return;
            MinecraftClient client = MinecraftClient.getInstance();
            String label = current.target().name();
            context.fill(6, 6, client.textRenderer.getWidth(label) + 18, 24, 0x99000000);
            context.drawTextWithShadow(client.textRenderer, label, 12, 11, 0xFFFFFFFF);
        });
    }

    private void tick(MinecraftClient client) {
        synchronizeWorld(client);
        if (client.world != null && client.player != null) {
            if (mapBootstrapPending && client.getNetworkHandler() != null && client.currentScreen == null
                    && client.getOverlay() == null) {
                mapBootstrapPending = false;
                pullMapExternal(client, mapSource);
            }
        }
        synchronizeAiSettings();
        while (toggleKey.wasPressed()) {
            if (running || resumeAfterReconnect) {
                stop("已手动停止");
            } else {
                start();
            }
        }
        while (menuKey.wasPressed()) {
            if (client.currentScreen instanceof DirectorScreen screen) {
                screen.close();
            } else {
                client.setScreen(new DirectorScreen(client.currentScreen));
            }
        }
        int switchDirection = 0;
        while (previousTargetKey.wasPressed()) {
            switchDirection = -1;
        }
        while (nextTargetKey.wasPressed()) {
            switchDirection = 1;
        }
        if (client.currentScreen != null) {
            targetSwitchDirection = 0;
        } else if (switchDirection != 0 && running && canOperate(client)) {
            if (switchDirection > 0) {
                skipQueue();
            } else {
                switchTarget(-1);
            }
        }
        long now = System.currentTimeMillis();
        if (mapLoading && now >= mapRequestDeadline) {
            mapGeneration++;
            mapLoading = false;
            mapMetadataPending = false;
            mapExternalPending = false;
            mapRefreshRequested = false;
            mapDetail = "地图外链请求超时，保留已有本机观景缓存";
        }
        if (client.getNetworkHandler() == null) {
            if (resumeAfterReconnect && lastServer != null && now >= nextReconnect
                    && client.currentScreen instanceof DisconnectedScreen) {
                nextReconnect = now + 30_000;
                ConnectScreen.connect(new TitleScreen(), client, ServerAddress.parse(lastServer.address),
                        lastServer, false, null);
            } else if (resumeAfterReconnect && client.currentScreen != null
                    && !(client.currentScreen instanceof DisconnectedScreen)
                    && !(client.currentScreen instanceof ConnectScreen)) {
                resumeAfterReconnect = false;
                status = "已退出重连";
            }
            return;
        }
        if (resumeAfterReconnect && canOperate(client)) {
            start();
        }
        if (now - lastHeartbeat >= 2_000 && ClientPlayNetworking.canSend(DirectorPayload.ID)) {
            if (!worldPolicyReceived && lastHeartbeat == 0) {
                worldPolicyPending = true;
                worldPolicyDeadline = now + 8_000;
            }
            send(allowed ? new Message.Heartbeat() : new Message.Hello(Protocol.VERSION));
            lastHeartbeat = now;
        }
        if (worldPolicyPending && now >= worldPolicyDeadline) {
            worldPolicyPending = false;
        }
        if (allowed && now - lastMessage > 8_000) {
            revokeServerAccess("导播服务响应超时");
        }
        if (!businessRequestCancelled && hasBusinessRequest()
                && now >= businessRequestDeadline) {
            revokeServerAccess("导播请求响应超时");
        }
        if (running && !canOperate(client)) {
            stop("导播需要观察者模式");
        }
        if (running && requestedTarget != null
                && now >= observeDeadline + (observeState == ObserveState.NOTICE ? 5_000 : 0)) {
            send(new Message.Stop());
            endObservation("观察授权已到期");
        }
        if (running && canOperate(client)) {
            tickQueue(client, now);
        }
    }

    private void receive(Message message) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (message instanceof Message.HelloReply reply) {
            if (!reply.worldKey().equals(currentWorldKey)) {
                return;
            }
            lastMessage = System.currentTimeMillis();
            boolean wasAllowed = allowed;
            if (reply.version() != Protocol.VERSION || !reply.allowed()) {
                revokeServerAccess("导播服务未授权：" + reply.detail());
            } else {
                allowed = true;
                if (!wasAllowed) {
                    status = "导播服务已授权，可刷新玩家与场景目标";
                    requestMapSource(false);
                }
            }
        } else if (message instanceof Message.IdlePointSync sync) {
            if (!sync.worldKey().equals(currentWorldKey)) return;
            Message.IdlePose pose = sync.point();
            IdlePoint point = pose == null ? null : new IdlePoint(sync.worldKey(), pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            if (java.util.Objects.equals(idlePoint, point)) return;
            if (idlePoint != null && idlePoint.matches(activeScene)) stop("服务端挂机点已更改，请重新选择展示目标");
            idlePoint = point;
            invalidateAiDecision();
            refreshMapTargets(client);
        } else if (message instanceof Message.WorldPolicy policy) {
            if (!policy.worldKey().equals(currentWorldKey)) {
                return;
            }
            boolean changed = !policy.equals(worldPolicy);
            boolean wasPending = worldPolicyPending;
            worldPolicyReceived = true;
            worldPolicyPending = false;
            if (!changed && !worldPolicyCacheFailed) {
                return;
            }
            worldPolicy = policy;
            worldPolicyCacheFailed = false;
            try {
                worldPolicyCache.save(lastServer == null ? "singleplayer" : lastServer.address, policy);
            } catch (IOException exception) {
                worldPolicyCacheFailed = true;
                LOGGER.warn("Cannot save world patrol policy", exception);
            }
            if (changed) invalidateAiDecision();
            pruneAutomaticQueue(client);
            if (changed || wasPending) {
                status = policy.automaticPatrol() ? "当前世界已加入自动巡游范围"
                        : "当前世界不自动巡游或补点；仍可手动选择并加入目标";
            }
        } else if (message instanceof Message.Snapshot snapshot) {
            if (!snapshotPending) {
                return;
            }
            int direction = targetSwitchDirection;
            String requestWorld = snapshotWorldKey;
            lastSnapshotResponse = System.currentTimeMillis();
            if (completeBusinessRequest()) {
                return;
            }
            if (!allowed || !canOperate(client) || !currentWorldKey.equals(requestWorld)) {
                return;
            }
            lastMessage = System.currentTimeMillis();
            ArrayList<ShowcaseTarget> updated = new ArrayList<>();
            for (PlayerView player : snapshot.players()) {
                if (player.world().equals(currentWorldKey)) {
                    updated.add(new ShowcaseTarget.Player(player));
                }
            }
            serverScenes = snapshot.scenes().stream()
                    .filter(scene -> scene.world().equals(currentWorldKey)).toList();
            targets = updated.stream().sorted(TARGET_ORDER).toList();
            refreshMapTargets(client);
            status = "展示目标已更新（" + targets.size() + "）";
            if (direction != 0 && running && MinecraftClient.getInstance().currentScreen == null) {
                switchTarget(direction);
            }
        } else if (message instanceof Message.MapSource source) {
            if (!mapMetadataPending || source.requestId() != mapMetadataRequestId || !allowed
                    || !source.worldKey().equals(currentWorldKey)) {
                return;
            }
            boolean refresh = mapRefreshRequested;
            serverMapSource = source;
            if (localMapProfile.configured()) {
                mapSource = new Message.MapSource(0, currentWorldKey, true, localMapProfile.baseUrl(),
                        localMapProfile.dynmapWorld(), source.exclusions(), "使用本机地图外链，遵守服务端排除区");
                mapMetadataPending = false;
                mapRefreshRequested = false;
                mapLoading = mapExternalPending;
                if (!mapExternalPending) mapDetail = mapSource.detail();
                refreshMapTargets(client);
                return;
            }
            boolean changed = mapSource != null && (!mapSource.baseUrl().equals(source.baseUrl())
                    || !mapSource.dynmapWorld().equals(source.dynmapWorld()) || !mapSource.exclusions().equals(source.exclusions()));
            mapSource = source;
            mapMetadataPending = false;
            mapRefreshRequested = false;
            mapLoading = false;
            mapDetail = source.detail();
            if (!source.configured() || changed) {
                mapGeneration++;
                importedScenes = List.of();
                mapRegions = List.of();
                mapLoaded = false;
                mapSceneCount = 0;
                mapFetchedAt = 0;
                cachedScenes = cachedScenes.stream().filter(scene -> !scene.world().equals(currentWorldKey)
                        || !scene.id().startsWith("dynmap:")).toList();
            }
            refreshMapTargets(client);
            if (refresh && source.configured()) {
                pullMapExternal(client, source);
            }
        } else if (message instanceof Message.ObserveUpdate update) {
            lastMessage = System.currentTimeMillis();
            if (observeRequestTarget != null
                    && (update.targetId() == null || observeRequestTarget.equals(update.targetId()))) {
                if (completeBusinessRequest()) {
                    return;
                }
            }
            if (requestedTarget == null || (update.targetId() != null && !requestedTarget.equals(update.targetId()))) {
                return;
            }
            if (update.state() == ObserveState.ENDED) {
                endObservation(update.reason());
            } else if (running && allowed) {
                observeState = update.state();
                observeDeadline = update.deadlineEpochMillis();
                if (showcaseQueue.current() != null && showcaseQueue.current().target() instanceof ShowcaseTarget.Player) {
                    showcaseQueue.applyPlayerDuration(showcaseQueue.current().entryId(), update.durationSeconds());
                }
                status = update.state() == ObserveState.NOTICE ? "已通知玩家，等待 15 秒" : "已获服务端观察许可";
                stopMotion();
                if (update.state() == ObserveState.FOLLOWING) {
                    shotTransition.begin(System.currentTimeMillis());
                }
            }
        } else if (message instanceof Message.DurationReply reply) {
            if (durationRequestTarget == null || !durationRequestTarget.equals(reply.targetId())) {
                return;
            }
            String entryId = durationRequestEntryId;
            if (completeBusinessRequest()) {
                return;
            }
            lastMessage = System.currentTimeMillis();
            ShowcaseQueue.Entry current = showcaseQueue.current();
            if (current == null || !current.entryId().equals(entryId) || requestedTarget == null
                    || !requestedTarget.equals(reply.targetId())) {
                return;
            }
            if (reply.state() == ObserveState.ENDED) {
                endObservation(reply.reason());
                return;
            }
            showcaseQueue.applyPlayerDuration(entryId, reply.durationSeconds());
            if (observeState != ObserveState.FOLLOWING || reply.state() == ObserveState.FOLLOWING) {
                observeState = reply.state();
                observeDeadline = reply.deadlineEpochMillis();
            }
            status = reply.accepted() ? "玩家跟随总时长已获核准：" + reply.durationSeconds() + "秒"
                    : "时长调整未获核准，保留当前跟随：" + reply.reason();
        } else if (message instanceof Message.Error error) {
            if (snapshotPending) {
                lastSnapshotResponse = System.currentTimeMillis();
                if (completeBusinessRequest()) {
                    return;
                }
                status = "目标刷新失败，保留当前镜头：" + error.reason();
            } else if (observeRequestTarget != null) {
                UUID rejected = observeRequestTarget;
                if (completeBusinessRequest()) {
                    return;
                }
                if (rejected.equals(requestedTarget)) {
                    send(new Message.Stop());
                    endObservation(error.reason());
                }
                status = "玩家观察请求失败：" + error.reason();
            } else if (durationRequestTarget != null) {
                if (completeBusinessRequest()) {
                    return;
                }
                status = "时长调整失败，保留当前跟随：" + error.reason();
            } else {
                status = error.reason();
            }
        }
    }

    private boolean completeBusinessRequest() {
        boolean cancelled = businessRequestCancelled;
        snapshotPending = false;
        snapshotWorldKey = null;
        observeRequestTarget = null;
        durationRequestTarget = null;
        durationRequestEntryId = null;
        businessRequestCancelled = false;
        targetSwitchDirection = 0;
        if (cancelled) {
            allowed = false;
            lastHeartbeat = 0;
            status = "旧远程请求已排空，重新请求授权；本地导播继续";
        }
        return cancelled;
    }

    private boolean hasBusinessRequest() {
        return snapshotPending || observeRequestTarget != null || durationRequestTarget != null;
    }

    private void clearMapState() {
        mapGeneration++;
        localMapProfile = new LocalMapSettings.Profile("", "");
        mapSource = null;
        serverMapSource = null;
        worldPolicy = null;
        worldPolicyCacheFailed = false;
        worldPolicyReceived = false;
        worldPolicyPending = false;
        serverScenes = List.of();
        importedScenes = List.of();
        mapRegions = List.of();
        mapBootstrapPending = false;
        mapLoaded = false;
        mapMetadataPending = false;
        mapRefreshRequested = false;
        mapLoading = false;
        mapExternalPending = false;
        mapFetchedAt = 0;
        mapSceneCount = 0;
        mapDetail = "可配置本机地图外链，无需导播服务";
    }

    private void loadWorldPolicy() {
        worldPolicyPending = ClientPlayNetworking.canSend(DirectorPayload.ID);
        worldPolicyDeadline = System.currentTimeMillis() + 8_000;
        try {
            worldPolicy = worldPolicyCache.load(lastServer == null ? "singleplayer" : lastServer.address, currentWorldKey);
            worldPolicyCacheFailed = false;
        } catch (IOException exception) {
            worldPolicyCacheFailed = true;
            LOGGER.warn("Cannot read world patrol policy", exception);
        }
    }

    private boolean automaticPatrolAllowed() {
        return !worldPolicyPending && (worldPolicy != null ? worldPolicy.automaticPatrol() : !worldPolicyCacheFailed);
    }

    private boolean automaticTargetAvailable(ShowcaseTarget target) {
        return automaticPatrolAllowed() && targetAvailable(target);
    }

    private List<Message.RegionBounds> worldExclusions() {
        return worldPolicy != null ? worldPolicy.exclusions()
                : serverMapSource == null ? List.of() : serverMapSource.exclusions();
    }

    private boolean insideExcludedRegion(Vec3d position) {
        return worldExclusions().stream().anyMatch(region -> position.x >= region.minX() && position.x <= region.maxX()
                && position.z >= region.minZ() && position.z <= region.maxZ());
    }

    private void loadLocalMapSettings() {
        try {
            localMapProfile = localMapSettings.load(lastServer == null ? "singleplayer" : lastServer.address, currentWorldKey);
            if (localMapProfile.configured()) {
                mapBootstrapPending = localMapProfile.equals(LocalMapSettings.defaultProfile(
                        lastServer == null ? "singleplayer" : lastServer.address, currentWorldKey));
                mapSource = new Message.MapSource(0, currentWorldKey, true, localMapProfile.baseUrl(),
                        localMapProfile.dynmapWorld(), List.of(), mapBootstrapPending
                        ? "BiliCraft 默认地图已就绪，进入世界后自动拉取一次" : "本机地图配置已载入；点击拉取后才连接外链");
                mapDetail = mapSource.detail();
            }
        } catch (IOException | IllegalArgumentException exception) {
            mapDetail = "本机地图配置读取失败，请重新保存当前服务器与世界的外链配置";
            LOGGER.warn("Cannot read local map configuration ({})", exception.getClass().getSimpleName());
        }
    }

    private int changeIdlePoint(FabricClientCommandSource source, IdlePoint point) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (point != null && !idlePointAllowed(client, point)) {
            source.sendError(Text.literal("挂机点必须位于当前世界边界、高度范围内，且不能在排除区内"));
            return 0;
        }
        String command = point == null ? "director idle clear" : "director idle set "
                + point.scene().x() + " " + point.scene().y() + " " + point.scene().z() + " " + point.yaw() + " " + point.pitch();
        client.getNetworkHandler().sendChatCommand(command);
        source.sendFeedback(Text.literal("挂机点请求已发送；等待服务器校验权限并保存"));
        return 1;
    }

    private boolean idlePointAllowed(MinecraftClient client, IdlePoint point) {
        SceneView scene = point.scene();
        return scene.world().equals(currentWorldKey) && scene.y() >= client.world.getBottomY()
                && scene.y() <= client.world.getTopYInclusive() && client.world.getWorldBorder().contains(scene.x(), scene.z())
                && !insideExcludedRegion(new Vec3d(scene.x(), scene.y(), scene.z()));
    }

    private boolean requestMapSource(boolean refresh) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (refresh && !canOperate(client)) {
            mapDetail = "请先连接游戏并进入观察者模式";
            return false;
        }
        if (refresh && mapSource != null && mapSource.configured() && (localMapProfile.configured() || !allowed)) {
            if (mapExternalPending) {
                mapDetail = "地图外链正在拉取，请等待本次完成";
                return false;
            }
            pullMapExternal(client, mapSource);
            return true;
        }
        if (!allowed) {
            mapDetail = "请先配置本机地图外链；服务端来源需要导播服务授权";
            return false;
        }
        if (mapLoading && !mapMetadataPending) {
            mapDetail = "地图外链正在拉取，请等待本次完成";
            return false;
        }
        mapRefreshRequested |= refresh;
        if (!mapMetadataPending) {
            long requestId = ++mapRequestSequence;
            if (!send(new Message.MapRequest(requestId))) {
                mapRefreshRequested = false;
                mapDetail = "地图外链配置请求发送失败";
                return false;
            }
            mapMetadataPending = true;
            mapMetadataRequestId = requestId;
            mapRequestDeadline = System.currentTimeMillis() + 8_000;
        }
        mapLoading = true;
        mapDetail = refresh ? "正在读取世界映射，随后由本机直连地图外链" : "正在读取地图外链配置";
        return true;
    }

    private void pullMapExternal(MinecraftClient client, Message.MapSource source) {
        long generation = ++mapGeneration;
        String serverAddress = lastServer == null ? "singleplayer" : lastServer.address;
        mapLoading = true;
        mapExternalPending = true;
        mapRequestDeadline = System.currentTimeMillis() + 30_000;
        mapDetail = "本机正在直连 Dynmap 外链；数据不经过 Minecraft 服务器";
        mapClient.fetch(source.baseUrl(), source.dynmapWorld(), source.worldKey()).whenComplete((result, error) -> client.execute(() -> {
            synchronizeWorld(client);
            if (allowed && System.currentTimeMillis() - lastMessage > 8_000) {
                revokeServerAccess("导播服务响应超时");
            }
            if (generation != mapGeneration
                    || !serverAddress.equals(lastServer == null ? "singleplayer" : lastServer.address)
                    || !source.worldKey().equals(currentWorldKey)) {
                return;
            }
            mapLoading = false;
            mapExternalPending = false;
            if (error != null) {
                mapDetail = "地图外链拉取失败，保留已有本机观景缓存；请检查外链后重试";
                return;
            }
            importedScenes = result.scenes();
            mapRegions = result.regions();
            mapLoaded = true;
            mapSceneCount = importedScenes.size();
            mapFetchedAt = result.fetchedAtEpochMillis();
            mapDetail = result.note();
            refreshMapTargets(client);
        }));
    }

    private void refreshMapTargets(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        List<SceneView> mapCandidates = List.of();
        if (mapSource != null && mapSource.configured()) {
            mapCandidates = !mapLoaded
                    ? cachedScenes.stream().filter(scene -> scene.world().equals(currentWorldKey)
                    && scene.id().startsWith(DynmapMapClient.scenePrefix(mapSource.baseUrl(), mapSource.dynmapWorld(), currentWorldKey))
                    && DynmapMapClient.eligibleName(scene.name())).toList()
                    : importedScenes;
        }
        List<SceneView> scenes = SceneLibrary.merge(currentWorldKey, serverScenes, mapCandidates);
        ArrayList<ShowcaseTarget> updated = new ArrayList<>(targets.stream().filter(target -> target instanceof ShowcaseTarget.Player).toList());
        scenes.forEach(scene -> updated.add(new ShowcaseTarget.Scene(scene)));
        if (idlePoint != null) updated.add(new ShowcaseTarget.Scene(idlePoint.scene()));
        targets = updated.stream().sorted(TARGET_ORDER).toList();
        try {
            SceneLibrary.CacheSelection selection = SceneLibrary.selectCache(currentWorldKey, scenes, cachedScenes);
            sceneCacheLimited = selection.limited();
            cachedScenes = selection.scenes();
            sceneCache.save(lastServer == null ? "singleplayer" : lastServer.address, cachedScenes);
            sceneCacheFailed = false;
        } catch (IOException exception) {
            LOGGER.warn("Cannot save local scene cache", exception);
            sceneCacheFailed = true;
        }
    }

    private void revokeServerAccess(String reason) {
        if (idlePoint != null) {
            if (idlePoint.matches(activeScene)) stop("服务端挂机点暂不可用：" + reason);
            idlePoint = null;
            invalidateAiDecision();
            refreshMapTargets(MinecraftClient.getInstance());
        }
        mapMetadataPending = false;
        mapRefreshRequested = false;
        mapLoading = mapExternalPending;
        if (!mapExternalPending) mapDetail = "导播服务失联；已取得的外链、观景缓存与世界规则仍可使用";
        if (requestedTarget != null || (observeRequestTarget != null && !businessRequestCancelled)) {
            send(new Message.Stop());
        }
        if (requestedTarget != null) {
            endObservation(reason);
        }
        allowed = false;
        businessRequestCancelled = hasBusinessRequest();
        showcaseQueue.removePlayers();
        targetSwitchDirection = 0;
        targets = targets.stream().filter(target -> target instanceof ShowcaseTarget.Scene).toList();
        status = reason + "；仅使用本地导播与观景缓存";
    }

    private boolean canOperate(MinecraftClient client) {
        return client.player != null && client.player.isSpectator() && client.world != null
                && client.getNetworkHandler() != null;
    }

    private void invalidateAiDecision() {
        aiGeneration++;
        aiSession.invalidate();
    }

    private void synchronizeAiSettings() {
        if (aiObservedSettingsRevision == aiSettings.revision) {
            return;
        }
        aiObservedSettingsRevision = aiSettings.revision;
        invalidateAiDecision();
        aiTestEpoch++;
        aiTestPending = false;
        aiModels = List.of();
        aiTestStatus = "配置已更新，尚未测试；模型列表不代表Chat能力";
        aiSession.status = aiSettings.enabled ? "AI策略已启用，等待队尾缺口；至少60秒一次" : "AI策略未启用；使用本地策略";
    }

    private StrategyPrompt.PreparedDecision prepareAiDecision(long now) {
        HashSet<String> occupied = new HashSet<>();
        if (showcaseQueue.current() != null) {
            occupied.add(showcaseQueue.current().target().id());
        }
        for (ShowcaseQueue.Entry entry : showcaseQueue.pending()) {
            occupied.add(entry.target().id());
        }
        boolean remoteAllowed = allowed && !businessRequestCancelled && now - lastMessage <= 8_000;
        ArrayList<StrategyPrompt.Candidate> candidates = new ArrayList<>();
        ArrayList<ShowcaseTarget> eligible = new ArrayList<>();
        for (ShowcaseTarget target : targets) {
            if ((!(target instanceof ShowcaseTarget.Player) || remoteAllowed) && automaticTargetAvailable(target)) {
                eligible.add(target);
            }
        }
        for (ShowcaseTarget target : showcaseQueue.sampleAutomaticCandidates(eligible, StrategyPrompt.MAX_CANDIDATES, now)) {
            StrategyPrompt.Kind kind = target instanceof ShowcaseTarget.Player ? StrategyPrompt.Kind.PLAYER
                    : StrategyPrompt.Kind.valueOf(((ShowcaseTarget.Scene) target).view().kind().name());
            String label = target.name().codePoints().filter(point -> !Character.isISOControl(point))
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            candidates.add(new StrategyPrompt.Candidate(target.id(), kind, kind == StrategyPrompt.Kind.PLAYER ? 20 : 80, label));
        }
        int missing = queueSettings.autoFill ? Math.max(0, queueSettings.bufferSize - showcaseQueue.pending().size()) : 0;
        return StrategyPrompt.build(candidates, missing, occupied);
    }

    /** Returns true while AI owns this fill attempt; all failures/cooldowns use the existing local queue policy. */
    private boolean tickAiDecision(MinecraftClient client, long now, boolean paused) {
        if (!automaticPatrolAllowed() || !aiSettings.enabled || !aiSettings.profiles.get(aiSettings.selected).complete()) {
            return false;
        }
        if (aiSession.pending()) {
            return true;
        }
        if (paused || aiSession.nextRequestInSeconds(now) > 0) {
            return false;
        }
        StrategyPrompt.PreparedDecision prepared = prepareAiDecision(now);
        if (!prepared.needsDecision()) {
            return false;
        }
        AiStrategySession.Request request = aiSession.begin(now, aiGeneration, aiSettings.revision,
                currentWorldKey, aiSettings.selected);
        if (request == null) {
            return false;
        }
        AiSettings.Profile selected = aiSettings.profiles.get(aiSettings.selected);
        AiSettings.Profile captured = new AiSettings.Profile(selected.baseUrl, selected.model, selected.apiKey);
        aiHttpClient.choose(request.provider(), captured, prepared).whenComplete((completion, failure) -> client.execute(() -> {
            synchronizeWorld(client);
            synchronizeAiSettings();
            if (!aiSession.finish(request, completion)) {
                return;
            }
            if (!running || !canOperate(client) || !automaticPatrolAllowed() || !queueSettings.autoFill || !aiSettings.enabled
                    || request.generation() != aiGeneration || request.settingsRevision() != aiSettings.revision
                    || request.provider() != aiSettings.selected || !request.world().equals(currentWorldKey)) {
                aiSession.status = "旧AI结果已失效；不会启动或改写队列";
                return;
            }
            if (failure != null || completion == null || !completion.usable()) {
                aiSession.status = "AI请求失败、截断或思考模式不兼容；使用本地策略，60秒间隔仍生效";
                return;
            }
            try {
                List<String> picks = prepared.parseResponse(completion.content());
                ArrayList<ShowcaseTarget> choices = new ArrayList<>();
                for (String id : picks) {
                    ShowcaseTarget target = targets.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
                    if (target != null && automaticTargetAvailable(target)
                            && (!(target instanceof ShowcaseTarget.Player) || System.currentTimeMillis() - lastMessage <= 8_000)) {
                        choices.add(target);
                    }
                }
                int added = showcaseQueue.appendAutomatic(choices, queueSettings.bufferSize,
                        queueSettings.sceneDurationSeconds, System.currentTimeMillis());
                aiSession.status = "AI已向队尾补充" + added + "项；候选不足时使用本地策略";
            } catch (IOException exception) {
                aiSession.status = "AI短JSON校验失败；使用本地策略，未执行模型动作";
            }
        }));
        return true;
    }

    String aiConnectionStatus() {
        return aiTestStatus;
    }

    boolean testAiConnection() {
        synchronizeAiSettings();
        if (aiTestPending) {
            status = "模型列表请求正在进行";
            return false;
        }
        AiSettings.Profile profile = aiSettings.profiles.get(aiSettings.selected);
        if (profile.baseUrl.isBlank() || profile.apiKey.isBlank()) {
            status = "请先保存API地址与密钥；模型可暂留空";
            return false;
        }
        long epoch = ++aiTestEpoch;
        long revision = aiSettings.revision;
        aiTestPending = true;
        aiTestStatus = "正在GET /models；不会发送Chat请求";
        AiSettings.Profile captured = new AiSettings.Profile(profile.baseUrl, profile.model, profile.apiKey);
        MinecraftClient client = MinecraftClient.getInstance();
        aiHttpClient.models(captured).whenComplete((models, failure) -> client.execute(() -> {
            synchronizeAiSettings();
            if (epoch != aiTestEpoch || revision != aiSettings.revision) {
                return;
            }
            aiTestPending = false;
            aiModels = failure == null ? models : List.of();
            aiTestStatus = failure == null ? "GET /models成功（" + models.size() + "个）；尚未验证Chat能力或产生Chat用量"
                    : "GET /models失败：请检查地址、密钥、网络与权限；未发送Chat请求";
        }));
        status = "已开始测试已保存的提供方；结果见AI连接状态";
        return true;
    }

    private void pruneAutomaticQueue(MinecraftClient client) {
        if (!automaticPatrolAllowed()) {
            ShowcaseQueue.Entry removed = showcaseQueue.pruneAutomatic(target -> false);
            if (removed != null) {
                if (requestedTarget != null) {
                    send(new Message.Stop());
                    businessRequestCancelled = hasBusinessRequest();
                    endObservation("当前世界已移出自动巡游范围");
                }
                activeScene = null;
                sceneArrival = null;
                retreatUntil = 0;
                shotTransition.clear();
                stopMotion();
                resetOrbit(client.player);
                status = "当前世界不自动巡游或补点；手动待播项继续播放";
            }
        }
    }

    private void tickQueue(MinecraftClient client, long now) {
        pruneAutomaticQueue(client);
        ShowcaseQueue.Entry current = showcaseQueue.current();
        boolean paused = client.currentScreen != null || client.isPaused();
        if (current != null && current.target() instanceof ShowcaseTarget.Scene) {
            ShowcaseTarget latest = targets.stream().filter(target -> target.id().equals(current.target().id()))
                    .findFirst().orElse(null);
            boolean targetValid = latest != null && targetAvailable(latest);
            if (latest instanceof ShowcaseTarget.Scene scene && activeScene != null) {
                activeScene = scene.view();
                showcaseQueue.refreshCurrentTarget(latest);
            }
            boolean arrivingThisTick = sceneArrival != null || shotTransition.preparing();
            if (sceneArrival != null && targetValid && activeScene != null) {
                if (!paused && sceneArrival.takeTeleportCommand(now)) {
                    String command = sceneArrival.teleportCommand();
                    if (idlePoint != null && idlePoint.matches(activeScene)) command += " " + idlePoint.yaw() + " " + idlePoint.pitch();
                    client.getNetworkHandler().sendChatCommand(command);
                    status = "已请求本人传送，等待到达并加载场景";
                }
                if (sceneArrival.arrived(new CameraMotion.Point(client.player.getX(), client.player.getY(),
                        client.player.getZ()), sceneChunksLoaded(client, sceneArrival.destination()))) {
                    sceneArrival = null;
                    cameraMotion.reset();
                    status = "已到达，正在展示 " + activeScene.name();
                } else if (sceneArrival.timedOut(now)) {
                    targetValid = false;
                    status = sceneArrival.teleport() ? "前往场景超时，已跳过；请检查本人 tp 权限或区块加载"
                            : "平滑前往场景超时，已跳过；请检查路线边界与区块加载";
                }
            }
            if (targetValid && activeScene != null && sceneArrival == null && !shotTransition.preparing() && !paused) {
                showcaseQueue.markPlayed(current.target().id());
            }
            if (!targetValid || activeScene == null || showcaseQueue.tickScene(now, paused || arrivingThisTick)) {
                if (!targetValid || activeScene == null) {
                    showcaseQueue.suppress(current.target().id(), now + 30_000);
                }
                activeScene = null;
                sceneArrival = null;
                if (arrivingThisTick) shotTransition.reveal(now);
                showcaseQueue.finishCurrent();
                stopMotion();
                resetOrbit(client.player);
            }
        } else if (current != null && requestedTarget == null) {
            showcaseQueue.finishCurrent();
        }
        if (automaticPatrolAllowed() && queueSettings.autoFill && showcaseQueue.pending().size() < queueSettings.bufferSize) {
            if (!tickAiDecision(client, now, paused)) {
                showcaseQueue.fill(targets.stream().filter(this::automaticTargetAvailable).toList(), queueSettings.bufferSize,
                        queueSettings.sceneDurationSeconds, now);
            }
        }
        if (automaticPatrolAllowed() && queueSettings.autoFill && showcaseQueue.pending().size() < queueSettings.bufferSize
                && allowed && !businessRequestCancelled && !hasBusinessRequest()
                && !paused && now - lastQueueRefreshAttempt >= 60_000) {
            lastQueueRefreshAttempt = now;
            requestSnapshot(0);
        }
        if (showcaseQueue.current() != null || paused || now < retreatUntil) {
            return;
        }
        ShowcaseQueue.Entry next;
        while ((next = showcaseQueue.peek()) != null) {
            if (next.target() instanceof ShowcaseTarget.Player
                    && (hasBusinessRequest() || !allowed || businessRequestCancelled)) {
                if (!allowed && now - lastMessage > 8_000) {
                    revokeServerAccess("玩家数据重新同步超时");
                    continue;
                }
                return;
            }
            showcaseQueue.poll();
            String targetId = next.target().id();
            ShowcaseTarget latest = targets.stream().filter(target -> target.id().equals(targetId)).findFirst().orElse(null);
            if (latest == null || !targetAvailable(latest)
                    || (next.source() == ShowcaseQueue.Source.AUTO && !automaticTargetAvailable(latest))) {
                showcaseQueue.suppress(next.target().id(), now + 30_000);
                continue;
            }
            if (beginTarget(latest, next.durationSeconds())) {
                showcaseQueue.start(new ShowcaseQueue.Entry(next.entryId(), latest, next.source(), next.durationSeconds()), now);
                return;
            }
            showcaseQueue.suppress(next.target().id(), now + 30_000);
        }
    }

    private boolean skipQueue() {
        if (!running || showcaseQueue.current() == null) {
            status = running ? "当前为基础巡游，无队列项可跳过" : "导播已停止，跳过不会启动导播";
            return false;
        }
        invalidateAiDecision();
        businessRequestCancelled = hasBusinessRequest();
        targetSwitchDirection = 0;
        if (showcaseQueue.current().target() instanceof ShowcaseTarget.Scene) {
            showcaseQueue.suppressAutomatic(showcaseQueue.current().target().id(), System.currentTimeMillis() + 30_000);
        }
        if (requestedTarget != null) {
            send(new Message.Stop());
            endObservation("手动跳过队列项");
        }
        showcaseQueue.finishCurrent();
        activeScene = null;
        sceneArrival = null;
        shotTransition.clear();
        stopMotion();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            resetOrbit(client.player);
        }
        status = "已跳过当前项，待播队列会继续";
        return true;
    }

    private void synchronizeWorld(MinecraftClient client) {
        if (client.world == null || client.getNetworkHandler() == null) {
            return;
        }
        String worldKey = client.world.getRegistryKey().getValue().toString();
        if (worldKey.equals(currentWorldKey)) {
            return;
        }
        clearMapState();
        if (requestedTarget != null || (observeRequestTarget != null && !businessRequestCancelled)) {
            send(new Message.Stop());
        }
        businessRequestCancelled = hasBusinessRequest();
        invalidateAiDecision();
        showcaseQueue.clear();
        shotTransition.clear();
        lastQueueRefreshAttempt = 0;
        currentWorldKey = worldKey;
        loadLocalMapSettings();
        loadWorldPolicy();
        idlePoint = null;
        serverScenes = cachedScenes.stream().filter(scene -> scene.world().equals(worldKey)
                && !scene.id().startsWith("dynmap:")).toList();
        allowed = false;
        lastMessage = 0;
        lastHeartbeat = 0;
        requestedTarget = null;
        observeState = ObserveState.ENDED;
        activeScene = null;
        sceneArrival = null;
        lastSelectedTarget = null;
        targetSwitchDirection = 0;
        previousTargetPosition = null;
        targetMovingUntil = 0;
        retreatUntil = 0;
        stopMotion();
        if (client.player != null) {
            resetOrbit(client.player);
        }
        // Pending responses still own their business slot, but can no longer select an old-world target.
        targets = cachedScenes.stream().filter(scene -> scene.world().equals(currentWorldKey))
                .map(scene -> (ShowcaseTarget) new ShowcaseTarget.Scene(scene)).sorted(TARGET_ORDER).toList();
        refreshMapTargets(client);
        status = "世界已切换，使用当前世界本地导播并重新请求授权";
    }

    boolean start() {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (!canOperate(client)) {
            status = "导播需要观察者模式";
            return false;
        }
        resumeAfterReconnect = false;
        shotTransition.clear();
        running = true;
        client.setCameraEntity(client.player);
        resetOrbit(client.player);
        status = allowed ? "本地导播已启动，可选择玩家、观景点或巡游区域"
                : "本地导播已启动；无导播服务时仅使用本地观景缓存";
        return true;
    }

    public void stop(String reason) {
        shotTransition.clear();
        invalidateAiDecision();
        if (requestedTarget != null || (observeRequestTarget != null && !businessRequestCancelled)) {
            send(new Message.Stop());
        }
        businessRequestCancelled = hasBusinessRequest();
        if (requestedTarget != null) {
            showcaseQueue.suppress("player:" + requestedTarget, System.currentTimeMillis() + 30 * 60_000L);
        }
        showcaseQueue.finishCurrent();
        running = false;
        resumeAfterReconnect = false;
        requestedTarget = null;
        observeState = ObserveState.ENDED;
        previousTargetPosition = null;
        targetMovingUntil = 0;
        lastSelectedTarget = null;
        activeScene = null;
        sceneArrival = null;
        // Keep business requests occupied until their responses drain on this connection.
        targetSwitchDirection = 0;
        retreatUntil = 0;
        stopMotion();
        status = reason;
    }

    boolean requestSnapshot(int switchDirection) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (!canOperate(client)) {
            status = "请先进入观察者模式";
            return false;
        }
        if (hasBusinessRequest()) {
            if (businessRequestCancelled) {
                status = "旧远程请求待排空；可切换本地场景，远程恢复可重连";
                if (switchDirection != 0 && running && client.currentScreen == null) {
                    return switchTarget(switchDirection);
                }
            } else {
                status = "导播请求处理中，请稍后再切换";
            }
            return false;
        }
        if (System.currentTimeMillis() - lastSnapshotResponse < 1_000) {
            status = "目标刷新冷却中，请稍后再切换";
            return false;
        }
        if (allowed && ClientPlayNetworking.canSend(DirectorPayload.ID)) {
            if (send(new Message.SnapshotRequest())) {
                snapshotPending = true;
                snapshotWorldKey = currentWorldKey;
                businessRequestDeadline = System.currentTimeMillis() + 8_000;
                targetSwitchDirection = switchDirection;
                status = "正在刷新展示目标";
                return true;
            } else {
                revokeServerAccess("目标请求发送失败");
                return false;
            }
        } else {
            if (allowed) {
                revokeServerAccess("导播服务连接失效");
            }
            if (localMapProfile.configured()) refreshMapTargets(client);
            lastSnapshotResponse = System.currentTimeMillis();
            status = "本地观景目标（" + targets.size() + "），玩家展示需要导播服务授权";
            if (switchDirection != 0 && running && client.currentScreen == null) {
                return switchTarget(switchDirection);
            }
            return true;
        }
    }

    private boolean switchTarget(int direction) {
        String currentId = requestedTarget != null ? "player:" + requestedTarget
                : activeScene == null ? null : new ShowcaseTarget.Scene(activeScene).id();
        List<ShowcaseTarget> candidates = targets.stream()
                .filter(target -> !target.id().equals(currentId))
                .filter(this::targetAvailable).sorted(TARGET_ORDER).toList();
        if (candidates.isEmpty()) {
            status = "当前世界暂无其他可展示目标，保留当前镜头";
            return false;
        }
        ShowcaseTarget next = direction > 0 ? candidates.getFirst() : candidates.getLast();
        if (lastSelectedTarget != null) {
            for (ShowcaseTarget candidate : candidates) {
                int comparison = TARGET_ORDER.compare(candidate, lastSelectedTarget);
                if (direction > 0 && comparison > 0) {
                    next = candidate;
                    break;
                }
                if (direction < 0 && comparison < 0) {
                    next = candidate;
                }
            }
        }
        return showTarget(next);
    }

    boolean showTarget(ShowcaseTarget target) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (!running || !canOperate(client)) {
            status = "请先启动导播";
            return false;
        }
        if (showcaseQueue.current() != null && showcaseQueue.current().target().id().equals(target.id())) {
            status = "当前已在展示该目标";
            return true;
        }
        ShowcaseQueue.Entry entry = showcaseQueue.manualEntry(target,
                showcaseQueue.defaultDuration(target, queueSettings.sceneDurationSeconds));
        if (!beginTarget(target, entry.durationSeconds())) {
            return false;
        }
        invalidateAiDecision();
        showcaseQueue.start(entry, System.currentTimeMillis());
        return true;
    }

    private boolean beginTarget(ShowcaseTarget target, int durationSeconds) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (!running || !canOperate(client)) {
            status = "请先启动导播";
            return false;
        }
        if (target instanceof ShowcaseTarget.Player && hasBusinessRequest()) {
            status = "导播请求处理中，请稍后再展示";
            return false;
        }
        if (!targetAvailable(target)) {
            status = target instanceof ShowcaseTarget.Player ? "玩家需要服务授权，且必须已加载并在 96 格内"
                    : "场景需属于当前世界，且符合世界边界、高度与已知排除区规则";
            return false;
        }
        String currentId = requestedTarget != null ? "player:" + requestedTarget
                : activeScene == null ? null : new ShowcaseTarget.Scene(activeScene).id();
        if (target.id().equals(currentId)) {
            status = "当前已在展示该目标";
            return true;
        }
        if (requestedTarget != null) {
            send(new Message.Stop());
            endObservation("切换展示目标");
        }
        if (target instanceof ShowcaseTarget.Player player) {
            if (!send(new Message.ObserveRequest(player.view().id(), durationSeconds))) {
                revokeServerAccess("玩家观察请求发送失败");
                return false;
            }
            activeScene = null;
            sceneArrival = null;
            shotTransition.clear();
            requestedTarget = player.view().id();
            observeRequestTarget = requestedTarget;
            businessRequestDeadline = System.currentTimeMillis() + 8_000;
            observeState = ObserveState.NOTICE;
            observeDeadline = System.currentTimeMillis() + 20_000;
            stopMotion();
            status = "正在请求玩家展示预告";
        } else if (target instanceof ShowcaseTarget.Scene scene) {
            activeScene = scene.view();
            sceneArrival = idlePoint != null && idlePoint.matches(activeScene)
                    ? new SceneArrival(idlePoint.position(), System.currentTimeMillis())
                    : SceneArrival.plan(new CameraMotion.Point(client.player.getX(), client.player.getY(), client.player.getZ()),
                    new CameraMotion.Point(activeScene.x(), activeScene.y(), activeScene.z()),
                    settings.radius, settings.heightY, settings.speed, System.currentTimeMillis());
            cameraMotion.reset();
            retreatUntil = 0;
            stopMotion();
            shotTransition.begin(System.currentTimeMillis());
            status = (sceneArrival.teleport() ? "正在传送前往 " : "正在平滑前往 ") + scene.label();
        }
        lastSelectedTarget = target;
        client.setScreen(null);
        return true;
    }

    private boolean targetAvailable(ShowcaseTarget target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!canOperate(client) || showcaseQueue.isSuppressed(target.id(), System.currentTimeMillis())) {
            return false;
        }
        if (target instanceof ShowcaseTarget.Player player) {
            PlayerEntity entity = client.world.getPlayerByUuid(player.view().id());
            return allowed && !businessRequestCancelled && player.view().world().equals(currentWorldKey)
                    && entity != null && entity.squaredDistanceTo(client.player) <= 96 * 96;
        }
        SceneView scene = ((ShowcaseTarget.Scene) target).view();
        if (scene.id().equals(IdlePoint.SCENE_ID)) {
            return allowed && idlePoint != null && idlePoint.matches(scene) && idlePointAllowed(client, idlePoint);
        }
        double radius = Math.max(scene.radius(), settings.radius);
        if (worldExclusions().stream().anyMatch(region -> scene.x() + radius >= region.minX()
                && scene.x() - radius <= region.maxX() && scene.z() + radius >= region.minZ()
                && scene.z() - radius <= region.maxZ())) {
            return false;
        }
        double verticalSwing = settings.mode == CameraMotion.Mode.CRANE ? settings.motionAmplitude / 4 : 0;
        if (!scene.world().equals(client.world.getRegistryKey().getValue().toString())
                || scene.y() < client.world.getBottomY()
                || settings.heightY - verticalSwing < client.world.getBottomY()
                || settings.heightY + verticalSwing > client.world.getTopYInclusive()
                || !client.world.getWorldBorder().contains(scene.x() - radius, scene.z() - radius)
                || !client.world.getWorldBorder().contains(scene.x() + radius, scene.z() + radius)) {
            return false;
        }
        return true;
    }

    private boolean sceneChunksLoaded(MinecraftClient client, CameraMotion.Point position) {
        return client.world.getChunkManager().isChunkLoaded(MathHelper.floor(position.x()) >> 4,
                MathHelper.floor(position.z()) >> 4);
    }

    private void endObservation(String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean wasFollowing = observeState == ObserveState.FOLLOWING;
        PlayerEntity target = client.world == null || requestedTarget == null
                ? null : client.world.getPlayerByUuid(requestedTarget);
        if (durationRequestTarget != null && durationRequestTarget.equals(requestedTarget)) {
            businessRequestCancelled = true;
        }
        if (requestedTarget != null) {
            showcaseQueue.suppress("player:" + requestedTarget, System.currentTimeMillis() + 30 * 60_000L);
        }
        if (showcaseQueue.current() != null && showcaseQueue.current().target() instanceof ShowcaseTarget.Player player
                && player.view().id().equals(requestedTarget)) {
            showcaseQueue.finishCurrent();
        }
        requestedTarget = null;
        observeState = ObserveState.ENDED;
        previousTargetPosition = null;
        targetMovingUntil = 0;
        stopMotion();
        if (running && client.player != null) {
            if (wasFollowing) {
                Vec3d away = target == null ? client.player.getRotationVec(1).multiply(-1)
                        : client.player.getEntityPos().subtract(target.getEntityPos());
                if (away.horizontalLengthSquared() < 0.01) {
                    double yaw = Math.toRadians(client.player.getYaw());
                    away = new Vec3d(Math.sin(yaw), 0, -Math.cos(yaw));
                }
                retreatDirection = new Vec3d(away.x, 0.25, away.z).normalize();
                retreatUntil = System.currentTimeMillis() + 4_000;
                shotTransition.begin(System.currentTimeMillis());
            }
            resetOrbit(client.player);
        }
        status = "观察已结束：" + reason;
    }

    private void resetOrbit(ClientPlayerEntity player) {
        Vec3d direction = new Vec3d(player.getRotationVec(1).x, 0, player.getRotationVec(1).z).normalize();
        orbitCenter = player.getEntityPos().add(direction.multiply(settings.radius));
        cameraMotion.reset();
    }

    /** Called before vanilla movement. Velocity then passes through vanilla travel and movement packets. */
    public void driveCamera(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (!running) {
            return;
        }
        if (!canOperate(client)) {
            stop("导播需要观察者模式");
            return;
        }
        if (allowed && System.currentTimeMillis() - lastMessage > 8_000) {
            revokeServerAccess("导播服务响应超时");
        }
        if (client.currentScreen != null || observeState == ObserveState.NOTICE
                || (sceneArrival != null && sceneArrival.teleport())) {
            player.setVelocity(Vec3d.ZERO);
            return;
        }
        if (!automaticPatrolAllowed()
                && (showcaseQueue.current() == null || showcaseQueue.current().source() == ShowcaseQueue.Source.AUTO)) {
            stopMotion();
            status = "当前世界不自动巡游；请选择目标或手动加入待播队列";
            return;
        }
        long now = System.currentTimeMillis();
        if (requestedTarget != null && now >= observeDeadline) {
            send(new Message.Stop());
            endObservation("观察授权已到期");
        }
        if (shotTransition.preparing() && !shotTransition.opaque(now)) {
            player.setVelocity(Vec3d.ZERO);
            return;
        }
        if (idlePoint != null && idlePoint.matches(activeScene)) {
            if (!targetAvailable(new ShowcaseTarget.Scene(activeScene))) {
                activeScene = null;
                stopMotion();
                status = "挂机点已超出边界、高度或进入排除区，已停止展示";
                return;
            }
            player.setVelocity(Vec3d.ZERO);
            if (!sceneChunksLoaded(client, idlePoint.position())) {
                status = "等待挂机点区块加载";
                return;
            }
            player.setPosition(activeScene.x(), activeScene.y(), activeScene.z());
            if (shotTransition.preparing()) {
                revealShot(player, idlePoint.yaw(), idlePoint.pitch(), now);
            } else {
                player.setYaw(idlePoint.yaw());
                player.setPitch(idlePoint.pitch());
            }
            return;
        }
        if (retreatUntil > now) {
            float awayYaw = (float) Math.toDegrees(Math.atan2(retreatDirection.z, retreatDirection.x)) - 90;
            if (shotTransition.preparing()) {
                revealShot(player, awayYaw, 0, now);
                return;
            }
            Vec3d velocity = retreatDirection.multiply(settings.speed / 20);
            Vec3d nextPosition = player.getEntityPos().add(velocity);
            if (!client.world.getChunkManager().isChunkLoaded(MathHelper.floor(nextPosition.x) >> 4,
                    MathHelper.floor(nextPosition.z) >> 4) || !client.world.getWorldBorder().contains(nextPosition)
                    || nextPosition.y < client.world.getBottomY() || nextPosition.y > client.world.getTopYInclusive()
                    || insideExcludedRegion(nextPosition)) {
                retreatUntil = 0;
                stopMotion();
                resetOrbit(player);
                return;
            }
            player.setVelocity(velocity);
            float turnLimit = (float) (settings.turnRateDegreesPerSecond / 20);
            player.setYaw(player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(awayYaw - player.getYaw()), -turnLimit, turnLimit));
            player.setPitch(player.getPitch() + MathHelper.clamp(-player.getPitch(), -turnLimit * 2 / 3, turnLimit * 2 / 3));
            resetOrbit(player);
            return;
        }
        CameraMotion.Subject subject;
        if (observeState == ObserveState.FOLLOWING && requestedTarget != null) {
            PlayerEntity target = client.world.getPlayerByUuid(requestedTarget);
            if (target == null || target.squaredDistanceTo(player) > 96 * 96
                    || insideExcludedRegion(target.getEntityPos())) {
                send(new Message.Stop());
                endObservation("玩家已离开可跟随范围或进入排除区");
                return;
            }
            if (!shotTransition.preparing()) showcaseQueue.markPlayed("player:" + requestedTarget);
            Vec3d center = target.getEntityPos().add(0, 1.2, 0);
            if (previousTargetPosition != null
                    && target.getEntityPos().subtract(previousTargetPosition).horizontalLengthSquared() > 0.0001) {
                targetMovingUntil = now + 500;
            }
            previousTargetPosition = target.getEntityPos();
            subject = new CameraMotion.Subject("player:" + requestedTarget,
                    new CameraMotion.Point(center.x, center.y, center.z), target.getYaw(), true, false,
                    now < targetMovingUntil, settings.radius);
        } else if (activeScene != null) {
            if (!activeScene.world().equals(client.world.getRegistryKey().getValue().toString())) {
                activeScene = null;
                stopMotion();
                resetOrbit(player);
                status = "场景不属于当前世界，已回到本地环绕";
                return;
            }
            subject = new CameraMotion.Subject(new ShowcaseTarget.Scene(activeScene).id(),
                    new CameraMotion.Point(activeScene.x(), activeScene.y(), activeScene.z()), 0, false,
                    activeScene.kind() == Message.SceneKind.AREA, false, activeScene.radius());
        } else {
            subject = new CameraMotion.Subject("local:" + currentWorldKey,
                    new CameraMotion.Point(orbitCenter.x, orbitCenter.y, orbitCenter.z), 0, false, false, false, settings.radius);
        }
        CameraMotion.Shot shot = sceneArrival == null ? cameraMotion.sample(subject,
                new CameraMotion.Point(player.getX(), player.getY(), player.getZ()), settings)
                : new CameraMotion.Shot(sceneArrival.destination(), subject.center());
        Vec3d desired = new Vec3d(shot.position().x(), shot.position().y(), shot.position().z());
        Vec3d lookAt = new Vec3d(shot.lookAt().x(), shot.lookAt().y(), shot.lookAt().z());
        if (!client.world.getWorldBorder().contains(desired)
                || desired.y < client.world.getBottomY() || desired.y > client.world.getTopYInclusive()
                || insideExcludedRegion(desired)) {
            if (requestedTarget != null) {
                send(new Message.Stop());
                endObservation("跟随机位超出世界边界、高度或进入排除区");
            }
            activeScene = null;
            stopMotion();
            resetOrbit(player);
            status = "下一机位超出世界边界、高度或进入排除区，已暂缓移动";
            return;
        }
        Vec3d delta = desired.subtract(player.getEntityPos());
        Vec3d sight = lookAt.subtract(player.getEyePos());
        float yaw = (float) Math.toDegrees(Math.atan2(sight.z, sight.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(sight.y, Math.sqrt(sight.x * sight.x + sight.z * sight.z)));
        if (shotTransition.preparing()) {
            revealShot(player, yaw, pitch, now);
            return;
        }
        Vec3d wantedVelocity = delta.multiply(0.12);
        double maxPerTick = settings.speed / 20;
        if (wantedVelocity.lengthSquared() > maxPerTick * maxPerTick) {
            wantedVelocity = wantedVelocity.normalize().multiply(maxPerTick);
        }
        Vec3d nextVelocity = player.getVelocity().lerp(wantedVelocity, 0.18);
        Vec3d nextPosition = player.getEntityPos().add(nextVelocity);
        if (!client.world.getWorldBorder().contains(nextPosition)
                || nextPosition.y < client.world.getBottomY() || nextPosition.y > client.world.getTopYInclusive()
                || insideExcludedRegion(nextPosition)) {
            if (requestedTarget != null) {
                send(new Message.Stop());
                endObservation("前方机位到达世界边界、高度限制或排除区");
            }
            activeScene = null;
            stopMotion();
            resetOrbit(player);
            status = "前方机位到达世界边界、高度限制或排除区，已暂缓移动";
            return;
        }
        if (!client.world.getChunkManager().isChunkLoaded(MathHelper.floor(nextPosition.x) >> 4,
                MathHelper.floor(nextPosition.z) >> 4)) {
            stopMotion();
            status = "等待前方机位区块加载";
            return;
        }
        player.setVelocity(nextVelocity);
        float turnLimit = (float) (settings.turnRateDegreesPerSecond / 20);
        player.setYaw(player.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(yaw - player.getYaw()), -turnLimit, turnLimit));
        player.setPitch(player.getPitch() + MathHelper.clamp(pitch - player.getPitch(), -turnLimit * 2 / 3, turnLimit * 2 / 3));
    }

    private void revealShot(ClientPlayerEntity player, float yaw, float pitch, long now) {
        player.setVelocity(Vec3d.ZERO);
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.lastYaw = yaw;
        player.lastPitch = pitch;
        shotTransition.reveal(now);
    }

    public boolean blocksManualMovement() {
        return running;
    }

    public boolean isRunning() {
        return running;
    }

    String status() {
        String detail = businessRequestCancelled ? "旧远程请求待排空，仅本地导播；远程恢复可重连；" + status : status;
        detail = worldPolicyCacheFailed ? "世界规则缓存读写失败，见日志；" + detail : detail;
        detail = sceneCacheLimited ? "缓存限64项/30KB，当前世界优先；" + detail : detail;
        return sceneCacheFailed ? "观景缓存读写失败，见日志；" + detail : detail;
    }

    List<ShowcaseTarget> targets() {
        return targets;
    }

    boolean saveSettings() {
        cameraSettingsSaved = settings.save(LOGGER);
        if (!cameraSettingsSaved) {
            status = "参数已应用，但配置保存失败，请检查日志";
            return false;
        }
        return true;
    }

    void openLocalControl() {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            if (localControlServer == null) {
                localControlServer = new LocalControlServer(
                        () -> onClientThread(this::readLocalControlState),
                        command -> onClientThread(() -> executeLocalControlCommand(command)));
            }
            client.setScreen(null);
            Util.getOperatingSystem().open(localControlServer.address());
            status = "本地中控已打开；仅本机可访问";
        } catch (IOException exception) {
            LOGGER.warn("Cannot start local control server", exception);
            status = "本地中控启动失败，请查看日志";
        }
    }

    private CompletableFuture<String> onClientThread(Supplier<String> operation) {
        CompletableFuture<String> result = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(operation.get());
            } catch (RuntimeException exception) {
                // A command can contain a credential. Never include its body or exception detail in logs.
                LOGGER.error("Local control operation failed ({})", exception.getClass().getSimpleName());
                result.completeExceptionally(new IllegalStateException("客户端控制操作失败"));
            }
        });
        return result;
    }

    private String readLocalControlState() {
        synchronizeAiSettings();
        MinecraftClient client = MinecraftClient.getInstance();
        boolean connected = client.getNetworkHandler() != null && client.world != null;
        String worldKey = connected ? client.world.getRegistryKey().getValue().toString() : "";
        boolean remoteAllowed = allowed && !businessRequestCancelled && canOperate(client)
                && worldKey.equals(currentWorldKey) && System.currentTimeMillis() - lastMessage <= 8_000;
        JsonObject state = new JsonObject();
        state.addProperty("product", "BiliCraft 导播");
        state.addProperty("connected", connected);
        state.addProperty("running", running);
        state.addProperty("allowed", remoteAllowed);
        state.addProperty("spectator", client.player != null && client.player.isSpectator());
        state.addProperty("status", status());
        state.addProperty("world", worldKey);
        JsonObject chat = new JsonObject();
        chat.addProperty("connected", connected);
        chat.addProperty("maxLength", LocalChatHistory.MAX_SEND_LENGTH);
        JsonArray chatMessages = new JsonArray();
        for (LocalChatHistory.Entry entry : localChat.messages()) {
            JsonObject message = new JsonObject();
            message.addProperty("id", entry.id());
            message.addProperty("text", entry.text());
            message.addProperty("receivedAt", entry.receivedAt());
            chatMessages.add(message);
        }
        chat.add("messages", chatMessages);
        state.add("chat", chat);
        JsonObject patrol = new JsonObject();
        patrol.addProperty("known", worldPolicy != null);
        patrol.addProperty("automatic", automaticPatrolAllowed());
        patrol.addProperty("pending", worldPolicyPending);
        patrol.addProperty("cacheFailed", worldPolicyCacheFailed);
        patrol.addProperty("source", worldPolicy == null ? "LOCAL_DEFAULT" : "SERVER");
        state.add("patrol", patrol);
        ServerInfo server = client.getCurrentServerEntry();
        state.addProperty("server", connected ? server == null ? "singleplayer" : server.address : "");
        JsonObject account = new JsonObject();
        account.addProperty("name", client.player != null ? client.player.getGameProfile().name()
                : client.getSession().getUsername());
        UUID accountId = client.player != null ? client.player.getGameProfile().id() : client.getSession().getUuidOrNull();
        account.addProperty("uuid", accountId == null ? null : accountId.toString());
        account.addProperty("avatarDataUrl", accountAvatar.dataUrl(client));
        state.add("account", account);
        if (connected && client.player != null) {
            JsonObject camera = new JsonObject();
            camera.addProperty("x", client.player.getX());
            camera.addProperty("y", client.player.getY());
            camera.addProperty("z", client.player.getZ());
            camera.addProperty("yaw", client.player.getYaw());
            camera.addProperty("pitch", client.player.getPitch());
            state.add("camera", camera);
        } else {
            state.add("camera", JsonNull.INSTANCE);
        }
        state.addProperty("motionPaused", running && (client.currentScreen != null || client.isPaused() || shotTransition.preparing()
                || observeState == ObserveState.NOTICE || (sceneArrival != null && sceneArrival.teleport()) || !canOperate(client)
                || (!automaticPatrolAllowed() && (showcaseQueue.current() == null
                || showcaseQueue.current().source() == ShowcaseQueue.Source.AUTO))));
        state.addProperty("sceneArriving", sceneArrival != null);
        state.addProperty("sceneTravelMode", sceneArrival == null ? null : sceneArrival.teleport() ? "TELEPORT" : "FLY");
        JsonObject observation = new JsonObject();
        observation.addProperty("state", remoteAllowed ? observeState.name() : ObserveState.ENDED.name());
        observation.addProperty("targetId", !remoteAllowed || requestedTarget == null ? null : requestedTarget.toString());
        observation.addProperty("deadline", !remoteAllowed || requestedTarget == null ? 0 : observeDeadline);
        state.add("observation", observation);
        JsonObject motion = new JsonObject();
        motion.addProperty("radius", settings.radius);
        motion.addProperty("speed", settings.speed);
        motion.addProperty("mode", settings.mode.name());
        motion.addProperty("heightY", settings.heightY);
        motion.addProperty("playerHeightOffset", settings.playerHeightOffset);
        motion.addProperty("motionAmplitude", settings.motionAmplitude);
        motion.addProperty("turnRateDegreesPerSecond", settings.turnRateDegreesPerSecond);
        state.add("settings", motion);
        JsonObject ai = new JsonObject();
        ai.addProperty("provider", aiSettings.selected.name());
        ai.addProperty("configured", aiSettings.profiles.get(aiSettings.selected).complete());
        ai.addProperty("saved", aiSettings.saved);
        ai.addProperty("loadFailed", aiSettings.loadFailed);
        ai.addProperty("strategyEnabled", aiSettings.enabled);
        ai.addProperty("requestPending", aiSession.pending());
        ai.addProperty("testPending", aiTestPending);
        ai.addProperty("testStatus", aiTestStatus);
        ai.addProperty("lastStatus", aiSession.status);
        ai.addProperty("nextRequestInSeconds", aiSession.nextRequestInSeconds(System.currentTimeMillis()));
        JsonArray modelList = new JsonArray();
        aiModels.forEach(modelList::add);
        ai.add("models", modelList);
        JsonObject usage = new JsonObject();
        usage.addProperty("requests", aiSession.requests);
        usage.addProperty("promptTokens", aiSession.promptTokens);
        usage.addProperty("completionTokens", aiSession.completionTokens);
        usage.addProperty("totalTokens", aiSession.totalTokens);
        usage.addProperty("unreportedRequests", aiSession.unreportedRequests);
        ai.add("usage", usage);
        JsonObject profiles = new JsonObject();
        for (AiSettings.Provider provider : AiSettings.Provider.values()) {
            AiSettings.Profile profile = aiSettings.profiles.get(provider);
            JsonObject fields = new JsonObject();
            fields.addProperty("baseUrl", profile.baseUrl);
            fields.addProperty("model", profile.model);
            fields.addProperty("keyPresent", !profile.apiKey.isBlank());
            profiles.add(provider.name(), fields);
        }
        ai.add("profiles", profiles);
        state.add("ai", ai);
        state.addProperty("selectedTargetId", remoteAllowed && requestedTarget != null ? "player:" + requestedTarget
                : activeScene == null ? null : new ShowcaseTarget.Scene(activeScene).id());
        JsonArray availableTargets = new JsonArray();
        for (ShowcaseTarget target : targets) {
            JsonObject fields = new JsonObject();
            String targetWorld;
            if (target instanceof ShowcaseTarget.Player player) {
                targetWorld = player.view().world();
                if (!remoteAllowed || !worldKey.equals(targetWorld)) {
                    continue;
                }
                fields.addProperty("kind", "PLAYER");
                fields.addProperty("x", player.view().x());
                fields.addProperty("y", player.view().y());
                fields.addProperty("z", player.view().z());
                fields.addProperty("radius", settings.radius);
            } else {
                SceneView scene = ((ShowcaseTarget.Scene) target).view();
                targetWorld = scene.world();
                if (!worldKey.equals(targetWorld)) {
                    continue;
                }
                fields.addProperty("kind", idlePoint != null && idlePoint.matches(scene) ? "IDLE" : scene.kind().name());
                fields.addProperty("x", scene.x());
                fields.addProperty("y", scene.y());
                fields.addProperty("z", scene.z());
                fields.addProperty("radius", scene.radius());
            }
            fields.addProperty("id", target.id());
            fields.addProperty("name", target.name());
            fields.addProperty("label", target.label());
            fields.addProperty("world", targetWorld);
            fields.addProperty("available", targetAvailable(target));
            fields.addProperty("automaticAvailable", automaticTargetAvailable(target));
            availableTargets.add(fields);
        }
        state.add("targets", availableTargets);
        JsonObject queue = new JsonObject();
        queue.add("current", showcaseQueue.current() == null
                || (!remoteAllowed && showcaseQueue.current().target() instanceof ShowcaseTarget.Player) ? JsonNull.INSTANCE
                : queueEntryJson(showcaseQueue.current(), true));
        JsonArray pending = new JsonArray();
        for (ShowcaseQueue.Entry entry : showcaseQueue.pending()) {
            if (remoteAllowed || entry.target() instanceof ShowcaseTarget.Scene) {
                pending.add(queueEntryJson(entry, false));
            }
        }
        queue.add("pending", pending);
        JsonObject queueConfiguration = new JsonObject();
        queueConfiguration.addProperty("autoFill", queueSettings.autoFill);
        queueConfiguration.addProperty("bufferSize", queueSettings.bufferSize);
        queueConfiguration.addProperty("sceneDurationSeconds", queueSettings.sceneDurationSeconds);
        queue.add("settings", queueConfiguration);
        state.addProperty("durationAdjustmentPending", durationRequestTarget != null && !businessRequestCancelled);
        state.addProperty("cameraSettingsSaved", cameraSettingsSaved);
        int pendingCount = showcaseQueue.pending().size();
        String queueStatus = (!running ? "导播已停止；" : "")
                + (worldPolicyPending ? "正在同步巡游范围" : !automaticPatrolAllowed() ? "当前世界仅手动选点"
                : queueSettings.autoFill ? "自动补充已启用" : "自动补充已关闭") + "；待播 "
                + pendingCount + "/" + queueSettings.bufferSize
                + (!automaticPatrolAllowed() ? "，自动巡游与AI补点暂停"
                : pendingCount < queueSettings.bufferSize ? "，合法候选不足时保持基础巡游" : "");
        queue.addProperty("status", queueSettings.loadFailed ? "队列配置读取失败，当前使用默认值；" + queueStatus : queueStatus);
        state.add("queue", queue);
        StrategyPrompt.PreparedDecision prepared = prepareAiDecision(System.currentTimeMillis());
        JsonObject preview = new JsonObject();
        preview.addProperty("version", StrategyPrompt.VERSION);
        preview.addProperty("systemPrompt", prepared.systemPrompt());
        preview.addProperty("userJson", prepared.userJson());
        preview.addProperty("candidateCount", prepared.targetIds().size());
        preview.addProperty("missingSlots", prepared.maxPicks());
        preview.addProperty("needsDecision", prepared.needsDecision());
        state.add("aiDecisionPreview", preview);
        JsonObject capabilities = new JsonObject();
        JsonObject map = new JsonObject();
        boolean configuredMap = mapSource != null && mapSource.configured();
        map.addProperty("supported", canOperate(client));
        map.addProperty("configured", configuredMap);
        map.addProperty("localConfigured", localMapProfile.configured());
        map.addProperty("localBaseUrl", localMapProfile.baseUrl());
        map.addProperty("localDynmapWorld", localMapProfile.dynmapWorld());
        map.addProperty("source", localMapProfile.configured() ? "LOCAL"
                : configuredMap ? "SERVER" : "NONE");
        map.addProperty("loading", mapLoading);
        map.addProperty("sceneCount", mapSceneCount);
        map.addProperty("regionCount", mapRegions.size());
        map.addProperty("fetchedAt", mapFetchedAt);
        map.addProperty("detail", mapDetail);
        map.addProperty("baseUrl", configuredMap ? mapSource.baseUrl() : "");
        map.addProperty("dynmapWorld", configuredMap ? mapSource.dynmapWorld() : "");
        state.add("map", map);
        capabilities.addProperty("mapImport", true);
        capabilities.addProperty("heatmap", false);
        capabilities.addProperty("aiStrategy", true);
        capabilities.addProperty("teleport", true);
        state.add("capabilities", capabilities);
        return state.toString();
    }

    private JsonObject queueEntryJson(ShowcaseQueue.Entry entry, boolean current) {
        JsonObject fields = new JsonObject();
        fields.addProperty("entryId", entry.entryId());
        fields.addProperty("targetId", entry.target().id());
        fields.addProperty("name", entry.target().name());
        if (entry.target() instanceof ShowcaseTarget.Scene scene) {
            fields.addProperty("kind", scene.view().id().equals(IdlePoint.SCENE_ID) ? "IDLE" : scene.view().kind().name());
        } else {
            fields.addProperty("kind", "PLAYER");
        }
        fields.addProperty("source", entry.source().name());
        fields.addProperty("durationSeconds", entry.durationSeconds());
        int remaining = current ? showcaseQueue.remainingSeconds() : entry.durationSeconds();
        if (current && entry.target() instanceof ShowcaseTarget.Player && observeState == ObserveState.FOLLOWING) {
            remaining = (int) Math.max(0, (observeDeadline - System.currentTimeMillis() + 999) / 1_000);
        }
        fields.addProperty("remainingSeconds", remaining);
        return fields;
    }

    private String executeLocalControlCommand(String body) {
        MinecraftClient client = MinecraftClient.getInstance();
        synchronizeWorld(client);
        if (allowed && System.currentTimeMillis() - lastMessage > 8_000) {
            revokeServerAccess("导播服务响应超时");
        }
        boolean accepted;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Expected command object");
            }
            JsonObject command = parsed.getAsJsonObject();
            String action = commandString(command, "action", 32);
            Set<String> fields = switch (action) {
                case "start", "stop", "previous", "next", "refresh", "queueSkip", "mapRefresh" -> Set.of("action");
                case "mapSettings" -> Set.of("action", "server", "world", "baseUrl", "dynmapWorld");
                case "chatSend" -> Set.of("action", "message");
                case "show" -> Set.of("action", "id");
                case "queueAdd" -> Set.of("action", "targetId", "index", "durationSeconds");
                case "queueRemove" -> Set.of("action", "entryId");
                case "queueMove" -> Set.of("action", "entryId", "index", "delta");
                case "queueAdjust" -> Set.of("action", "entryId", "deltaSeconds");
                case "queueSettings" -> Set.of("action", "autoFill", "bufferSize", "sceneDurationSeconds");
                case "camera" -> Set.of("action", "radius", "speed", "mode", "heightY", "playerHeightOffset", "motionAmplitude",
                        "turnRateDegreesPerSecond", "persist");
                case "ai" -> Set.of("action", "provider", "baseUrl", "model", "apiKey", "clearKey", "enabled");
                case "aiTest" -> Set.of("action");
                default -> throw new IllegalArgumentException("Unknown action");
            };
            if (!fields.containsAll(command.keySet())) {
                throw new IllegalArgumentException("Unknown command field");
            }
            accepted = switch (action) {
                case "chatSend" -> {
                    if (client.getNetworkHandler() == null || client.world == null || client.player == null) {
                        status = "聊天发送失败：尚未连接游戏";
                        yield false;
                    }
                    String message = commandString(command, "message", LocalChatHistory.MAX_SEND_LENGTH);
                    if (message.codePoints().anyMatch(character -> !StringHelper.isValidChar(character))
                            || !StandardCharsets.UTF_8.newEncoder().canEncode(message)) {
                        status = "聊天或命令含无效字符，请使用单行文本";
                        yield false;
                    }
                    message = message.strip();
                    if (message.isEmpty() || message.equals("/")) {
                        status = "请输入聊天消息或完整命令";
                        yield false;
                    }
                    if (!client.getChatRestriction().allowsChat(client.isInSingleplayer())) {
                        status = "当前客户端不允许聊天：" + client.getChatRestriction().getDescription().getString();
                        yield false;
                    }
                    if (message.startsWith("/")) client.getNetworkHandler().sendChatCommand(message.substring(1));
                    else client.getNetworkHandler().sendChatMessage(message);
                    status = "已发送；执行结果以服务器聊天回执为准";
                    yield true;
                }
                case "start" -> {
                    if (client.currentScreen instanceof DirectorScreen || client.currentScreen instanceof GameMenuScreen) {
                        client.setScreen(null);
                    }
                    yield start();
                }
                case "stop" -> {
                    stop("已从本地中控停止");
                    yield true;
                }
                case "previous" -> {
                    if (!running || client.currentScreen != null) {
                        status = "请先启动导播并关闭游戏菜单";
                        yield false;
                    }
                    yield switchTarget(-1);
                }
                case "next", "queueSkip" -> skipQueue();
                case "refresh" -> requestSnapshot(0);
                case "mapRefresh" -> {
                    boolean sent = requestMapSource(true);
                    status = mapDetail;
                    yield sent;
                }
                case "mapSettings" -> {
                    String serverAddress = lastServer == null ? "singleplayer" : lastServer.address;
                    if (!canOperate(client) || !commandString(command, "server", 1_024).equals(serverAddress)
                            || !commandString(command, "world", 256).equals(currentWorldKey)) {
                        status = "服务器或世界已切换，请刷新当前世界后重新保存地图配置";
                        yield false;
                    }
                    LocalMapSettings.Profile saved = localMapSettings.save(serverAddress, currentWorldKey,
                            commandString(command, "baseUrl", 1_024), commandString(command, "dynmapWorld", 256));
                    mapGeneration++;
                    mapMetadataPending = false;
                    mapRefreshRequested = false;
                    mapExternalPending = false;
                    mapLoading = false;
                    mapLoaded = false;
                    importedScenes = List.of();
                    mapRegions = List.of();
                    mapBootstrapPending = false;
                    mapSceneCount = 0;
                    mapFetchedAt = 0;
                    localMapProfile = saved;
                    mapSource = saved.configured()
                            ? new Message.MapSource(0, currentWorldKey, true, saved.baseUrl(), saved.dynmapWorld(),
                            serverMapSource == null ? List.of() : serverMapSource.exclusions(), "本机地图配置已保存；点击拉取后才连接外链")
                            : serverMapSource;
                    cachedScenes = cachedScenes.stream().filter(scene -> !scene.world().equals(currentWorldKey)
                            || !scene.id().startsWith("dynmap:")).toList();
                    refreshMapTargets(client);
                    invalidateAiDecision();
                    mapDetail = saved.configured() ? mapSource.detail() : "当前服务器与世界的本机地图配置已清除";
                    status = mapDetail;
                    yield true;
                }
                case "show" -> {
                    String id = commandString(command, "id", 400);
                    ShowcaseTarget target = targets.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
                    if (target == null) {
                        status = "目标不在当前候选列表中，请刷新";
                        yield false;
                    }
                    yield showTarget(target);
                }
                case "queueAdd" -> {
                    String id = commandString(command, "targetId", 400);
                    ShowcaseTarget target = targets.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
                    if (target == null || !targetAvailable(target)) {
                        status = "目标当前不可加入队列，请刷新当前世界的合法素材";
                        yield false;
                    }
                    int index = command.has("index") ? commandInteger(command, "index") : showcaseQueue.pending().size();
                    int duration = command.has("durationSeconds") ? commandInteger(command, "durationSeconds")
                            : showcaseQueue.defaultDuration(target, queueSettings.sceneDurationSeconds);
                    showcaseQueue.add(target, index, duration);
                    status = "已加入待播队列";
                    yield true;
                }
                case "queueRemove" -> {
                    showcaseQueue.remove(commandString(command, "entryId", 64));
                    status = "已移除待播项";
                    yield true;
                }
                case "queueMove" -> {
                    if (command.has("index") == command.has("delta")) {
                        throw new IllegalArgumentException("Exactly one queue movement mode is required");
                    }
                    String entryId = commandString(command, "entryId", 64);
                    if (command.has("delta")) {
                        showcaseQueue.moveBy(entryId, commandInteger(command, "delta"));
                    } else {
                        showcaseQueue.move(entryId, commandInteger(command, "index"));
                    }
                    status = "已调整待播顺序";
                    yield true;
                }
                case "queueAdjust" -> {
                    String entryId = commandString(command, "entryId", 64);
                    int deltaSeconds = commandInteger(command, "deltaSeconds");
                    ShowcaseQueue.Entry current = showcaseQueue.current();
                    if (current != null && current.entryId().equals(entryId) && current.target() instanceof ShowcaseTarget.Player player) {
                        if (deltaSeconds != -10 && deltaSeconds != 10) {
                            status = "时长每次只能增加或减少10秒";
                            yield false;
                        }
                        int requestedDuration = current.durationSeconds() + deltaSeconds;
                        if (requestedDuration < Protocol.MIN_FOLLOW_SECONDS || requestedDuration > Protocol.MAX_FOLLOW_SECONDS) {
                            status = "已达玩家时长边界（180～480秒），未调整";
                            yield false;
                        }
                        if (!running || !canOperate(client) || !allowed || businessRequestCancelled
                                || requestedTarget == null || !requestedTarget.equals(player.view().id())
                                || observeState == ObserveState.ENDED || hasBusinessRequest()) {
                            status = "当前玩家授权无效或已有导播请求，请稍后重试";
                            yield false;
                        }
                        if (!send(new Message.DurationRequest(requestedTarget, requestedDuration))) {
                            status = "时长调整发送失败，当前跟随时长未改变";
                            yield false;
                        }
                        durationRequestTarget = requestedTarget;
                        durationRequestEntryId = entryId;
                        businessRequestDeadline = System.currentTimeMillis() + 8_000;
                        status = "已提交玩家时长调整，等待导播服务核准";
                        yield true;
                    }
                    if (showcaseQueue.current() != null && showcaseQueue.current().entryId().equals(entryId)
                            && activeScene == null && showcaseQueue.current().target() instanceof ShowcaseTarget.Scene) {
                        status = "当前场景已经结束，无法再调整时长";
                        yield false;
                    }
                    try {
                        showcaseQueue.adjust(entryId, deltaSeconds, System.currentTimeMillis(),
                                !running || client.currentScreen != null || client.isPaused() || sceneArrival != null);
                    } catch (IllegalArgumentException exception) {
                        // These domain errors contain only fixed queue messages, never command contents or credentials.
                        status = exception.getMessage();
                        yield false;
                    }
                    status = deltaSeconds > 0 ? "该场景已增加10秒" : "该场景已减少10秒，归零后会按队列继续";
                    yield true;
                }
                case "queueSettings" -> {
                    JsonElement fill = command.get("autoFill");
                    if (fill == null || !fill.isJsonPrimitive() || !fill.getAsJsonPrimitive().isBoolean()) {
                        throw new IllegalArgumentException("Invalid autoFill");
                    }
                    queueSettings.save(fill.getAsBoolean(), commandInteger(command, "bufferSize"),
                            commandInteger(command, "sceneDurationSeconds"));
                    status = "队列配置已保存；场景时长单位为秒";
                    yield true;
                }
                case "camera" -> {
                    boolean persist = true;
                    if (command.has("persist")) {
                        JsonElement value = command.get("persist");
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                            throw new IllegalArgumentException("Invalid persist");
                        }
                        persist = value.getAsBoolean();
                    }
                    CameraMotion.Mode mode = CameraMotion.Mode.valueOf(commandString(command, "mode", 16));
                    double radius = commandNumber(command, "radius");
                    double speed = commandNumber(command, "speed");
                    double heightY = commandNumber(command, "heightY");
                    double playerHeightOffset = commandNumber(command, "playerHeightOffset");
                    double amplitude = commandNumber(command, "motionAmplitude");
                    double turnRate = commandNumber(command, "turnRateDegreesPerSecond");
                    CameraSettings.validate(mode, radius, speed, heightY, playerHeightOffset, amplitude, turnRate);
                    settings.mode = mode;
                    settings.radius = radius;
                    settings.speed = speed;
                    settings.heightY = heightY;
                    settings.playerHeightOffset = playerHeightOffset;
                    settings.motionAmplitude = amplitude;
                    settings.turnRateDegreesPerSecond = turnRate;
                    if (sceneArrival != null && !sceneArrival.teleport() && activeScene != null) {
                        sceneArrival = SceneArrival.planFlight(
                                new CameraMotion.Point(client.player.getX(), client.player.getY(), client.player.getZ()),
                                new CameraMotion.Point(activeScene.x(), activeScene.y(), activeScene.z()),
                                radius, heightY, speed, System.currentTimeMillis());
                    }
                    cameraSettingsSaved = false;
                    if (!persist) {
                        status = "实时运镜已应用，尚未保存";
                        yield true;
                    }
                    boolean saved = saveSettings();
                    if (saved) {
                        status = "运镜参数已保存";
                    }
                    yield saved;
                }
                case "aiTest" -> testAiConnection();
                case "ai" -> {
                    AiSettings.Provider provider = AiSettings.Provider.valueOf(commandString(command, "provider", 16));
                    String address = commandString(command, "baseUrl", 1024);
                    String model = commandString(command, "model", 256);
                    String key = command.has("apiKey") ? commandString(command, "apiKey", 4096) : "";
                    boolean clearKey = false;
                    if (command.has("clearKey")) {
                        JsonElement clear = command.get("clearKey");
                        if (!clear.isJsonPrimitive() || !clear.getAsJsonPrimitive().isBoolean()) {
                            throw new IllegalArgumentException("Invalid clearKey");
                        }
                        clearKey = clear.getAsBoolean();
                    }
                    if (clearKey && !key.isBlank()) {
                        throw new IllegalArgumentException("Conflicting key options");
                    }
                    Map<AiSettings.Provider, AiSettings.Profile> drafts = new EnumMap<>(aiSettings.profiles);
                    String savedKey = clearKey ? "" : key.isBlank() ? aiSettings.profiles.get(provider).apiKey : key;
                    if (!savedKey.isEmpty() && !savedKey.chars().allMatch(value -> value >= 33 && value <= 126)) {
                        status = "API Key 含空白或非 ASCII 字符，请只粘贴密钥本身";
                        yield false;
                    }
                    drafts.put(provider, new AiSettings.Profile(address, model, savedKey));
                    boolean enabled = aiSettings.enabled;
                    if (command.has("enabled")) {
                        JsonElement value = command.get("enabled");
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                            throw new IllegalArgumentException("Invalid AI enabled value");
                        }
                        enabled = value.getAsBoolean();
                    }
                    aiSettings.save(provider, drafts, enabled);
                    synchronizeAiSettings();
                    status = enabled ? "AI配置已保存并启用；策略请求至少间隔60秒，会产生API用量"
                            : "AI配置已保存，策略已关闭；未验证Chat能力";
                    yield true;
                }
                default -> throw new IllegalArgumentException("Unknown action");
            };
            if (accepted && Set.of("queueAdd", "queueRemove", "queueMove", "queueAdjust", "queueSettings").contains(action)) {
                invalidateAiDecision();
            }
        } catch (JsonParseException | IllegalArgumentException exception) {
            accepted = false;
            status = "控制参数格式或范围无效，请核对字段";
        } catch (IOException exception) {
            accepted = false;
            status = "本机配置保存失败，请检查配置目录";
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", accepted);
        response.addProperty("message", status());
        return response.toString();
    }

    private static String commandString(JsonObject command, String name, int maximumLength) {
        JsonElement value = command.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid string field");
        }
        String text = value.getAsString();
        if (text.length() > maximumLength) {
            throw new IllegalArgumentException("String field exceeds limit");
        }
        return text;
    }

    private static double commandNumber(JsonObject command, String name) {
        JsonElement value = command.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid numeric field");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("Numeric field must be finite");
        }
        return number;
    }

    private static int commandInteger(JsonObject command, String name) {
        double value = commandNumber(command, name);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer field is invalid");
        }
        return (int) value;
    }

    private void stopMotion() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.setVelocity(Vec3d.ZERO);
        }
    }

    private boolean send(Message message) {
        if (MinecraftClient.getInstance().getNetworkHandler() == null || !ClientPlayNetworking.canSend(DirectorPayload.ID)) {
            return false;
        }
        try {
            ClientPlayNetworking.send(new DirectorPayload(Codec.encode(message)));
            return true;
        } catch (IOException exception) {
            LOGGER.error("Cannot encode director protocol message", exception);
            status = "导播通信编码失败";
            return false;
        }
    }
}
