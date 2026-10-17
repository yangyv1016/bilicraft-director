package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;

/** Main-thread terrain queries with a bounded block-read budget for one planning pass. */
final class CameraTerrainWorld implements CameraTerrainAvoidance.Terrain {
    private static final double VIEW_DISTANCE = 8;
    private final ClientWorld world;
    private final Box localBox;
    private final Vec3d eyeOffset;
    private final List<Message.RegionBounds> exclusions;
    private final double minimumGroundClearance;
    private long remainingScans = 8_192;

    CameraTerrainWorld(ClientPlayerEntity player, List<Message.RegionBounds> exclusions) {
        this(player, exclusions, 0);
    }

    CameraTerrainWorld(ClientPlayerEntity player, List<Message.RegionBounds> exclusions, double minimumGroundClearance) {
        world = MinecraftClient.getInstance().world;
        Vec3d feet = player.getEntityPos();
        localBox = player.getBoundingBox().offset(feet.multiply(-1)).expand(0.1);
        eyeOffset = player.getEyePos().subtract(feet);
        this.exclusions = exclusions;
        this.minimumGroundClearance = minimumGroundClearance;
    }

    @Override
    public CameraTerrainAvoidance.Clearance space(CameraMotion.Point feet) {
        Vec3d position = new Vec3d(feet.x(), feet.y(), feet.z());
        Box occupied = localBox.offset(position);
        if (!withinLimits(occupied) || intersectsExclusion(position, position)) {
            return CameraTerrainAvoidance.Clearance.BLOCKED;
        }
        if (!reserveVolume(occupied) || !chunksLoaded(occupied.expand(1.0000001))) {
            return CameraTerrainAvoidance.Clearance.UNKNOWN;
        }
        // A null entity selects ShapeContext.absent(), independently of spectator/noClip.
        if (!world.isBlockSpaceEmpty(null, occupied)) return CameraTerrainAvoidance.Clearance.BLOCKED;
        if (minimumGroundClearance > 0) {
            CameraMotion.Point surface = surface(feet);
            if (surface == null) return CameraTerrainAvoidance.Clearance.UNKNOWN;
            if (feet.y() < surface.y() + minimumGroundClearance) return CameraTerrainAvoidance.Clearance.BLOCKED;
        }
        return CameraTerrainAvoidance.Clearance.CLEAR;
    }

    @Override
    public CameraTerrainAvoidance.Clearance path(CameraMotion.Point from, CameraMotion.Point to) {
        Vec3d start = new Vec3d(from.x(), from.y(), from.z());
        Vec3d end = new Vec3d(to.x(), to.y(), to.z());
        Box startBox = localBox.offset(start);
        Box endBox = localBox.offset(end);
        if (!withinLimits(startBox) || !withinLimits(endBox) || intersectsExclusion(start, end)) {
            return CameraTerrainAvoidance.Clearance.BLOCKED;
        }
        Box swept = startBox.stretch(end.subtract(start));
        if (!reserveVolume(swept) || !chunksLoaded(swept.expand(1.0000001))) {
            return CameraTerrainAvoidance.Clearance.UNKNOWN;
        }
        for (VoxelShape shape : world.getBlockCollisions(null, swept)) {
            for (Box obstacle : shape.getBoundingBoxes()) {
                Box expanded = new Box(obstacle.minX - localBox.maxX, obstacle.minY - localBox.maxY,
                        obstacle.minZ - localBox.maxZ, obstacle.maxX - localBox.minX,
                        obstacle.maxY - localBox.minY, obstacle.maxZ - localBox.minZ);
                if (expanded.contains(start) || expanded.contains(end) || expanded.raycast(start, end).isPresent()) {
                    return CameraTerrainAvoidance.Clearance.BLOCKED;
                }
            }
        }
        return CameraTerrainAvoidance.Clearance.CLEAR;
    }

    @Override
    public CameraTerrainAvoidance.Clearance view(CameraMotion.Point feet, CameraMotion.Point lookAt) {
        Vec3d eye = new Vec3d(feet.x(), feet.y(), feet.z()).add(eyeOffset);
        Vec3d end = new Vec3d(lookAt.x(), lookAt.y(), lookAt.z());
        Vec3d delta = end.subtract(eye);
        double distance = delta.length();
        // Only foreground occlusion affects the shot; remote map anchors may be inside buildings or terrain.
        if (distance > VIEW_DISTANCE) end = eye.add(delta.multiply(VIEW_DISTANCE / distance));
        long steps = 4L + Math.abs((long) MathHelper.floor(end.x) - MathHelper.floor(eye.x))
                + Math.abs((long) MathHelper.floor(end.y) - MathHelper.floor(eye.y))
                + Math.abs((long) MathHelper.floor(end.z) - MathHelper.floor(eye.z));
        if (steps > remainingScans) return CameraTerrainAvoidance.Clearance.UNKNOWN;
        remainingScans -= steps;
        if (!chunksLoaded(new Box(eye, end).expand(1))) return CameraTerrainAvoidance.Clearance.UNKNOWN;
        return world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.VISUAL,
                RaycastContext.FluidHandling.NONE, ShapeContext.absent())).getType() == HitResult.Type.MISS
                ? CameraTerrainAvoidance.Clearance.CLEAR : CameraTerrainAvoidance.Clearance.BLOCKED;
    }

    @Override
    public CameraMotion.Point surface(CameraMotion.Point feet) {
        int x = MathHelper.floor(feet.x());
        int z = MathHelper.floor(feet.z());
        if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4) || remainingScans < 1) return null;
        WorldChunk chunk = world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        // Never populate a missing heightmap: that would scan the whole chunk outside this pass's budget.
        if (chunk == null || !chunk.hasHeightmap(Heightmap.Type.WORLD_SURFACE)) return null;
        remainingScans--;
        return new CameraMotion.Point(feet.x(), chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15), feet.z());
    }

    @Override
    public double minimumGroundClearance() {
        return minimumGroundClearance;
    }

    @Override
    public double maxY() {
        return world.getTopYInclusive() - localBox.maxY;
    }

    private boolean withinLimits(Box occupied) {
        return occupied.minY >= world.getBottomY() && occupied.maxY <= world.getTopYInclusive()
                && world.getWorldBorder().contains(occupied);
    }

    private boolean intersectsExclusion(Vec3d from, Vec3d to) {
        for (Message.RegionBounds region : exclusions) {
            Box expanded = new Box(region.minX() - localBox.maxX, Math.min(from.y, to.y) - 1,
                    region.minZ() - localBox.maxZ, region.maxX() - localBox.minX,
                    Math.max(from.y, to.y) + 1, region.maxZ() - localBox.minZ);
            if (expanded.contains(from) || expanded.contains(to) || expanded.raycast(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean reserveVolume(Box occupied) {
        // The vanilla collision iterator also visits neighboring blocks with protruding shapes.
        long width = (long) MathHelper.floor(occupied.maxX + 1.0E-7) - MathHelper.floor(occupied.minX - 1.0E-7) + 3;
        long height = (long) MathHelper.floor(occupied.maxY + 1.0E-7) - MathHelper.floor(occupied.minY - 1.0E-7) + 3;
        long depth = (long) MathHelper.floor(occupied.maxZ + 1.0E-7) - MathHelper.floor(occupied.minZ - 1.0E-7) + 3;
        double scans = (double) width * height * depth;
        if (scans > remainingScans) return false;
        remainingScans -= (long) scans;
        return true;
    }

    private boolean chunksLoaded(Box scanned) {
        int minX = MathHelper.floor(scanned.minX) >> 4;
        int maxX = MathHelper.floor(scanned.maxX) >> 4;
        int minZ = MathHelper.floor(scanned.minZ) >> 4;
        int maxZ = MathHelper.floor(scanned.maxZ) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.getChunkManager().isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }
}
