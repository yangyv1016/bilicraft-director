package dev.bilicraft.director.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Observes vanilla status packets per connection, without altering downloads or resource reloads. */
public final class ResourcePackTracker {
    public interface Access {
        ResourcePackTracker bilicraftDirector$resourcePackTracker();
    }

    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public void accepted(UUID id) { pending.add(id); }

    public void finished(UUID id) { pending.remove(id); }

    public boolean pending() { return !pending.isEmpty(); }
}
