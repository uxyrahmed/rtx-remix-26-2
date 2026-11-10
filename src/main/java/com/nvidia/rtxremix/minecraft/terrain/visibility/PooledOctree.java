/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import com.nvidia.rtxremix.minecraft.mixin.FrustumAccessor;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Vanilla's visible-section {@link Octree}, rebuilt on pooled nodes.
 *
 * <p>Every full visibility walk builds a new octree holding each section it reached: a leaf
 * per section plus the branches and bounding boxes above them, tens of thousands of objects
 * several times a second at a long render distance, on the visibility thread. This octree
 * keeps its nodes across walks and rebinds them, so a walk allocates nothing once the pools
 * have grown to the largest tree seen.
 *
 * <p>The structure is vanilla's to the bit: the same root bounds, the same child slot for
 * every section (the camera-relative axis sorting included), the same frustum tests in the
 * same order, and the same visit order, so frustum culling, the near-section flag and the
 * visible list built from a visit are exactly what vanilla's octree produces. The branch
 * frustum test is vanilla's {@code cubeInFrustum(BoundingBox)} evaluated on the branch's
 * integer bounds without the box object.
 *
 * <p>Not thread safe, like vanilla's: one thread builds it, and the render thread reads and
 * extends it once the walk that built it has published.
 */
public final class PooledOctree extends Octree {

    private final ArrayList<Branch> branches = new ArrayList<>();
    private final ArrayList<Leaf> leaves = new ArrayList<>();
    private int branchesUsed;
    private int leavesUsed;
    private Branch root;
    private int cameraCenterX;
    private int cameraCenterY;
    private int cameraCenterZ;

    public PooledOctree() {
        // The base octree is never consulted; every public operation is answered here.
        super(SectionPos.of(0, 0, 0), 0, 1, 0);
    }

    /**
     * Empties the octree and lays out its root as vanilla's constructor does for the same
     * arguments. Nodes of the previous tree are rebound, not released.
     */
    public void reset(SectionPos cameraSection, int renderDistance, int sectionsPerChunk, int minBlockY) {
        int visibleAreaDiameterInSections = renderDistance * 2 + 1;
        int boundingBoxSizeInSections = Mth.smallestEncompassingPowerOfTwo(visibleAreaDiameterInSections);
        int distanceToBBEdgeInBlocks = renderDistance * 16;
        int originX = cameraSection.minBlockX();
        int originY = cameraSection.minBlockY();
        int originZ = cameraSection.minBlockZ();
        cameraCenterX = originX + 8;
        cameraCenterY = originY + 8;
        cameraCenterZ = originZ + 8;
        int minX = originX - distanceToBBEdgeInBlocks;
        int maxX = minX + boundingBoxSizeInSections * 16 - 1;
        int minY = boundingBoxSizeInSections >= sectionsPerChunk ? minBlockY : originY - distanceToBBEdgeInBlocks;
        int maxY = minY + boundingBoxSizeInSections * 16 - 1;
        int minZ = originZ - distanceToBBEdgeInBlocks;
        int maxZ = minZ + boundingBoxSizeInSections * 16 - 1;

        for (int i = 0; i < leavesUsed; i++) {
            leaves.get(i).section = null;
        }
        branchesUsed = 0;
        leavesUsed = 0;
        root = branch(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean add(SectionRenderDispatcher.RenderSection section) {
        return root.add(section);
    }

    @Override
    public void visitNodes(Octree.OctreeVisitor visitor, Frustum frustum, int closeDistance) {
        root.visitNodes(visitor, false, frustum, 0, closeDistance, true);
    }

    private boolean isClose(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int closeDistance) {
        return cameraCenterX > minX - closeDistance
            && cameraCenterX < maxX + closeDistance
            && cameraCenterY > minY - closeDistance
            && cameraCenterY < maxY + closeDistance
            && cameraCenterZ > minZ - closeDistance
            && cameraCenterZ < maxZ + closeDistance;
    }

    private Branch branch(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Branch branch;
        if (branchesUsed < branches.size()) {
            branch = branches.get(branchesUsed);
        } else {
            branch = new Branch();
            branches.add(branch);
        }
        branchesUsed++;
        branch.bind(minX, minY, minZ, maxX, maxY, maxZ);
        return branch;
    }

    private Leaf leaf(SectionRenderDispatcher.RenderSection section) {
        Leaf leaf;
        if (leavesUsed < leaves.size()) {
            leaf = leaves.get(leavesUsed);
        } else {
            leaf = new Leaf();
            leaves.add(leaf);
        }
        leavesUsed++;
        leaf.section = section;
        return leaf;
    }

    /**
     * Vanilla's {@code cubeInFrustum(BoundingBox)}: the box grows by one block on its maximum
     * sides, both corners are taken relative to the frustum's camera in double and narrowed to
     * float, and the frustum classifies the box.
     */
    private static int cubeInFrustum(Frustum frustum, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        FrustumAccessor accessor = (FrustumAccessor) frustum;
        double camX = accessor.rtxremix$getX();
        double camY = accessor.rtxremix$getY();
        double camZ = accessor.rtxremix$getZ();
        float x1 = (float) ((double) minX - camX);
        float y1 = (float) ((double) minY - camY);
        float z1 = (float) ((double) minZ - camZ);
        float x2 = (float) ((double) (maxX + 1) - camX);
        float y2 = (float) ((double) (maxY + 1) - camY);
        float z2 = (float) ((double) (maxZ + 1) - camZ);
        return accessor.rtxremix$getFrustumIntersection().intersectAab(x1, y1, z1, x2, y2, z2);
    }

    private final class Branch implements Octree.Node {
        private final Octree.@Nullable Node[] nodes = new Octree.Node[8];
        private int minX, minY, minZ, maxX, maxY, maxZ;
        private int bbCenterX, bbCenterY, bbCenterZ;
        private int xShift, yShift, zShift;
        private boolean cameraXDiffNegative, cameraYDiffNegative, cameraZDiffNegative;

        void bind(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            Arrays.fill(nodes, null);
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            bbCenterX = minX + (maxX - minX + 1) / 2;
            bbCenterY = minY + (maxY - minY + 1) / 2;
            bbCenterZ = minZ + (maxZ - minZ + 1) / 2;
            int cameraXDiff = cameraCenterX - bbCenterX;
            int cameraYDiff = cameraCenterY - bbCenterY;
            int cameraZDiff = cameraCenterZ - bbCenterZ;
            sortAxes(Math.abs(cameraXDiff), Math.abs(cameraYDiff), Math.abs(cameraZDiff));
            cameraXDiffNegative = cameraXDiff < 0;
            cameraYDiffNegative = cameraYDiff < 0;
            cameraZDiffNegative = cameraZDiff < 0;
        }

        /** Vanilla's axis sorting: the child slot weight of each axis, largest camera offset first. */
        private void sortAxes(int absXDiff, int absYDiff, int absZDiff) {
            if (absXDiff > absYDiff && absXDiff > absZDiff) {
                if (absYDiff > absZDiff) {
                    setShifts(4, 2, 1);
                } else {
                    setShifts(4, 1, 2);
                }
            } else if (absYDiff > absXDiff && absYDiff > absZDiff) {
                if (absXDiff > absZDiff) {
                    setShifts(2, 4, 1);
                } else {
                    setShifts(1, 4, 2);
                }
            } else if (absXDiff > absYDiff) {
                setShifts(2, 1, 4);
            } else {
                setShifts(1, 2, 4);
            }
        }

        private void setShifts(int xShift, int yShift, int zShift) {
            this.xShift = xShift;
            this.yShift = yShift;
            this.zShift = zShift;
        }

        boolean add(SectionRenderDispatcher.RenderSection section) {
            long sectionNode = section.getSectionNode();
            boolean sectionXDiffNegative = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)) - bbCenterX < 0;
            boolean sectionYDiffNegative = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)) - bbCenterY < 0;
            boolean sectionZDiffNegative = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)) - bbCenterZ < 0;
            int nodeIndex = 0;
            if (sectionXDiffNegative != cameraXDiffNegative) {
                nodeIndex += xShift;
            }
            if (sectionYDiffNegative != cameraYDiffNegative) {
                nodeIndex += yShift;
            }
            if (sectionZDiffNegative != cameraZDiffNegative) {
                nodeIndex += zShift;
            }

            Octree.Node child = nodes[nodeIndex];
            if (maxX - minX + 1 == 32) {
                if (child == null) {
                    nodes[nodeIndex] = leaf(section);
                    return true;
                }
                // Vanilla replaces the leaf with one for this section and reports nothing new.
                ((Leaf) child).section = section;
                return false;
            }
            if (child != null) {
                return ((Branch) child).add(section);
            }

            int childMinX = sectionXDiffNegative ? minX : bbCenterX;
            int childMaxX = sectionXDiffNegative ? bbCenterX - 1 : maxX;
            int childMinY = sectionYDiffNegative ? minY : bbCenterY;
            int childMaxY = sectionYDiffNegative ? bbCenterY - 1 : maxY;
            int childMinZ = sectionZDiffNegative ? minZ : bbCenterZ;
            int childMaxZ = sectionZDiffNegative ? bbCenterZ - 1 : maxZ;
            Branch branch = branch(childMinX, childMinY, childMinZ, childMaxX, childMaxY, childMaxZ);
            nodes[nodeIndex] = branch;
            return branch.add(section);
        }

        @Override
        public void visitNodes(Octree.OctreeVisitor visitor, boolean skipFrustumCheck, Frustum frustum,
                               int depth, int closeDistance, boolean isClose) {
            boolean isVisible = skipFrustumCheck;
            if (!skipFrustumCheck) {
                int checkResult = cubeInFrustum(frustum, minX, minY, minZ, maxX, maxY, maxZ);
                skipFrustumCheck = checkResult == -2;
                isVisible = checkResult == -2 || checkResult == -1;
            }

            if (isVisible) {
                isClose = isClose && isClose(minX, minY, minZ, maxX, maxY, maxZ, closeDistance);
                visitor.visit(this, skipFrustumCheck, depth, isClose);

                for (Octree.Node node : nodes) {
                    if (node != null) {
                        node.visitNodes(visitor, skipFrustumCheck, frustum, depth + 1, closeDistance, isClose);
                    }
                }
            }
        }

        @Override
        public SectionRenderDispatcher.@Nullable RenderSection getSection() {
            return null;
        }

        @Override
        public AABB getAABB() {
            return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }
    }

    private final class Leaf implements Octree.Node {
        private SectionRenderDispatcher.RenderSection section;

        @Override
        public void visitNodes(Octree.OctreeVisitor visitor, boolean skipFrustumCheck, Frustum frustum,
                               int depth, int closeDistance, boolean isClose) {
            AABB boundingBox = section.getBoundingBox();
            if (skipFrustumCheck || frustum.isVisible(section.getBoundingBox())) {
                isClose = isClose
                        && isClose(boundingBox.minX, boundingBox.minY, boundingBox.minZ,
                                boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ, closeDistance);
                visitor.visit(this, skipFrustumCheck, depth, isClose);
            }
        }

        @Override
        public SectionRenderDispatcher.RenderSection getSection() {
            return section;
        }

        @Override
        public AABB getAABB() {
            return section.getBoundingBox();
        }
    }
}
