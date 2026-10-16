"use strict";

const element = (id) => document.getElementById(id);
const kindNames = { PLAYER: "玩家", VIEWPOINT: "观景点", AREA: "巡游区域", IDLE: "挂机点" };
const playerAvailabilityNames = { AVAILABLE: "可预告观察", OTHER_WORLD: "其他世界", IDLE: "暂无动作", SPECTATOR: "观察者", UNAVAILABLE: "暂不可观察" };
const providerNames = { DEEPSEEK: "DeepSeek", OPENAI: "OpenAI" };
const cameraModeNames = { AUTO: "自动", FOLLOW: "跟拍", ORBIT: "环绕", DOLLY: "推进 / 拉远", TRUCK: "横移", CRANE: "升降", PAN: "摇镜", STATIC: "固定机位" };
const cameraModeHelp = {
    AUTO: "移动玩家使用后侧跟拍，静止玩家与观景点使用环绕，区域使用八字巡游。",
    FOLLOW: "保持玩家后侧机位；展示建筑时保持入镜方向，持续朝向目标。",
    ORBIT: "围绕目标缓慢旋转，距离和高度保持稳定。",
    DOLLY: "沿目标方向缓慢推进并拉回；运动幅度控制距离变化。",
    TRUCK: "保持基线距离横向往返，运动幅度控制侧向移动范围。",
    CRANE: "保持水平构图缓慢升降；运动幅度控制高度变化。",
    PAN: "固定机位，缓慢左右扫视；运动幅度控制扫视范围。",
    STATIC: "保持固定机位，持续朝向目标。玩家走动时会转头追踪。"
};
const cameraNumberSettings = ["radius", "speed", "heightY", "playerHeightOffset", "motionAmplitude", "turnRateDegreesPerSecond"];
const liveCameraIds = { radius: "Radius", speed: "Speed", heightY: "HeightY", playerHeightOffset: "PlayerHeightOffset", motionAmplitude: "MotionAmplitude", turnRateDegreesPerSecond: "TurnRateDegreesPerSecond" };
const commandNames = {
    start: "启动导播", stop: "停止导播", previous: "上一素材插播", queueSkip: "跳过当前",
    refresh: "拉取目标", mapRefresh: "拉取地图", mapSettings: "保存本机地图来源", show: "立即插播", camera: "调整运镜", ai: "保存 AI 配置", aiTest: "测试 AI 连接",
    queueAdd: "加入待播队列", queueRemove: "移除待播项", queueMove: "调整待播顺序", queueSettings: "保存队列配置",
    queueAdjust: "调整排播时长", chatSend: "发送聊天"
};
const tokenStorageKey = "bilicraft-control-token";
let sessionToken = location.hash.slice(1);
try {
    if (sessionToken) sessionStorage.setItem(tokenStorageKey, sessionToken);
    else sessionToken = sessionStorage.getItem(tokenStorageKey) || "";
} catch {
    // The current page can still pair when browser storage is unavailable.
}
if (location.hash) history.replaceState(null, "", location.pathname);

let state = null;
let apiOnline = false;
let pollPromise = null;
let pendingCommands = 0;
let stopPending = false;
let selectedId = null;
let selectedTarget = null;
let activeKind = "ALL";
let targetListSignature = "";
let cameraDirty = false;
let liveCameraDirty = false;
let liveCameraSending = false;
let liveCameraRevision = 0;
let liveCameraTimer;
let liveCameraPersist = false;
let liveCameraRequest = null;
let cameraSavePending = false;
let queueSettingsDirty = false;
let mapDraft = null;
let entryDurationDirty = false;
let currentProvider = "DEEPSEEK";
let aiInitialized = false;
let aiEnabledDraft = false;
let aiEnabledDirty = false;
const aiDrafts = new Map();
let feedbackTimer;
const logs = [];

const canvas = element("mapCanvas");
const context = canvas.getContext("2d");
const mapView = { x: 0, z: 0, scale: 2.5, followCamera: true, initialized: false };
let mapWidth = 0;
let mapHeight = 0;
let mapFrame = 0;
let drag = null;
let queueSignature = "";
let avatarSource = "";
let consoleView = null;
let chatSending = false;
let chatSignature = "";
let previousChatIds = new Set();
let chatUnread = 0;

function addLog(message, level = "info") {
    const entry = { message, level, time: new Date().toLocaleTimeString("zh-CN", { hour12: false }) };
    logs.unshift(entry);
    if (logs.length > 100) logs.pop();
    const row = document.createElement("li");
    if (level !== "info") row.className = level;
    const time = document.createElement("span");
    time.className = "log-time";
    time.textContent = entry.time;
    const label = document.createElement("span");
    label.className = "log-level";
    label.textContent = level === "error" ? "失败" : level === "pending" ? "提交" : "记录";
    const text = document.createElement("span");
    text.className = "log-message";
    text.textContent = message;
    row.append(time, label, text);
    element("commandLog").prepend(row);
    while (element("commandLog").children.length > 100) element("commandLog").lastElementChild.remove();
    element("logCount").textContent = String(logs.length);
    element("logLatest").textContent = message;
}

function showFeedback(message, error = false) {
    clearTimeout(feedbackTimer);
    element("feedback").textContent = message;
    element("feedback").classList.toggle("error", error);
    element("feedback").hidden = false;
    feedbackTimer = setTimeout(() => { element("feedback").hidden = true; }, error ? 7000 : 4000);
}

function canQueueTarget(target) {
    return Boolean(apiOnline && state?.connected && state.spectator && pendingCommands === 0 && target?.available
        && (target.kind !== "PLAYER" || state.allowed) && state.queue.pending.length < 64);
}

function updateAvailability() {
    const available = apiOnline && state !== null;
    const canMove = available && state.connected && state.spectator;
    const busy = pendingCommands > 0;
    document.querySelectorAll("[data-command]").forEach((button) => {
        const action = button.dataset.command;
        if (action === "stop") button.disabled = !available || stopPending;
        else if (action === "start") button.disabled = !canMove || state.running || busy;
        else if (action === "refresh") button.disabled = !canMove || busy;
        else if (action === "mapRefresh") button.disabled = !canMove || !state.map.supported || state.map.loading || busy
            || Boolean(mapDraft?.dirty) || (!state.map.configured && !state.allowed);
        else if (action === "queueSkip") button.disabled = !canMove || !state.running || !state.queue.current || busy;
        else button.disabled = !canMove || !state.running || busy || state.targets.length === 0;
    });
    element("showSelected").disabled = !canMove || !state.running || busy || !selectedTarget
        || !selectedTarget.available || (selectedTarget.kind === "PLAYER" && !state.allowed);
    const canAdd = canQueueTarget(selectedTarget);
    for (const id of ["queueAppend", "queueNext", "queueInsert"]) element(id).disabled = !canAdd;
    document.querySelectorAll("[data-library-add]").forEach((button) => {
        button.disabled = !canQueueTarget(state?.targets.find((target) => target.id === button.dataset.targetId));
    });
    element("entryDuration").disabled = !available || !selectedTarget;
    document.querySelectorAll("[data-queue-action]").forEach((button) => {
        const index = state?.queue.pending.findIndex((entry) => entry.entryId === button.dataset.entryId);
        button.disabled = !available || busy || index < 0
            || (button.dataset.queueAction === "up" && index === 0)
            || (button.dataset.queueAction === "down" && index === state.queue.pending.length - 1);
    });
    document.querySelectorAll("[data-duration-card]").forEach((button) => {
        const entry = !state ? null : state.queue.current?.entryId === button.dataset.entryId ? state.queue.current
            : state.queue.pending.find((item) => item.entryId === button.dataset.entryId);
        const duration = entry ? entry.durationSeconds + Number(button.dataset.deltaSeconds) : 0;
        const minimum = entry?.kind === "PLAYER" ? 180 : 30;
        const maximum = entry?.kind === "PLAYER" ? 480 : 600;
        button.disabled = !available || busy || state?.durationAdjustmentPending || !entry || duration < minimum || duration > maximum;
    });
    element("saveCamera").disabled = !available || busy || cameraSavePending || !cameraDirty;
    const idlePlaying = state?.running && state.queue.current?.kind === "IDLE";
    element("liveCameraMode").disabled = !available || cameraSavePending || idlePlaying;
    for (const setting of cameraNumberSettings) element("live" + liveCameraIds[setting]).disabled = !available || cameraSavePending || idlePlaying;
    const amplitudeUsed = ["AUTO", "DOLLY", "TRUCK", "CRANE", "PAN"].includes(element("liveCameraMode").value);
    element("liveMotionAmplitude").disabled ||= !amplitudeUsed;
    element("liveMotionAmplitude").title = amplitudeUsed ? "运动范围受镜头距离和世界边界约束" : "当前模式不使用运动幅度；已保留给其它模式";
    element("saveLiveCamera").disabled = !available || liveCameraSending || cameraSavePending || (state?.cameraSettingsSaved && !liveCameraDirty);
    element("saveQueueSettings").disabled = !available || busy || !queueSettingsDirty;
    const canConfigureMap = Boolean(available && state.connected && state.world && state.server);
    for (const id of ["mapLocalBaseUrl", "mapLocalWorld", "resetMapSettings", "clearMapSettings"]) {
        element(id).disabled = !canConfigureMap || busy;
    }
    element("saveMapSettings").disabled = !canConfigureMap || busy || !mapDraft?.dirty || state.map.loading;
    const aiDraft = aiDrafts.get(currentProvider);
    element("saveAi").disabled = !available || busy || !aiDraft
        || (!aiDraft.dirty && !aiEnabledDirty && currentProvider === state.ai.provider);
    const testingSavedProvider = available && currentProvider === state.ai.provider;
    element("testAi").disabled = !testingSavedProvider || busy || state.ai.testPending || state.ai.requestPending
        || aiDraft.dirty || aiEnabledDirty || !state.ai.profiles[currentProvider].keyPresent || !aiDraft.baseUrl.trim();
    element("testAi").textContent = state?.ai.testPending ? "正在读取模型列表…" : "测试已保存的连接";
    element("testAi").title = !testingSavedProvider || aiDraft?.dirty || aiEnabledDirty
        ? "请先保存当前提供方与草稿；测试只使用客户端已保存的配置" : "只请求 /models，不发送聊天或启用策略";
    element("aiModelList").disabled = !testingSavedProvider || !state.ai.models?.length || state.ai.testPending
        || aiDraft.baseUrl !== state.ai.profiles[currentProvider].baseUrl || aiDraft.clearKey || Boolean(aiDraft.apiKey);
    element("aiEnabled").disabled = !state;
    element("resetCamera").disabled = !state;
    element("resetQueueSettings").disabled = !state;
    element("resetAi").disabled = !state;
    const canChat = Boolean(available && state.chat.connected);
    element("chatInput").disabled = !canChat;
    if (state) element("chatInput").maxLength = state.chat.maxLength;
    element("chatSend").disabled = !canChat || busy || chatSending || !element("chatInput").value.trim();
    element("chatSend").textContent = chatSending ? "发送中…" : "发送";
    element("chatLength").textContent = element("chatInput").value.length + " / " + element("chatInput").maxLength;
    element("chatStatus").textContent = !apiOnline ? "本机中控离线 · 草稿已保留"
        : canChat ? "已连接游戏 · 使用当前登录账号发送" : "游戏未连接 · 连接后可发送";
}

function setConnection(online, message) {
    if (apiOnline !== online) addLog(online ? "已连接本机导播中控" : message, online ? "info" : "error");
    apiOnline = online;
    element("connectionBadge").classList.toggle("online", online);
    element("connectionBadge").classList.toggle("offline", !online);
    element("connectionText").textContent = online ? "本机已连接" : "本机离线";
    if (!online) {
        element("serverText").textContent = message;
        element("serverText").title = message;
        element("lastUpdate").textContent = "同步中断";
        element("settingsConnection").textContent = "本机离线 · 未确认新状态";
    }
    updateAvailability();
}

async function requestApi(path, command) {
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), 6000);
    try {
        const headers = { "X-BiliCraft-Token": sessionToken };
        const options = { headers, signal: abort.signal, cache: "no-store", credentials: "omit" };
        if (command) {
            headers["Content-Type"] = "application/json";
            options.method = "POST";
            options.body = JSON.stringify(command);
        }
        const response = await fetch(path, options);
        if (response.status === 401 || response.status === 403) {
            sessionToken = "";
            try { sessionStorage.removeItem(tokenStorageKey); } catch { /* See the storage boundary above. */ }
            throw new Error("配对已失效，请从 Mod 菜单重新打开本地中控");
        }
        if (!response.ok) throw new Error("本机接口响应异常（HTTP " + response.status + "）");
        return await response.json();
    } finally {
        clearTimeout(timeout);
    }
}

function refreshState() {
    if (pollPromise) return pollPromise;
    if (!sessionToken) {
        setConnection(false, "请从 Mod 菜单打开中控以完成配对");
        return Promise.resolve(false);
    }
    pollPromise = (async () => {
        try {
            const next = await requestApi("/api/state");
            if (!next || !Array.isArray(next.targets) || !next.settings || !next.ai || !next.ai.profiles
                || !next.queue || !Array.isArray(next.queue.pending) || !next.queue.settings
                || !next.chat || !Array.isArray(next.chat.messages)) {
                throw new Error("本机状态格式不匹配，请重新打开中控");
            }
            applyState(next);
            setConnection(true, "");
            return true;
        } catch (error) {
            setConnection(false, error.name === "AbortError" ? "本机接口响应超时" : error.message);
            return false;
        } finally {
            pollPromise = null;
        }
    })();
    return pollPromise;
}

async function sendCommand(command, quiet = false) {
    if (!apiOnline || !state) return { ok: false, synchronized: false };
    pendingCommands++;
    if (command.action === "stop") stopPending = true;
    updateAvailability();
    const label = commandNames[command.action];
    if (!quiet) addLog(label + "：正在提交", "pending");
    try {
        const result = await requestApi("/api/command", command);
        if (typeof result.ok !== "boolean" || typeof result.message !== "string") {
            throw new Error("本机命令响应格式不匹配");
        }
        if (!result.ok) {
            addLog(label + "：" + result.message, "error");
            showFeedback(result.message, true);
            return { ok: false, synchronized: false };
        }
        if (!quiet) {
            addLog(label + "：" + result.message);
            showFeedback(result.message);
        }
        // An older poll may have started before the command. Wait for it, then read the applied state.
        if (pollPromise) await pollPromise;
        const synchronized = await refreshState();
        if (!synchronized) {
            addLog(label + "：客户端已接受操作，但新状态尚未确认", "error");
            showFeedback("客户端已接受操作，但新状态尚未确认；请等待重新连接", true);
        }
        return { ok: true, synchronized };
    } catch (error) {
        const message = error.name === "AbortError" ? "本机接口响应超时，执行结果未知，请等待状态同步" : error.message;
        addLog(label + "：" + message, "error");
        showFeedback(message, true);
        setConnection(false, message);
        return { ok: false, synchronized: false };
    } finally {
        pendingCommands--;
        if (command.action === "stop") stopPending = false;
        updateAvailability();
    }
}

function applyState(next) {
    const changedWorld = state && (state.world !== next.world || state.server !== next.server);
    const previousSelection = state?.selectedTargetId;
    state = next;
    if (changedWorld) {
        selectedId = null;
        selectedTarget = null;
        mapView.initialized = false;
        mapView.followCamera = true;
        if (mapDraft?.dirty) addLog("服务器或世界已切换，旧世界的地图草稿已清理");
        mapDraft = null;
    }
    if (!selectedId && next.selectedTargetId) selectedId = next.selectedTargetId;
    if (next.selectedTargetId && next.selectedTargetId !== previousSelection) {
        addLog("当前展示目标：" + (next.targets.find((target) => target.id === next.selectedTargetId)?.name || "目标已更新"));
    }
    const currentTarget = next.targets.find((target) => target.id === selectedId);
    if (currentTarget) selectedTarget = currentTarget;
    else if (selectedTarget) selectedTarget = { ...selectedTarget, available: false };
    element("serverText").textContent = next.connected ? next.server : "游戏未连接 · 可停止恢复意图";
    element("serverText").title = next.connected ? next.server + (next.allowed ? " · 导播服务已授权" : " · 本地模式，玩家跟随未授权")
        : "本地中控在线，Minecraft 服务器尚未连接";
    element("lastUpdate").textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
    element("settingsConnection").textContent = (next.connected ? next.allowed ? "导播服务已授权" : "本地模式" : "游戏未连接")
        + " · 已同步 " + element("lastUpdate").textContent;
    renderAccount();
    renderChat();
    element("mapWorldLabel").textContent = next.world || "未进入世界";
    const terrainBlocked = next.terrain?.recoveryBlocked;
    const manualOnly = !next.patrol.pending && !next.patrol.automatic;
    const runWarning = next.motionPaused || terrainBlocked || manualOnly;
    element("runState").textContent = !next.running ? "已停止" : terrainBlocked ? "地形保护" : manualOnly ? "仅手动"
        : next.sceneArriving ? "前往场景" : next.motionPaused ? "移动暂停" : "运行中";
    element("runState").classList.toggle("online", next.running && !runWarning);
    element("runState").classList.toggle("warning", next.running && runWarning);
    element("runtimeStatus").textContent = next.status;
    element("motionState").textContent = !next.running ? "未运行" : next.sceneArriving ? "等待传送到位和区块加载"
        : next.motionPaused ? "已暂停，请查看执行状态" : "本地运镜";
    const observation = next.observation;
    element("observationState").textContent = observation.state === "NOTICE" ? "私密预告中"
        : observation.state === "APPROACHING" ? "授权入镜准备中"
        : observation.state === "FOLLOWING" ? "已获准跟随" : "未进行";
    updateCountdown();
    element("teleportCapability").textContent = next.capabilities.teleport ? "同世界 OP 传送" : "当前客户端未接入";
    element("teleportRulesStatus").textContent = next.capabilities.teleport
        ? "由本人发起同世界场景传送；到位并加载后开始运镜，失败或超时跳过"
        : "当前客户端尚未接入场景传送";
    element("targetSource").textContent = !next.connected ? "保留最后同步视图"
        : next.patrol.pending ? "正在同步巡游范围 · 手动选点可用"
            : next.patrol.cacheFailed ? "巡游规则读取失败 · 自动巡游、本地及AI补点暂停"
            : !next.patrol.automatic ? "当前世界未获巡游许可 · /director worlds list"
            : next.allowed ? "当前世界 · 服务端数据已连接" : "本地模式 · 景点、巡游与队列可用 · 玩家跟随未授权";
    element("cameraActual").textContent = "当前：" + cameraModeNames[next.settings.mode] + " · 距离 " + next.settings.radius
        + " 格 · 速度 " + next.settings.speed + " 格 / 秒 · 场景 Y " + next.settings.heightY
        + " · 跟拍高度 +" + next.settings.playerHeightOffset
        + " 格 · 幅度 " + next.settings.motionAmplitude + " 格 · 转向 " + next.settings.turnRateDegreesPerSecond + " 度 / 秒";
    if (!cameraDirty && !element("cameraForm").contains(document.activeElement)) loadCameraValues();
    if (!liveCameraDirty && !liveCameraSending) {
        element("liveCameraMode").value = next.settings.mode;
        for (const setting of cameraNumberSettings) element("live" + liveCameraIds[setting]).value = next.settings[setting];
        renderLiveCameraValues();
        element("liveCameraStatus").textContent = next.running && next.queue.current?.kind === "IDLE" ? "挂机点 · 位置与注视方向锁定"
            : next.cameraSettingsSaved ? "已应用 · 已保存" : "已实时应用 · 未保存";
    }
    if (!queueSettingsDirty && !element("queueSettingsForm").contains(document.activeElement)) loadQueueSettings();
    if (!entryDurationDirty && document.activeElement !== element("entryDuration")) {
        element("entryDuration").value = selectedTarget?.kind === "PLAYER" ? "" : next.queue.settings.sceneDurationSeconds;
    }
    element("queueSettingsActual").textContent = "当前：自动补足" + (next.queue.settings.autoFill ? "开启" : "关闭")
        + " · 缓存 " + next.queue.settings.bufferSize + " 项 · 场景默认 " + next.queue.settings.sceneDurationSeconds + " 秒"
        + (next.patrol.pending ? " · 正在同步巡游范围" : next.patrol.cacheFailed ? " · 巡游规则读取失败，自动补点暂停"
            : next.patrol.automatic ? "" : " · 当前世界未获巡游许可 · /director worlds list");
    renderQueue();
    if (!aiInitialized) {
        currentProvider = next.ai.provider;
        aiInitialized = true;
    }
    for (const provider of Object.keys(providerNames)) {
        if (!aiDrafts.get(provider)?.dirty) {
            const profile = next.ai.profiles[provider];
            aiDrafts.set(provider, { baseUrl: profile.baseUrl, model: profile.model, apiKey: "", clearKey: false, dirty: false });
        }
    }
    if (!aiEnabledDirty) aiEnabledDraft = next.ai.strategyEnabled;
    // Do not rewrite focused fields or replace unsaved per-provider drafts on a poll.
    if (!element("aiForm").contains(document.activeElement)) renderAiForm();
    renderAiSummary();
    if (!mapDraft?.dirty) {
        mapDraft = { server: next.server, world: next.world, baseUrl: next.map.localBaseUrl,
            dynmapWorld: next.map.localDynmapWorld, dirty: false };
    }
    if (changedWorld || !element("mapSettingsForm").contains(document.activeElement)) {
        element("mapLocalBaseUrl").value = mapDraft.baseUrl;
        element("mapLocalWorld").value = mapDraft.dynmapWorld;
    }
    renderMapStatus();
    const preview = next.aiDecisionPreview;
    element("aiDecisionPreview").textContent = preview
        ? "协议 v" + preview.version + " · 候选 " + preview.candidateCount + " · 待补 " + preview.missingSlots
            + " · " + (preview.needsDecision ? "可准备短决策" : "当前无需决策")
            + "\n\nSYSTEM\n" + preview.systemPrompt + "\n\nUSER JSON\n" + preview.userJson
        : "当前无需准备决策预览。";
    renderTargets();
    renderSelection();
    if (next.camera && (mapView.followCamera || !mapView.initialized)) {
        mapView.x = next.camera.x;
        mapView.z = next.camera.z;
        mapView.initialized = true;
    } else if (!mapView.initialized && next.targets.length) {
        fitMap();
    }
    element("mapEmpty").hidden = Boolean(next.camera || next.targets.length);
    if (!next.connected && !next.targets.length) {
        element("mapEmpty").querySelector("strong").textContent = "等待游戏连接";
        element("mapEmpty").querySelector("span").textContent = "本地中控保持在线，可编辑配置或取消导播恢复意图";
    } else {
        element("mapEmpty").querySelector("strong").textContent = "等待游戏位置";
        element("mapEmpty").querySelector("span").textContent = "进入世界后显示摄影机与现有目标";
    }
    updateAvailability();
    scheduleMap();
}

function renderChat() {
    const messages = state.chat.messages;
    const signature = JSON.stringify(messages);
    if (signature === chatSignature) return;
    chatSignature = signature;
    const list = element("chatMessages");
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 36;
    const scrollTop = list.scrollTop;
    chatUnread = consoleView === "chat" ? 0 : Math.min(messages.length,
        chatUnread + messages.filter((message) => !previousChatIds.has(message.id)).length);
    previousChatIds = new Set(messages.map((message) => message.id));
    const fragment = document.createDocumentFragment();
    for (const message of messages) {
        const row = document.createElement("li");
        const time = document.createElement("time");
        time.textContent = new Date(message.receivedAt).toLocaleTimeString("zh-CN", { hour12: false });
        const text = document.createElement("span");
        text.textContent = message.text;
        row.append(time, text);
        fragment.append(row);
    }
    list.replaceChildren(fragment);
    list.scrollTop = nearBottom ? list.scrollHeight : scrollTop;
    element("chatEmpty").hidden = messages.length > 0;
    element("chatUnread").textContent = String(chatUnread);
    element("chatUnread").hidden = chatUnread === 0;
}

function updateCountdown() {
    const observation = state?.observation;
    const seconds = observation && observation.deadline > 0 ? Math.max(0, Math.ceil((observation.deadline - Date.now()) / 1000)) : 0;
    element("observationCountdown").textContent = seconds > 0 ? (seconds >= 60
        ? Math.floor(seconds / 60) + " 分 " + seconds % 60 + " 秒" : seconds + " 秒") : "—";
}

function renderAccount() {
    const account = state.account;
    element("accountName").textContent = account ? account.name : "未登录 Minecraft";
    element("accountDetail").textContent = account ? "Minecraft 账户" : "等待客户端账户";
    element("accountInfo").title = account ? account.name : "当前未获取登录账户";
    element("accountFallback").textContent = account ? Array.from(account.name)[0].toUpperCase() : "?";
    const source = account?.avatarDataUrl || "";
    if (source !== avatarSource) {
        avatarSource = source;
        element("accountAvatar").hidden = true;
        element("accountFallback").hidden = false;
        if (/^data:image\/(?:png|webp|jpeg);base64,[A-Za-z0-9+/=\r\n]+$/.test(source)) {
            element("accountAvatar").src = source;
        } else {
            element("accountAvatar").removeAttribute("src");
        }
    }
}

function formatDuration(seconds) {
    return seconds >= 60 ? Math.floor(seconds / 60) + " 分 " + Math.floor(seconds % 60) + " 秒" : Math.floor(seconds) + " 秒";
}

function renderQueue() {
    const queue = state.queue;
    const current = queue.current;
    const next = queue.pending[0];
    for (const [card, entry] of [["current", current], ["next", next]]) {
        document.querySelectorAll("[data-duration-card=" + card + "]").forEach((button) => {
            button.dataset.entryId = entry ? entry.entryId : "";
            const range = entry?.kind === "PLAYER" ? "180–480" : "30–600";
            button.title = (card === "current" ? "当前播出" : "下一项")
                + (Number(button.dataset.deltaSeconds) < 0 ? "减少10秒" : "增加10秒") + "，总时长 " + range + " 秒"
                + (entry?.kind === "PLAYER" && card === "current" ? "；等待服务器核准后生效" : "；不会自动开播");
        });
        element(card + "DurationHint").textContent = !entry ? "暂无可调队列项"
            : entry.kind === "PLAYER" ? (state.durationAdjustmentPending && card === "current" ? "等待服务器核准"
                : "总时长 180–480 秒，服务器核准") : "总时长 30–600 秒";
    }
    element("currentEntryLabel").textContent = current ? (state.running ? "当前播出" : "已暂停") : "当前播出";
    element("currentEntryName").textContent = current ? current.name : state.running ? "本地巡游" : "暂无播出项";
    element("currentEntrySource").textContent = current ? (current.source === "MANUAL" ? "手动" : "自动") : "本地";
    element("currentEntryKind").textContent = current ? kindNames[current.kind] : "队列由 Mod 管理";
    const playerNotice = current?.kind === "PLAYER" && state.observation.state === "NOTICE";
    const playerArriving = current?.kind === "PLAYER" && state.observation.state === "APPROACHING";
    element("currentEntryTime").textContent = !current ? (state.running ? "等待可用素材" : "手动启动后开始播出")
        : playerNotice ? "私密预告中 · 计划 " + formatDuration(current.durationSeconds)
        : playerArriving ? "授权入镜准备中 · 尚未开始跟随计时"
        : current.remainingSeconds > 0 || current.durationSeconds > 0 ? "剩余 " + formatDuration(current.remainingSeconds)
            + (current.durationSeconds > 0 ? " / " + formatDuration(current.durationSeconds) : " · 服务端管理") : "服务端管理";
    element("currentEntryProgress").max = current?.durationSeconds > 0 ? current.durationSeconds : 100;
    element("currentEntryProgress").value = current?.durationSeconds > 0 ? Math.max(0, current.durationSeconds - current.remainingSeconds) : 0;
    element("currentEntryProgress").hidden = !current || playerNotice || playerArriving || current.durationSeconds <= 0;
    element("nextEntryName").textContent = next ? next.name : "待播队列为空";
    element("nextEntryMeta").textContent = next ? kindNames[next.kind] + " · " + (next.source === "MANUAL" ? "手动" : "自动")
        + " · " + (next.durationSeconds > 0 ? formatDuration(next.durationSeconds) : "服务端管理") : queue.status;
    element("pendingCount").textContent = String(queue.pending.length);
    element("queueCapacity").textContent = queue.pending.length + " / 64";
    element("queueStatus").textContent = queue.status;
    element("queueAutoSummary").textContent = state.running && state.terrain?.recoveryBlocked ? "地形保护 · 自动补足暂停，等待当前机位安全"
        : state.patrol.pending ? "正在同步巡游范围 · 自动补足暂停"
        : state.patrol.cacheFailed ? "巡游规则读取失败 · 自动补足暂停"
        : !state.patrol.automatic ? "当前世界未获巡游许可 · /director worlds list"
        : queue.settings.autoFill ? "自动补足 · 前向 " + queue.settings.bufferSize + " 项" : "自动补足已关闭";
    element("queuePosition").max = queue.pending.length + 1;
    if (document.activeElement !== element("queuePosition") && Number(element("queuePosition").value) > queue.pending.length + 1) {
        element("queuePosition").value = queue.pending.length + 1;
    }
    const signature = JSON.stringify(queue.pending);
    if (signature === queueSignature) return;
    queueSignature = signature;
    const list = element("pendingQueue");
    list.querySelector(".queue-empty")?.remove();
    const existing = new Map(Array.from(list.children).map((item) => [item.dataset.entryId, item]));
    const kept = new Set();
    const focused = list.contains(document.activeElement) ? document.activeElement : null;
    const scrollTop = list.scrollTop;
    queue.pending.forEach((entry, index) => {
        kept.add(entry.entryId);
        let row = existing.get(entry.entryId);
        if (!row) {
            row = document.createElement("article");
            row.className = "queue-entry";
            row.dataset.entryId = entry.entryId;
            row.setAttribute("role", "listitem");
            const top = document.createElement("div");
            top.className = "queue-entry-top";
            const order = document.createElement("span");
            order.className = "queue-order";
            const name = document.createElement("span");
            name.className = "queue-entry-name";
            const source = document.createElement("span");
            source.className = "source-tag";
            top.append(order, name, source);
            const detail = document.createElement("p");
            detail.className = "queue-entry-meta";
            const actions = document.createElement("div");
            actions.className = "queue-entry-actions";
            for (const [action, label] of [["up", "↑"], ["down", "↓"], ["remove", "移除"]]) {
                const button = document.createElement("button");
                button.className = "queue-action";
                button.textContent = label;
                button.dataset.queueAction = action;
                button.dataset.entryId = entry.entryId;
                button.addEventListener("click", () => {
                    if (action === "remove") sendCommand({ action: "queueRemove", entryId: button.dataset.entryId });
                    else sendCommand({ action: "queueMove", entryId: button.dataset.entryId, delta: action === "up" ? -1 : 1 });
                });
                actions.append(button);
            }
            row.append(top, detail, actions);
        }
        row.querySelector(".queue-order").textContent = String(index + 1).padStart(2, "0");
        row.querySelector(".queue-entry-name").textContent = entry.name;
        row.querySelector(".source-tag").textContent = entry.source === "MANUAL" ? "手动" : "自动";
        row.querySelector(".queue-entry-meta").textContent = kindNames[entry.kind] + " · " + (entry.durationSeconds > 0 ? formatDuration(entry.durationSeconds) : "服务端管理");
        row.querySelectorAll("[data-queue-action]").forEach((button) => {
            button.setAttribute("aria-label", (button.dataset.queueAction === "up" ? "上移" : button.dataset.queueAction === "down" ? "下移" : "移除") + " " + entry.name);
        });
        if (list.children[index] !== row) list.insertBefore(row, list.children[index] || null);
    });
    for (const [id, row] of existing) if (!kept.has(id)) row.remove();
    if (!queue.pending.length) {
        const empty = document.createElement("p");
        empty.className = "queue-empty";
        empty.textContent = "尚无待播项。选择素材加入，或在配置中启用自动补足。";
        list.append(empty);
    }
    if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
    list.scrollTop = scrollTop;
}

function loadQueueSettings() {
    element("queueAutoFill").checked = state.queue.settings.autoFill;
    element("queueBufferSize").value = state.queue.settings.bufferSize;
    element("queueSceneDuration").value = state.queue.settings.sceneDurationSeconds;
    element("queueDraftStatus").textContent = "已同步";
}

function addSelectedToQueue(index) {
    if (!selectedTarget || element("queueAppend").disabled) return;
    const command = { action: "queueAdd", targetId: selectedTarget.id };
    if (index !== undefined) command.index = index;
    if (!element("entryDuration").reportValidity()) return;
    if (element("entryDuration").value !== "") {
        command.durationSeconds = Number(element("entryDuration").value);
    }
    sendCommand(command);
}

function addLibraryTargetToQueue(targetId, index) {
    const target = state?.targets.find((item) => item.id === targetId);
    if (!canQueueTarget(target)) return;
    const command = { action: "queueAdd", targetId };
    if (index !== undefined) command.index = index;
    if (target.kind !== "PLAYER") command.durationSeconds = state.queue.settings.sceneDurationSeconds;
    sendCommand(command);
}

function visibleTargets() {
    const search = element("targetSearch").value.trim().toLocaleLowerCase();
    return (state?.targets || []).filter((target) => (activeKind === "ALL" || target.kind === activeKind)
        && (!search || (target.name + " " + target.world + " " + target.label).toLocaleLowerCase().includes(search)));
}

function mapTargets() {
    return visibleTargets().filter((target) => target.world === state.world && target.x !== null && target.z !== null);
}

function renderTargets() {
    const targets = visibleTargets();
    element("targetCount").textContent = targets.length + " / " + (state?.targets.length || 0);
    // Preserve actual button nodes across polling, including scroll position and keyboard focus.
    const signature = JSON.stringify([targets, selectedId, state?.selectedTargetId, activeKind]);
    if (signature === targetListSignature) return;
    targetListSignature = signature;
    const list = element("targetsList");
    list.querySelector(".target-empty")?.remove();
    const existing = new Map(Array.from(list.children).map((item) => [item.dataset.id, item]));
    const keepIds = new Set();
    const focused = list.contains(document.activeElement) ? document.activeElement : null;
    const scrollTop = list.scrollTop;
    for (let index = 0; index < targets.length; index++) {
        const target = targets[index];
        keepIds.add(target.id);
        let item = existing.get(target.id);
        if (!item) {
            item = document.createElement("li");
            item.className = "target-item";
            item.dataset.id = target.id;
            const select = document.createElement("button");
            select.type = "button";
            select.className = "target-select";
            select.addEventListener("click", () => selectTarget(item.dataset.id));
            const top = document.createElement("span");
            top.className = "target-item-top";
            const dot = document.createElement("i");
            dot.className = "kind-dot";
            const name = document.createElement("span");
            name.className = "target-name";
            const kind = document.createElement("span");
            kind.className = "target-kind";
            top.append(dot, name, kind);
            const bottom = document.createElement("span");
            bottom.className = "target-item-bottom";
            bottom.append(document.createElement("span"), document.createElement("span"));
            select.append(top, bottom);
            const actions = document.createElement("div");
            actions.className = "target-actions";
            for (const [position, label] of [["head", "队首"], ["tail", "队尾"]]) {
                const button = document.createElement("button");
                button.type = "button";
                button.className = "target-add";
                button.textContent = label;
                button.dataset.libraryAdd = position;
                button.dataset.targetId = target.id;
                button.title = position === "head" ? "加入待播队首，作为下一项，不打断当前播出" : "加入待播队尾";
                button.addEventListener("click", (event) => {
                    event.stopPropagation();
                    addLibraryTargetToQueue(button.dataset.targetId, position === "head" ? 0 : undefined);
                });
                button.addEventListener("dblclick", (event) => { event.stopPropagation(); event.preventDefault(); });
                actions.append(button);
            }
            item.append(select, actions);
        }
        item.classList.toggle("selected", target.id === selectedId);
        item.classList.toggle("current", target.id === state.selectedTargetId);
        item.classList.toggle("unavailable", !target.available);
        item.querySelector(".target-select").setAttribute("aria-pressed", String(target.id === selectedId));
        item.querySelector(".kind-dot").className = "kind-dot " + target.kind.toLowerCase();
        item.querySelector(".target-name").textContent = target.name;
        item.querySelector(".target-kind").textContent = kindNames[target.kind];
        item.querySelector(".target-item-bottom").firstElementChild.textContent = target.kind === "PLAYER"
            ? target.world + " · " + target.label : target.label;
        item.querySelector(".target-item-bottom").lastElementChild.textContent = target.kind === "PLAYER" && target.availability
            ? playerAvailabilityNames[target.availability] : !target.available ? "暂不可用"
            : target.automaticAvailable ? "可展示" : "可手动展示";
        item.querySelector(".target-select").title = "查看 " + target.name + " · " + target.world
            + (target.unavailableReason ? " · " + target.unavailableReason : "");
        item.querySelectorAll("[data-library-add]").forEach((button) => {
            button.disabled = !canQueueTarget(target);
            button.setAttribute("aria-label", "将 " + target.name + " 加入待播" + (button.dataset.libraryAdd === "head" ? "队首" : "队尾"));
        });
        if (list.children[index] !== item) list.insertBefore(item, list.children[index] || null);
    }
    for (const [id, item] of existing) if (!keepIds.has(id)) item.remove();
    if (!targets.length) {
        const empty = document.createElement("li");
        empty.className = "target-empty";
        const title = document.createElement("strong");
        title.textContent = state?.targets.length ? "没有匹配的目标" : "目标库暂为空";
        const detail = document.createElement("span");
        detail.textContent = state?.targets.length ? "调整分类或搜索关键词" : "连接游戏后拉取目标；无插件时继续本地巡游";
        empty.append(title, detail);
        list.append(empty);
    }
    if (focused?.isConnected && document.activeElement !== focused) focused.focus({ preventScroll: true });
    list.scrollTop = scrollTop;
}

function selectTarget(id) {
    const target = state?.targets.find((candidate) => candidate.id === id);
    if (!target) return;
    if (selectedId !== id) {
        entryDurationDirty = false;
        element("entryDuration").value = target.kind === "PLAYER" ? "" : state.queue.settings.sceneDurationSeconds;
    }
    selectedId = id;
    selectedTarget = target;
    renderTargets();
    renderSelection();
    updateAvailability();
    scheduleMap();
}

function renderSelection() {
    const target = selectedTarget;
    element("selectionKind").textContent = target ? kindNames[target.kind] : "—";
    element("selectionName").textContent = target ? target.name : "选择一个展示目标";
    element("selectionDescription").textContent = target ? target.label : "从素材库或位置图选择，再加入队列。";
    element("selectionWorld").textContent = target ? target.world : "—";
    element("selectionCoordinates").textContent = !target ? "—" : target.x === null ? "授权入镜后显示位置"
        : [target.x, target.y, target.z].map((n) => n.toFixed(1)).join(" / ");
    element("selectionRadius").textContent = target && !["PLAYER", "IDLE"].includes(target.kind) ? target.radius + " 格" : "—";
    element("selectionStatus").textContent = !target ? "玩家目标始终经过 15 秒私密预告。"
        : !state.targets.some((candidate) => candidate.id === target.id) ? "此目标已不在当前候选中，请重新拉取。"
            : !target.available ? target.unavailableReason || "当前目标不可展示；请检查当前世界、边界、排除区或玩家授权。"
                : target.kind === "PLAYER" ? "先私密预告 15 秒；未拒绝且获服务端授权后才接近入镜。玩家可随时拒绝或加入长期黑名单。"
                    : target.kind === "IDLE" ? "传送到保存的机位与注视方向后完全静止，按场景时长结束；需本人 tp 权限，继续遵守巡游、边界与排除区规则。"
                    : (target.automaticAvailable ? "" : "仅手动选择，不参与自动或AI补点。")
                        + "远处或未加载场景由本账号发起传送，到位并加载后开始运镜。";
    element("entryDurationUnit").textContent = "秒";
    element("entryDuration").min = target?.kind === "PLAYER" ? "180" : "30";
    element("entryDuration").max = target?.kind === "PLAYER" ? "480" : "600";
    element("entryDuration").required = target?.kind !== "PLAYER";
    element("entryDuration").placeholder = target?.kind === "PLAYER" ? "随机" : "";
    element("entryDuration").title = target?.kind === "PLAYER" ? "180–480秒；留空由本地随机计划，播出前由服务器核准" : "30–600秒";
}

function loadCameraValues() {
    element("cameraMode").value = state.settings.mode;
    element("cameraModeHelp").textContent = cameraModeHelp[state.settings.mode];
    for (const setting of cameraNumberSettings) {
        element(setting + "Range").value = element(setting + "Number").value = state.settings[setting];
    }
    element("cameraDraftStatus").textContent = "已同步";
}

function renderLiveCameraValues() {
    for (const setting of cameraNumberSettings) {
        element("live" + liveCameraIds[setting] + "Value").textContent = element("live" + liveCameraIds[setting]).value
            + (setting === "heightY" ? "" : setting === "speed" ? " 格/秒" : setting === "turnRateDegreesPerSecond" ? " 度/秒" : " 格");
    }
}

function scheduleLiveCamera(persist, immediate) {
    clearTimeout(liveCameraTimer);
    liveCameraPersist ||= persist;
    liveCameraDirty = true;
    liveCameraRevision++;
    element("liveCameraStatus").textContent = "正在平滑调整…";
    liveCameraTimer = setTimeout(flushLiveCamera, immediate ? 0 : 180);
}

async function flushLiveCamera() {
    if (liveCameraSending || cameraSavePending || !liveCameraDirty || !apiOnline) return;
    const revision = liveCameraRevision;
    const command = { action: "camera", mode: element("liveCameraMode").value, persist: liveCameraPersist };
    for (const setting of cameraNumberSettings) command[setting] = Number(element("live" + liveCameraIds[setting]).value);
    liveCameraPersist = false;
    liveCameraSending = true;
    liveCameraRequest = sendCommand(command, !command.persist);
    const result = await liveCameraRequest;
    liveCameraRequest = null;
    liveCameraSending = false;
    if (result.ok && result.synchronized && revision === liveCameraRevision) {
        liveCameraDirty = false;
        element("liveCameraStatus").textContent = command.persist ? "已应用 · 已保存" : "已实时应用 · 未保存";
    } else if (!result.ok || !result.synchronized) {
        element("liveCameraStatus").textContent = "调整尚未确认，请检查连接后重试";
    }
    updateAvailability();
    if (revision !== liveCameraRevision && apiOnline) liveCameraTimer = setTimeout(flushLiveCamera, 0);
}

function renderAiForm() {
    const draft = aiDrafts.get(currentProvider);
    if (!draft) return;
    document.querySelectorAll("[data-provider]").forEach((button) => {
        const active = button.dataset.provider === currentProvider;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
    });
    element("aiProviderTitle").textContent = providerNames[currentProvider] + " 配置";
    element("apiBase").value = draft.baseUrl;
    element("apiModel").value = draft.model;
    element("apiKey").value = draft.apiKey;
    element("clearKey").checked = draft.clearKey;
    element("apiKey").disabled = draft.clearKey;
    element("aiEnabled").checked = aiEnabledDraft;
    element("aiDraftStatus").textContent = draft.dirty || aiEnabledDirty ? "存在未保存草稿" : "已同步";
    element("keyPresence").textContent = state.ai.profiles[currentProvider].keyPresent ? "本机已保存密钥" : "尚未设置密钥";
}

function renderAiSummary() {
    const ai = state.ai;
    element("aiSavedProvider").textContent = providerNames[ai.provider];
    element("aiConfigStatus").textContent = ai.loadFailed ? "配置读取失败，请重新保存"
        : ai.configured ? "地址、模型与密钥已配置" : "配置尚未完整";
    element("aiSaveStatus").textContent = ai.loadFailed ? "配置读取失败；保存前请核对本机配置。"
        : ai.saved ? "当前配置已写入本机。密钥不会通过状态接口返回。" : "当前配置尚未保存到本机。";
    const runtime = !ai.strategyEnabled ? "已关闭 · 本地选点" : ai.requestPending ? "短决策请求中"
        : !state.running ? "已启用 · 等待启动导播" : "已启用 · 按需补足队列";
    element("aiRuntimeStatus").textContent = runtime;
    element("aiStrategyBadge").textContent = ai.strategyEnabled ? "策略已启用" : "策略已关闭";
    element("aiStrategyBadge").classList.toggle("online", ai.strategyEnabled);
    element("aiCapability").textContent = !ai.strategyEnabled ? "已关闭" : ai.requestPending ? "决策中" : "已启用";
    element("aiLastStatus").textContent = ai.lastStatus || "暂无策略请求";
    element("aiNextRequest").textContent = ai.nextRequestInSeconds > 0 ? ai.nextRequestInSeconds + " 秒后" : "可按需请求";
    const usage = ai.usage;
    element("aiRequests").textContent = (usage?.requests || 0) + " 次";
    element("aiTokenUsage").textContent = (usage?.promptTokens || 0) + " / " + (usage?.completionTokens || 0);
    element("aiTotalTokens").textContent = String(usage?.totalTokens || 0);
    element("aiUnreportedRequests").textContent = (usage?.unreportedRequests || 0) + " 次";
    element("aiTestStatus").textContent = (ai.testStatus || "尚未测试已保存的连接")
        + "。连接测试仅请求模型列表，不发起聊天或启用策略。";
    element("aiRulesStatus").textContent = runtime + "；策略失败回退本地选点，调用间隔至少 60 秒。";
    const modelList = element("aiModelList");
    const models = currentProvider === ai.provider ? ai.models || [] : [];
    const signature = JSON.stringify(models);
    if (modelList.dataset.models !== signature) {
        modelList.replaceChildren(new Option(models.length ? "选择一个模型填入草稿" : "测试后选择模型", ""));
        for (const model of models) modelList.append(new Option(model, model));
        modelList.dataset.models = signature;
    }
}

function renderMapStatus() {
    const map = state.map;
    const supported = map.supported;
    const status = !supported ? "需进入观察者模式" : map.loading ? "客户端正在导入"
        : map.source === "LOCAL" ? "本机来源已配置" : map.source === "SERVER" ? "服务端来源已配置" : "等待配置地图来源";
    element("mapCapability").textContent = status;
    element("mapStatusBadge").textContent = status;
    element("mapStatusBadge").classList.toggle("online", supported && map.configured && !map.loading);
    element("mapStatusBadge").classList.toggle("warning", supported && map.loading);
    element("mapConfigWorld").textContent = state.world || "尚未进入世界";
    element("mapServiceStatus").textContent = state.allowed ? "已授权 · 可使用玩家跟随与服务端来源"
        : "本地模式 · 玩家跟随未授权";
    element("mapSourceKind").textContent = map.source === "LOCAL" ? "本机配置（优先）"
        : map.source === "SERVER" ? "导播服务配置" : "尚未配置";
    element("mapMappingStatus").textContent = map.configured ? map.dynmapWorld : "尚未设置当前世界来源";
    element("mapSourceUrl").textContent = map.baseUrl || "尚未配置";
    element("mapSceneCount").textContent = map.sceneCount + " 项（本机当前世界缓存）";
    element("mapFetchedAt").textContent = map.fetchedAt > 0 ? new Date(map.fetchedAt).toLocaleString("zh-CN", { hour12: false }) : "尚未导入";
    element("mapImportDetail").textContent = map.detail || "保存本机地图来源后，手动拉取标记和区域。";
    element("mapDraftStatus").textContent = mapDraft?.dirty ? "存在未保存草稿" : map.localConfigured ? "本机来源已保存" : "本机来源未配置";
    element("mapSettingsScope").textContent = state.connected ? "仅用于 " + state.server + " · " + state.world
        : "进入世界后可配置此服务器与世界的地图来源。";
    element("mapRulesStatus").textContent = status + "；本机来源优先、按服务器与世界隔离，仅手动直连外链导入。玩家跟随须由导播服务授权。";
    element("dynmapLink").hidden = !map.configured;
    if (map.baseUrl) {
        const source = new URL(map.baseUrl);
        if (["http:", "https:"].includes(source.protocol)) element("dynmapLink").href = source.href;
    }
}

for (const id of ["mapLocalBaseUrl", "mapLocalWorld"]) {
    element(id).addEventListener("input", () => {
        if (!mapDraft) return;
        mapDraft.baseUrl = element("mapLocalBaseUrl").value;
        mapDraft.dynmapWorld = element("mapLocalWorld").value;
        mapDraft.dirty = true;
        renderMapStatus();
        updateAvailability();
    });
}
element("resetMapSettings").addEventListener("click", () => {
    if (!state || !mapDraft) return;
    mapDraft.baseUrl = state.map.localBaseUrl;
    mapDraft.dynmapWorld = state.map.localDynmapWorld;
    mapDraft.dirty = false;
    element("mapLocalBaseUrl").value = mapDraft.baseUrl;
    element("mapLocalWorld").value = mapDraft.dynmapWorld;
    renderMapStatus();
    updateAvailability();
});
element("clearMapSettings").addEventListener("click", () => {
    if (!mapDraft) return;
    for (const id of ["mapLocalBaseUrl", "mapLocalWorld"]) element(id).value = "";
    mapDraft.baseUrl = "";
    mapDraft.dynmapWorld = "";
    mapDraft.dirty = true;
    renderMapStatus();
    updateAvailability();
});
element("mapSettingsForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (element("saveMapSettings").disabled || !element("mapSettingsForm").reportValidity()) return;
    const submitted = { ...mapDraft, baseUrl: mapDraft.baseUrl.trim(), dynmapWorld: mapDraft.dynmapWorld.trim() };
    if (Boolean(submitted.baseUrl) !== Boolean(submitted.dynmapWorld)) {
        showFeedback("请同时填写 Dynmap 地址与世界名，或同时留空清除本机来源", true);
        return;
    }
    if (submitted.baseUrl) {
        try {
            const url = new URL(submitted.baseUrl);
            if (!["https:", "http:"].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash) {
                throw new Error("invalid");
            }
        } catch {
            showFeedback("地图基础地址须为 HTTP(S)，且不含账号密码、查询参数或片段", true);
            element("mapLocalBaseUrl").focus();
            return;
        }
    }
    const result = await sendCommand({ action: "mapSettings", server: submitted.server, world: submitted.world,
        baseUrl: submitted.baseUrl, dynmapWorld: submitted.dynmapWorld });
    if (result.ok && result.synchronized && mapDraft.server === submitted.server && mapDraft.world === submitted.world
        && mapDraft.baseUrl.trim() === submitted.baseUrl && mapDraft.dynmapWorld.trim() === submitted.dynmapWorld) {
        const confirmed = state.map.localBaseUrl.replace(/\/+$/, "") === submitted.baseUrl.replace(/\/+$/, "")
            && state.map.localDynmapWorld === submitted.dynmapWorld;
        if (confirmed) {
            mapDraft.baseUrl = state.map.localBaseUrl;
            mapDraft.dynmapWorld = state.map.localDynmapWorld;
            mapDraft.dirty = false;
            element("mapLocalBaseUrl").value = mapDraft.baseUrl;
            element("mapLocalWorld").value = mapDraft.dynmapWorld;
        } else showFeedback("保存后的本机来源尚未确认，请刷新状态后核对", true);
        renderMapStatus();
        updateAvailability();
    }
});

document.querySelectorAll("[data-command]").forEach((button) => {
    button.addEventListener("click", () => sendCommand({ action: button.dataset.command }));
});
element("showSelected").addEventListener("click", () => {
    if (selectedTarget) sendCommand({ action: "show", id: selectedTarget.id });
});
document.querySelectorAll("[data-duration-card]").forEach((button) => {
    button.addEventListener("click", () => {
        if (button.disabled) return;
        sendCommand({ action: "queueAdjust", entryId: button.dataset.entryId, deltaSeconds: Number(button.dataset.deltaSeconds) });
    });
});
element("queueAppend").addEventListener("click", () => addSelectedToQueue());
element("queueNext").addEventListener("click", () => addSelectedToQueue(0));
element("queueInsertForm").addEventListener("submit", (event) => {
    event.preventDefault();
    if (!element("queueInsert").disabled && element("queueInsertForm").reportValidity()) {
        addSelectedToQueue(Number(element("queuePosition").value) - 1);
    }
});
element("entryDuration").addEventListener("input", () => { entryDurationDirty = true; });
element("accountAvatar").addEventListener("load", () => {
    if (element("accountAvatar").getAttribute("src") === avatarSource) {
        element("accountAvatar").hidden = false;
        element("accountFallback").hidden = true;
    }
});
element("accountAvatar").addEventListener("error", () => {
    element("accountAvatar").hidden = true;
    element("accountFallback").hidden = false;
});
element("targetSearch").addEventListener("input", () => { renderTargets(); scheduleMap(); });
document.querySelectorAll("[data-kind]").forEach((button) => {
    button.addEventListener("click", () => {
        activeKind = button.dataset.kind;
        document.querySelectorAll("[data-kind]").forEach((tab) => {
            const active = tab === button;
            tab.classList.toggle("active", active);
            tab.setAttribute("aria-pressed", String(active));
        });
        renderTargets();
        scheduleMap();
    });
});

element("liveCameraMode").addEventListener("change", () => {
    scheduleLiveCamera(false, true);
    updateAvailability();
});
for (const setting of cameraNumberSettings) {
    const slider = element("live" + liveCameraIds[setting]);
    slider.addEventListener("input", () => {
        renderLiveCameraValues();
        scheduleLiveCamera(false, false);
    });
    slider.addEventListener("change", () => scheduleLiveCamera(false, true));
}
element("saveLiveCamera").addEventListener("click", () => scheduleLiveCamera(true, true));
element("cameraMode").addEventListener("change", () => {
    cameraDirty = true;
    element("cameraModeHelp").textContent = cameraModeHelp[element("cameraMode").value];
    element("cameraDraftStatus").textContent = "未应用草稿";
    updateAvailability();
});
for (const setting of cameraNumberSettings) {
    for (const suffix of ["Range", "Number"]) {
        element(setting + suffix).addEventListener("input", (event) => {
            const other = suffix === "Range" ? "Number" : "Range";
            if (event.target.value !== "") element(setting + other).value = event.target.value;
            cameraDirty = true;
            element("cameraDraftStatus").textContent = "未应用草稿";
            updateAvailability();
        });
    }
}
element("resetCamera").addEventListener("click", () => {
    if (!state) return;
    cameraDirty = false;
    loadCameraValues();
    updateAvailability();
});
element("cameraForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (element("saveCamera").disabled || !element("cameraForm").reportValidity()) return;
    const command = { action: "camera", mode: element("cameraMode").value };
    for (const setting of cameraNumberSettings) command[setting] = Number(element(setting + "Number").value);
    // A newer explicit save supersedes queued previews, and waits for an already-sent preview to drain.
    clearTimeout(liveCameraTimer);
    liveCameraDirty = false;
    liveCameraPersist = false;
    liveCameraRevision++;
    cameraSavePending = true;
    updateAvailability();
    if (liveCameraRequest) await liveCameraRequest;
    const result = await sendCommand(command);
    cameraSavePending = false;
    if (result.ok && result.synchronized) {
        // Keep any edits made while the request was pending.
        if (element("cameraMode").value === command.mode && cameraNumberSettings.every((setting) => Number(element(setting + "Number").value) === command[setting])) {
            cameraDirty = false;
            loadCameraValues();
            updateAvailability();
        }
    }
    updateAvailability();
});

for (const id of ["queueAutoFill", "queueBufferSize", "queueSceneDuration"]) {
    element(id).addEventListener("input", () => {
        queueSettingsDirty = true;
        element("queueDraftStatus").textContent = "未应用草稿";
        updateAvailability();
    });
}
element("resetQueueSettings").addEventListener("click", () => {
    if (!state) return;
    queueSettingsDirty = false;
    loadQueueSettings();
    updateAvailability();
});
element("queueSettingsForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (element("saveQueueSettings").disabled || !element("queueSettingsForm").reportValidity()) return;
    const autoFill = element("queueAutoFill").checked;
    const bufferSize = Number(element("queueBufferSize").value);
    const sceneDurationSeconds = Number(element("queueSceneDuration").value);
    const result = await sendCommand({ action: "queueSettings", autoFill, bufferSize, sceneDurationSeconds });
    if (result.ok && result.synchronized && element("queueAutoFill").checked === autoFill
        && Number(element("queueBufferSize").value) === bufferSize
        && Number(element("queueSceneDuration").value) === sceneDurationSeconds) {
        queueSettingsDirty = false;
        loadQueueSettings();
        updateAvailability();
    }
});

document.querySelectorAll("[data-provider]").forEach((button) => {
    button.addEventListener("click", () => {
        currentProvider = button.dataset.provider;
        renderAiForm();
        renderAiSummary();
        updateAvailability();
    });
});
for (const id of ["apiBase", "apiModel", "apiKey", "clearKey"]) {
    element(id).addEventListener("input", () => {
        const draft = aiDrafts.get(currentProvider);
        if (!draft) return;
        draft.baseUrl = element("apiBase").value;
        draft.model = element("apiModel").value;
        draft.clearKey = element("clearKey").checked;
        draft.apiKey = draft.clearKey ? "" : element("apiKey").value;
        draft.dirty = true;
        if (draft.clearKey) element("apiKey").value = "";
        element("apiKey").disabled = draft.clearKey;
        element("aiDraftStatus").textContent = "存在未保存草稿";
        updateAvailability();
    });
}
element("aiEnabled").addEventListener("input", () => {
    if (!state) return;
    aiEnabledDraft = element("aiEnabled").checked;
    aiEnabledDirty = aiEnabledDraft !== state.ai.strategyEnabled;
    element("aiDraftStatus").textContent = "存在未保存草稿";
    updateAvailability();
});
element("testAi").addEventListener("click", () => {
    if (!element("testAi").disabled) sendCommand({ action: "aiTest" });
});
element("aiModelList").addEventListener("change", () => {
    if (element("aiModelList").disabled || !element("aiModelList").value) return;
    element("apiModel").value = element("aiModelList").value;
    element("apiModel").dispatchEvent(new Event("input"));
    element("aiModelList").value = "";
});
for (const type of ["copy", "cut"]) element("apiKey").addEventListener(type, (event) => event.preventDefault());
element("resetAi").addEventListener("click", () => {
    if (!state) return;
    const profile = state.ai.profiles[currentProvider];
    aiDrafts.set(currentProvider, { baseUrl: profile.baseUrl, model: profile.model, apiKey: "", clearKey: false, dirty: false });
    aiEnabledDraft = state.ai.strategyEnabled;
    aiEnabledDirty = false;
    renderAiForm();
    updateAvailability();
});
element("aiForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (element("saveAi").disabled || !element("aiForm").reportValidity()) return;
    const provider = currentProvider;
    const draft = aiDrafts.get(provider);
    const submitted = { ...draft };
    const submittedEnabled = aiEnabledDraft;
    if (draft.apiKey && !/^[\x21-\x7e]+$/.test(draft.apiKey)) {
        showFeedback("API Key 含空白或非 ASCII 字符，请只粘贴密钥本身", true);
        element("apiKey").focus();
        return;
    }
    if (draft.baseUrl.trim()) {
        try {
            const url = new URL(draft.baseUrl.trim());
            if (!["https:", "http:"].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash) {
                throw new Error("invalid");
            }
        } catch {
            showFeedback("基础地址须为 HTTP(S)，且不含账号密码、查询参数或片段", true);
            element("apiBase").focus();
            return;
        }
    }
    const result = await sendCommand({ action: "ai", provider, baseUrl: draft.baseUrl, model: draft.model,
        apiKey: draft.apiKey, clearKey: draft.clearKey, enabled: submittedEnabled });
    if (result.ok) {
        if (aiEnabledDraft === submittedEnabled) aiEnabledDirty = !result.synchronized;
        const latest = aiDrafts.get(provider);
        if (latest.baseUrl === submitted.baseUrl && latest.model === submitted.model
            && latest.apiKey === submitted.apiKey && latest.clearKey === submitted.clearKey) {
            latest.apiKey = "";
            latest.clearKey = false;
            latest.dirty = !result.synchronized;
            if (currentProvider === provider) renderAiForm();
        }
        updateAvailability();
    }
});

for (const [buttonId, settings] of [["workspaceTab", false], ["settingsTab", true]]) {
    element(buttonId).addEventListener("click", () => {
        element("workView").hidden = settings;
        element("settingsView").hidden = !settings;
        element("workspaceTab").classList.toggle("active", !settings);
        element("workspaceTab").setAttribute("aria-selected", String(!settings));
        element("settingsTab").classList.toggle("active", settings);
        element("settingsTab").setAttribute("aria-selected", String(settings));
        if (!settings) resizeMap();
    });
}
function selectConfiguration(tab) {
    for (const name of ["camera", "queue", "ai", "map", "rules"]) element(name + "Config").hidden = name !== tab;
    document.querySelectorAll("[data-config-tab]").forEach((button) => {
        const active = button.dataset.configTab === tab;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
    });
}
document.querySelectorAll("[data-config-tab]").forEach((button) => {
    button.addEventListener("click", () => selectConfiguration(button.dataset.configTab));
});
for (const id of ["openQueueSettings", "editQueueSettings"]) {
    element(id).addEventListener("click", () => { element("settingsTab").click(); selectConfiguration("queue"); });
}
for (const [openId, closeId, selector] of [["openLibrary", "closeLibrary", ".target-library"], ["openInspector", "closeInspector", ".inspector"]]) {
    element(openId).addEventListener("click", () => document.querySelector(selector).classList.toggle("open"));
    element(closeId).addEventListener("click", () => document.querySelector(selector).classList.remove("open"));
}
function setConsoleView(view) {
    consoleView = consoleView === view ? null : view;
    const chatOpen = consoleView === "chat";
    const logOpen = consoleView === "log";
    element("logPanel").classList.toggle("collapsed", !consoleView);
    element("logPanel").classList.toggle("chat-expanded", chatOpen);
    document.body.classList.toggle("logs-expanded", logOpen);
    document.body.classList.toggle("chat-expanded", chatOpen);
    element("chatPanel").hidden = !chatOpen;
    element("commandLog").hidden = !logOpen;
    element("logLatest").hidden = chatOpen;
    element("clearLogs").hidden = chatOpen;
    element("logToggle").setAttribute("aria-expanded", String(logOpen));
    element("chatToggle").setAttribute("aria-expanded", String(chatOpen));
    if (chatOpen) {
        chatUnread = 0;
        element("chatUnread").hidden = true;
        element("chatMessages").scrollTop = element("chatMessages").scrollHeight;
        if (!element("chatInput").disabled) element("chatInput").focus();
    }
}
element("logToggle").addEventListener("click", () => setConsoleView("log"));
element("chatToggle").addEventListener("click", () => setConsoleView("chat"));
element("chatInput").addEventListener("input", updateAvailability);
element("chatInput").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && event.isComposing) event.preventDefault();
});
element("chatForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (element("chatSend").disabled || !element("chatForm").reportValidity()) return;
    const input = element("chatInput");
    const message = input.value;
    chatSending = true;
    updateAvailability();
    const result = await sendCommand({ action: "chatSend", message }, true);
    if (result.ok && input.value === message) input.value = "";
    chatSending = false;
    updateAvailability();
    if (!input.disabled) input.focus();
});
element("clearLogs").addEventListener("click", () => {
    logs.length = 0;
    element("commandLog").replaceChildren();
    element("logCount").textContent = "0";
    element("logLatest").textContent = "本地会话记录已清空";
});

function scheduleMap() {
    if (!mapFrame) mapFrame = requestAnimationFrame(() => { mapFrame = 0; drawMap(); });
}

function resizeMap() {
    const bounds = canvas.getBoundingClientRect();
    mapWidth = bounds.width;
    mapHeight = bounds.height;
    if (!mapWidth || !mapHeight) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(mapWidth * ratio);
    canvas.height = Math.round(mapHeight * ratio);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    scheduleMap();
}

function drawMap() {
    if (!mapWidth || !mapHeight) return;
    context.clearRect(0, 0, mapWidth, mapHeight);
    context.fillStyle = "#111b14";
    context.fillRect(0, 0, mapWidth, mapHeight);
    const targetStep = 70 / mapView.scale;
    const exponent = 10 ** Math.floor(Math.log10(targetStep));
    const step = [1, 2, 5, 10].find((n) => n * exponent >= targetStep) * exponent;
    const left = mapView.x - mapWidth / (2 * mapView.scale);
    const top = mapView.z - mapHeight / (2 * mapView.scale);
    context.lineWidth = 1;
    context.strokeStyle = "#223125";
    context.beginPath();
    for (let x = Math.ceil(left / step) * step; x < left + mapWidth / mapView.scale; x += step) {
        const pixel = (x - left) * mapView.scale;
        context.moveTo(pixel, 0);
        context.lineTo(pixel, mapHeight);
    }
    for (let z = Math.ceil(top / step) * step; z < top + mapHeight / mapView.scale; z += step) {
        const pixel = (z - top) * mapView.scale;
        context.moveTo(0, pixel);
        context.lineTo(mapWidth, pixel);
    }
    context.stroke();
    context.font = "10px 'Segoe UI', 'Microsoft YaHei UI', sans-serif";
    context.fillStyle = "#526b50";
    for (let x = Math.ceil(left / step) * step; x < left + mapWidth / mapView.scale; x += step) {
        context.fillText(Math.round(x).toString(), (x - left) * mapView.scale + 5, mapHeight - 40);
    }
    for (let z = Math.ceil(top / step) * step; z < top + mapHeight / mapView.scale; z += step) {
        context.fillText(Math.round(z).toString(), 8, (z - top) * mapView.scale - 5);
    }
    const targets = mapTargets();
    for (const target of targets) {
        const x = (target.x - left) * mapView.scale;
        const z = (target.z - top) * mapView.scale;
        const radius = ["PLAYER", "IDLE"].includes(target.kind) ? 0 : target.radius * mapView.scale;
        if (x + radius < -30 || z + radius < -30 || x - radius > mapWidth + 30 || z - radius > mapHeight + 30) continue;
        const selected = target.id === selectedId;
        const current = target.id === state.selectedTargetId;
        const color = target.kind === "PLAYER" ? "#94c1e7" : target.kind === "IDLE" ? "#8ecbd0"
            : target.kind === "VIEWPOINT" ? "#d6bf8f" : "#bddb92";
        context.globalAlpha = target.available ? 1 : 0.4;
        if (radius > 0) {
            context.fillStyle = target.kind === "VIEWPOINT" ? "#d6bf8f09" : "#bddb920d";
            context.strokeStyle = selected ? color : "#496343";
            context.setLineDash(target.kind === "AREA" ? [4, 5] : []);
            context.lineWidth = selected ? 1.5 : 1;
            context.beginPath();
            context.arc(x, z, radius, 0, Math.PI * 2);
            context.fill();
            context.stroke();
            context.setLineDash([]);
        }
        context.fillStyle = color;
        context.strokeStyle = "#111b14";
        context.lineWidth = 2;
        context.beginPath();
        if (target.kind === "PLAYER") context.arc(x, z, 4.5, 0, Math.PI * 2);
        else if (target.kind === "VIEWPOINT") {
            context.moveTo(x, z - 6); context.lineTo(x + 6, z); context.lineTo(x, z + 6); context.lineTo(x - 6, z); context.closePath();
        } else context.rect(x - 4.5, z - 4.5, 9, 9);
        context.fill();
        context.stroke();
        if (selected || current) {
            context.strokeStyle = color;
            context.lineWidth = selected ? 1.5 : 1;
            context.beginPath();
            context.arc(x, z, selected ? 11 : 9, 0, Math.PI * 2);
            context.stroke();
        }
        if (targets.length <= 20 || selected || current) {
            const label = target.name.length > 16 ? target.name.slice(0, 16) + "…" : target.name;
            const width = context.measureText(label).width;
            context.fillStyle = "#111b14dc";
            context.fillRect(x + 10, z - 9, width + 9, 19);
            context.fillStyle = selected ? "#ecf3df" : "#a8bb9d";
            context.fillText(label, x + 14, z + 4);
        }
        context.globalAlpha = 1;
    }
    if (state?.camera) {
        const camera = state.camera;
        const x = (camera.x - left) * mapView.scale;
        const z = (camera.z - top) * mapView.scale;
        context.save();
        context.translate(x, z);
        context.rotate(camera.yaw * Math.PI / 180);
        context.fillStyle = "#bddb9212";
        context.beginPath(); context.moveTo(0, 0); context.lineTo(-30, 49); context.lineTo(30, 49); context.closePath(); context.fill();
        context.fillStyle = "#d1eeab";
        context.strokeStyle = "#17251a";
        context.lineWidth = 2;
        context.beginPath(); context.moveTo(0, 11); context.lineTo(-6, -7); context.lineTo(0, -3); context.lineTo(6, -7); context.closePath(); context.fill(); context.stroke();
        context.restore();
        context.fillStyle = "#d1e2bf";
        context.fillText("摄影机", x + 14, z - 12);
    }
    element("mapScale").textContent = step + " 格 / 网格";
}

function fitMap() {
    const points = mapTargets().map((target) => ({ x: target.x, z: target.z, radius: ["PLAYER", "IDLE"].includes(target.kind) ? 0 : target.radius }));
    if (state?.camera) points.push({ x: state.camera.x, z: state.camera.z, radius: 0 });
    if (!points.length) return;
    const minX = Math.min(...points.map((point) => point.x - point.radius));
    const maxX = Math.max(...points.map((point) => point.x + point.radius));
    const minZ = Math.min(...points.map((point) => point.z - point.radius));
    const maxZ = Math.max(...points.map((point) => point.z + point.radius));
    mapView.x = (minX + maxX) / 2;
    mapView.z = (minZ + maxZ) / 2;
    mapView.scale = Math.max(0.15, Math.min(8, (mapWidth - 110) / Math.max(30, maxX - minX), (mapHeight - 130) / Math.max(30, maxZ - minZ)));
    mapView.followCamera = false;
    mapView.initialized = true;
    scheduleMap();
}

function zoomMap(factor, pixelX = mapWidth / 2, pixelZ = mapHeight / 2) {
    const oldScale = mapView.scale;
    const scale = Math.max(0.15, Math.min(12, oldScale * factor));
    mapView.x += (pixelX - mapWidth / 2) * (1 / oldScale - 1 / scale);
    mapView.z += (pixelZ - mapHeight / 2) * (1 / oldScale - 1 / scale);
    mapView.scale = scale;
    scheduleMap();
}

canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    const bounds = canvas.getBoundingClientRect();
    mapView.followCamera = false;
    zoomMap(Math.exp(-event.deltaY * 0.0015), event.clientX - bounds.left, event.clientY - bounds.top);
}, { passive: false });
canvas.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    drag = { id: event.pointerId, x: event.clientX, z: event.clientY, centerX: mapView.x, centerZ: mapView.z, moved: false };
    canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener("pointermove", (event) => {
    const bounds = canvas.getBoundingClientRect();
    const x = event.clientX - bounds.left;
    const z = event.clientY - bounds.top;
    element("cursorCoordinates").textContent = "X " + Math.round(mapView.x + (x - mapWidth / 2) / mapView.scale)
        + " · Z " + Math.round(mapView.z + (z - mapHeight / 2) / mapView.scale);
    if (!drag || drag.id !== event.pointerId) return;
    if (Math.hypot(event.clientX - drag.x, event.clientY - drag.z) > 4) drag.moved = true;
    if (drag.moved) {
        mapView.followCamera = false;
        mapView.x = drag.centerX - (event.clientX - drag.x) / mapView.scale;
        mapView.z = drag.centerZ - (event.clientY - drag.z) / mapView.scale;
        canvas.classList.add("dragging");
        scheduleMap();
    }
});
canvas.addEventListener("pointerup", (event) => {
    if (!drag || drag.id !== event.pointerId) return;
    if (!drag.moved) {
        const bounds = canvas.getBoundingClientRect();
        const x = event.clientX - bounds.left;
        const z = event.clientY - bounds.top;
        let closest = null;
        let distance = 18;
        for (const target of mapTargets()) {
            const targetX = (target.x - mapView.x) * mapView.scale + mapWidth / 2;
            const targetZ = (target.z - mapView.z) * mapView.scale + mapHeight / 2;
            const delta = Math.hypot(targetX - x, targetZ - z);
            if (delta < distance) { closest = target; distance = delta; }
        }
        if (closest) selectTarget(closest.id);
    }
    canvas.releasePointerCapture(event.pointerId);
    drag = null;
    canvas.classList.remove("dragging");
});
canvas.addEventListener("pointercancel", () => { drag = null; canvas.classList.remove("dragging"); });
canvas.addEventListener("pointerleave", () => { if (!drag) element("cursorCoordinates").textContent = "X — · Z —"; });
element("zoomIn").addEventListener("click", () => zoomMap(1.35));
element("zoomOut").addEventListener("click", () => zoomMap(1 / 1.35));
element("fitTargets").addEventListener("click", fitMap);
element("centerCamera").addEventListener("click", () => {
    if (!state?.camera) { showFeedback("当前没有摄影机位置"); return; }
    mapView.followCamera = true;
    mapView.x = state.camera.x;
    mapView.z = state.camera.z;
    scheduleMap();
});
new ResizeObserver(resizeMap).observe(element("mapContainer"));
window.addEventListener("resize", resizeMap);
document.addEventListener("visibilitychange", () => { if (!document.hidden) refreshState(); });
setInterval(() => { refreshState(); updateCountdown(); }, 1000);
renderTargets();
resizeMap();
refreshState();
