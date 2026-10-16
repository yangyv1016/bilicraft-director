package dev.bilicraft.director.client;

/** Game-thread request ownership, cooldown and session usage. Transport completions never choose a target themselves. */
final class AiStrategySession {
    static final long MIN_REQUEST_INTERVAL_MILLIS = 60_000;
    record Request(long epoch, long generation, long settingsRevision, String world, AiSettings.Provider provider) { }

    private long epoch;
    private Request active;
    private long nextRequestAt;
    long requests;
    long promptTokens;
    long completionTokens;
    long totalTokens;
    long unreportedRequests;
    String status = "AI策略未启用；使用本地策略";

    Request begin(long now, long generation, long settingsRevision, String world, AiSettings.Provider provider) {
        if (active != null || now < nextRequestAt) {
            return null;
        }
        active = new Request(++epoch, generation, settingsRevision, world, provider);
        nextRequestAt = now + MIN_REQUEST_INTERVAL_MILLIS;
        requests++;
        status = "正在请求文本选点；当前镜头继续";
        return active;
    }

    boolean finish(Request request, AiHttpClient.Completion completion) {
        if (completion != null && completion.usage().reported()) {
            promptTokens += completion.usage().promptTokens();
            completionTokens += completion.usage().completionTokens();
            totalTokens += completion.usage().totalTokens();
        } else {
            unreportedRequests++;
        }
        if (!request.equals(active)) {
            return false;
        }
        active = null;
        return true;
    }

    void invalidate() {
        if (active != null) {
            status = "AI结果已取消；不启动或改写人工队列";
        }
        ++epoch;
        active = null;
    }

    boolean pending() {
        return active != null;
    }

    long nextRequestInSeconds(long now) {
        return Math.max(0, (nextRequestAt - now + 999) / 1_000);
    }
}
