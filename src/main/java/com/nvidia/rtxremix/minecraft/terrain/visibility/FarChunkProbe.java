/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Vanilla's line-of-sight probe for sections far from the camera, without its allocations.
 *
 * <p>Vanilla's visibility walk lets a section more than 60 blocks from the camera propagate
 * to a neighbour only if a ray from a corner of the section toward the camera passes
 * exclusively through sections that exist and are already in the visible graph. It holds the
 * ray in two JOML vectors per probe and allocates a block position per step for the lookup.
 * This reproduces the same arithmetic in the same order on doubles, through JOML's own
 * {@code fma}, so the probed positions and the verdict are bit-identical to vanilla's whatever
 * JOML's fused-multiply option says.
 */
public final class FarChunkProbe {

    /** Answers whether the section holding a block position exists and is in the graph. */
    @FunctionalInterface
    public interface GraphLookup {
        boolean contains(int blockX, int blockY, int blockZ);
    }

    /** Squared distance at which the ray is close enough to the camera to stop. */
    private static final double NEAR_CAMERA_DISTANCE_SQUARED = 3600.0;

    private FarChunkProbe() {}

    /**
     * Runs the probe from the section at {@code origin}, the section propagating, toward a
     * neighbour across a face on {@code axis}, back toward the camera at ({@code cameraX},
     * {@code cameraY}, {@code cameraZ}).
     *
     * @param originX              block origin of the propagating section, per axis
     * @param cameraSectionCenterX camera section origin plus eight, per axis; picks the corner
     *                             of the section the ray starts from
     * @param step                 ray advance per iteration in blocks
     * @param worldMaxY            highest block Y of the world, inclusive, as the view area
     *                             gives it; a point above it or below {@code worldMinY} leaves
     *                             the world, which passes
     * @param worldMinY            lowest block Y of the world, inclusive
     * @return true when every probed section is in the graph or the ray left the world
     */
    public static boolean reachesCamera(
            int originX, int originY, int originZ,
            Direction.Axis axis,
            int cameraSectionCenterX, int cameraSectionCenterY, int cameraSectionCenterZ,
            double cameraX, double cameraY, double cameraZ,
            double step,
            int worldMaxY, int worldMinY,
            GraphLookup graph) {
        boolean maxX = axis == Direction.Axis.X ? cameraSectionCenterX > originX : cameraSectionCenterX < originX;
        boolean maxY = axis == Direction.Axis.Y ? cameraSectionCenterY > originY : cameraSectionCenterY < originY;
        boolean maxZ = axis == Direction.Axis.Z ? cameraSectionCenterZ > originZ : cameraSectionCenterZ < originZ;
        double pointX = originX + (maxX ? 16 : 0);
        double pointY = originY + (maxY ? 16 : 0);
        double pointZ = originZ + (maxZ ? 16 : 0);

        // new Vector3d(camera).sub(point).normalize().mul(step)
        double stepX = cameraX - pointX;
        double stepY = cameraY - pointY;
        double stepZ = cameraZ - pointZ;
        double invLength = org.joml.Math.invsqrt(
                org.joml.Math.fma(stepX, stepX, org.joml.Math.fma(stepY, stepY, stepZ * stepZ)));
        stepX = stepX * invLength * step;
        stepY = stepY * invLength * step;
        stepZ = stepZ * invLength * step;

        while (distanceSquared(pointX, pointY, pointZ, cameraX, cameraY, cameraZ) > NEAR_CAMERA_DISTANCE_SQUARED) {
            pointX = pointX + stepX;
            pointY = pointY + stepY;
            pointZ = pointZ + stepZ;
            if (pointY > (double) worldMaxY || pointY < (double) worldMinY) {
                return true;
            }
            if (!graph.contains(Mth.floor(pointX), Mth.floor(pointY), Mth.floor(pointZ))) {
                return false;
            }
        }
        return true;
    }

    /** JOML's {@code Vector3d.distanceSquared(x, y, z)}, in its evaluation order. */
    private static double distanceSquared(double pointX, double pointY, double pointZ,
                                          double cameraX, double cameraY, double cameraZ) {
        double dx = pointX - cameraX;
        double dy = pointY - cameraY;
        double dz = pointZ - cameraZ;
        return org.joml.Math.fma(dx, dx, org.joml.Math.fma(dy, dy, dz * dz));
    }
}
