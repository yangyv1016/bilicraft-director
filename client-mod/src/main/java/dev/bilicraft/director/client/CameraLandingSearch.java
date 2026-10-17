package dev.bilicraft.director.client;

import java.util.ArrayList;

import static dev.bilicraft.director.client.CameraTerrainAvoidance.ANGLES;
import static dev.bilicraft.director.client.CameraTerrainAvoidance.HEIGHTS;
import static dev.bilicraft.director.client.CameraTerrainAvoidance.clearPose;
import static dev.bilicraft.director.client.CameraTerrainAvoidance.offset;

/** Incremental TP-only landing search; every candidate is relative to the original saved shot. */
final class CameraLandingSearch {
    private final CameraMotion.Shot intended;
    private double[] heights;
    private int candidateIndex;
    private long nextPollAt;
    private CameraMotion.Point lastReturnedSurface;
    private CameraMotion.Point refinementSafe;
    private double refinementAngle;
    private double refinementLower;
    private double refinementUpper;

    CameraLandingSearch(CameraMotion.Shot intended) {
        this.intended = intended;
    }

    CameraMotion.Shot intended() {
        return intended;
    }

    CameraMotion.Point poll(CameraTerrainAvoidance.Terrain terrain, long now) {
        if (now < nextPollAt) return null;
        nextPollAt = now + 250;
        if (heights == null) {
            ArrayList<Double> levels = new ArrayList<>();
            levels.add(0.0);
            double maximumLift = Math.max(0, terrain.maxY() - intended.position().y());
            for (double height : HEIGHTS) {
                if (height >= maximumLift) break;
                levels.add(height);
            }
            if (maximumLift > 0) levels.add(maximumLift);
            heights = levels.stream().mapToDouble(Double::doubleValue).toArray();
        }
        boolean verifyRefinement = refinementSafe != null;
        boolean surfaceChecked = false;
        for (int probes = 0; probes < 16;) {
            if (refinementSafe != null) {
                // A refinement spanning ticks must not reuse a position whose terrain has since changed.
                if (verifyRefinement) {
                    probes++;
                    verifyRefinement = false;
                    if (!clearPose(refinementSafe, intended.lookAt(), terrain)) refinementSafe = null;
                    continue;
                }
                if (refinementUpper - refinementLower <= 0.5) {
                    CameraMotion.Point result = refinementSafe;
                    refinementSafe = null;
                    return result;
                }
                double middle = (refinementLower + refinementUpper) / 2;
                CameraMotion.Point point = offset(intended, refinementAngle, middle);
                probes++;
                if (clearPose(point, intended.lookAt(), terrain)) {
                    refinementUpper = middle;
                    refinementSafe = point;
                } else refinementLower = middle;
                continue;
            }
            if (candidateIndex == heights.length * ANGLES.length) {
                // Unknown chunks and a depleted query budget can become usable on a later pass.
                candidateIndex = 0;
                lastReturnedSurface = null;
                return null;
            }
            if (candidateIndex >= ANGLES.length && !surfaceChecked) {
                // A destination chunk can arrive before its neighbors; recheck this quick path on the next poll.
                surfaceChecked = true;
                CameraMotion.Point surface = terrain.surface(intended.position());
                if (surface != null) {
                    CameraMotion.Point point = new CameraMotion.Point(intended.position().x(),
                            Math.min(terrain.maxY(), Math.max(intended.position().y(),
                                    surface.y() + Math.max(10, terrain.minimumGroundClearance()))),
                            intended.position().z());
                    if (point.equals(lastReturnedSurface)) continue;
                    probes++;
                    if (clearPose(point, intended.lookAt(), terrain)) {
                        lastReturnedSurface = point;
                        return point;
                    }
                }
                continue;
            }
            int heightIndex = candidateIndex / ANGLES.length;
            double angle = ANGLES[candidateIndex % ANGLES.length];
            candidateIndex++;
            CameraMotion.Point point = offset(intended, angle, heights[heightIndex]);
            probes++;
            if (!clearPose(point, intended.lookAt(), terrain)) continue;
            if (heightIndex == 0) return point;
            refinementSafe = point;
            refinementAngle = angle;
            refinementLower = heights[heightIndex - 1];
            refinementUpper = heights[heightIndex];
        }
        return null;
    }
}
