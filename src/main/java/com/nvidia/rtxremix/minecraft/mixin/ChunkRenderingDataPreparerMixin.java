/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityWalkSwitches;
import com.nvidia.rtxremix.minecraft.terrain.visibility.TimeSlicedWorkQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.FarChunkProbe;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.google.common.collect.Queues;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over vanilla's section visibility work on three fronts: it runs the full visibility
 * walk on a dedicated low-priority executor so long view distances cannot starve terrain
 * capture or the render thread, it replaces the walk with an allocation-free port that
 * produces vanilla's visible set exactly, and it time-slices vanilla's incremental
 * propagation on the render thread through a per-frame budget so a chunk-load burst cannot
 * stall a frame.
 *
 * <p>Frustum culling of the visible set is vanilla's own octree, built on pooled nodes
 * ({@code RenderableChunksMixin}). The mod also tells the terrain manager which sections
 * became empty, because a section that empties is dropped from vanilla's visible set without
 * the recompile that used to report it, and labels each phase for the profiler.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class ChunkRenderingDataPreparerMixin {

    /** Vanilla's neighbour order; the walk must visit directions in this order. */
    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    /** Section distance from the camera beyond which vanilla probes line of sight. */
    @Shadow
    @Final
    private static int MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE;

    /** Ray-march step of vanilla's far-section probe, {@code ceil(sqrt(3) * 16)} blocks. */
    @Shadow
    @Final
    private static double CEILED_SECTION_DIAGONAL;

    @Shadow
    private @Nullable ViewArea viewArea;

    /** Vanilla's neighbour lookup: null beyond the view distance or the world's height. */
    @Shadow
    protected abstract SectionRenderDispatcher.@Nullable RenderSection getRelativeFrom(
            long cameraSectionNode, SectionRenderDispatcher.RenderSection renderSection, Direction direction);

    private static long rtxremix$lastVisibleSetSignature;
    private static final ExecutorService rtxremix$visibilityExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "RTX Remix chunk visibility");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });

    /**
     * Per-frame budget for vanilla's incremental visibility propagation. After a chunk-load
     * burst the propagation can seed a walk of thousands of sections; unbudgeted, one such
     * walk cost 25 ms on the render thread. The poll floor keeps the fringe advancing even
     * when the frame is already late.
     */
    private static final long rtxremix$PROPAGATION_BUDGET_NANOS = 1_500_000L;
    private static final int rtxremix$PROPAGATION_MINIMUM_POLLS = 64;

    /**
     * Seeds and partially walked sections carried across frames. Its entries belong to the
     * visibility state that produced them, so a publish or a view area swap empties it.
     *
     * <p>Created by {@link #rtxremix$createPropagationQueue} rather than a field initializer:
     * JDK 25's javac gives the implicit constructor's return the line of the last static
     * initializer below, which puts this initializer inside the line range Mixin takes for
     * the constructor body, so Mixin would silently drop it.
     */
    private TimeSlicedWorkQueue<Object> rtxremix$propagationQueue;
    /** Seed queue of the state whose entries {@link #rtxremix$propagationQueue} holds. */
    private BlockingQueue<?> rtxremix$propagationSeeds;

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$storageScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$frustumScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$prepareScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$graphScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$seedScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$walkScope = new ThreadLocal<>();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$createPropagationQueue(CallbackInfo ci) {
        rtxremix$propagationQueue = new TimeSlicedWorkQueue<>(
                rtxremix$PROPAGATION_BUDGET_NANOS, rtxremix$PROPAGATION_MINIMUM_POLLS, System::nanoTime);
    }

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void rtxremix$storageHead(ViewArea viewArea, CallbackInfo ci) {
        rtxremix$open(rtxremix$storageScope, "ChunkVisibility.setStorage");
    }

    /**
     * The initial empty state vanilla builds next takes the current buffers; size them for the
     * new view area first. Vanilla has already waited for a running full walk here, so no walk
     * is filling the buffers being replaced.
     */
    @Inject(
            method = "waitAndReset",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;viewArea:Lnet/minecraft/client/renderer/ViewArea;", opcode = Opcodes.PUTFIELD))
    private void rtxremix$configureBuffers(ViewArea viewArea, CallbackInfo ci) {
        VisibilityGraphBuffers.configure(viewArea != null ? viewArea.size() : 0);
    }

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void rtxremix$storageReturn(ViewArea viewArea, CallbackInfo ci) {
        rtxremix$propagationQueue.clear();
        rtxremix$propagationSeeds = null;
        rtxremix$close(rtxremix$storageScope);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"))
    private void rtxremix$frustumHead(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> visibleSections,
            List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$frustumScope, "ChunkVisibility.applyFrustum", rtxremix$sizeOf(visibleSections));
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void rtxremix$frustumReturn(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> visibleSections,
            List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
            CallbackInfo ci) {
        rtxremix$captureCount(rtxremix$frustumScope, rtxremix$sizeOf(visibleSections));
        rtxremix$close(rtxremix$frustumScope);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$prepareHead(
            CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState, CallbackInfo ci) {
        rtxremix$open(rtxremix$prepareScope, "ChunkVisibility.prepare");
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void rtxremix$prepareReturn(
            CameraRenderState camera, int fov, ChunkLoadingRenderState chunkLoadingRenderState, CallbackInfo ci) {
        rtxremix$close(rtxremix$prepareScope);
    }

    /**
     * A full walk is about to construct its state: hand it the other buffer set when it starts,
     * and run it on the low-priority visibility executor while Remix renders. The rotation runs
     * for vanilla's walk too, since the reused buffers are what its state is made of.
     */
    @WrapOperation(
            method = "scheduleFullUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;runAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> rtxremix$runFullUpdate(
            Runnable fullUpdate, Executor executor, Operation<CompletableFuture<Void>> original) {
        Runnable onFreshBuffers = () -> {
            VisibilityGraphBuffers.rotate();
            fullUpdate.run();
        };
        return original.call(onFreshBuffers, RtxRemixMod.isRtxEnabled() ? rtxremix$visibilityExecutor : executor);
    }

    @Inject(method = "runPartialUpdate", at = @At("HEAD"))
    private void rtxremix$graphHead(CameraRenderState camera, LongSet loadedExpectedChunks, CallbackInfo ci) {
        rtxremix$open(rtxremix$graphScope, "ChunkVisibility.rebuildGraph");
    }

    @Inject(method = "runPartialUpdate", at = @At("RETURN"))
    private void rtxremix$graphReturn(CameraRenderState camera, LongSet loadedExpectedChunks, CallbackInfo ci) {
        rtxremix$budgetHit(rtxremix$graphScope, rtxremix$propagationQueue.budgetHit());
        rtxremix$close(rtxremix$graphScope);
    }

    // Vanilla only propagates when new seeds arrived. Sections left over from a budgeted
    // slice must keep draining even on frames with no new seeds.
    @Redirect(
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/BlockingQueue;isEmpty()Z",
                    ordinal = 0
            ))
    private boolean rtxremix$noPropagationWork(BlockingQueue<?> seeds) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return seeds.isEmpty();
        }
        rtxremix$dropForeignPropagationWork(seeds);
        return seeds.isEmpty() && !rtxremix$propagationQueue.hasPendingWork();
    }

    // Vanilla drains the seeds into a fresh deque and walks it to completion. Handing it the
    // persistent sliced queue instead bounds the walk to this frame's budget and resumes it
    // from the same entries next frame.
    @Redirect(
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayDeque()Ljava/util/ArrayDeque;"
            ))
    private ArrayDeque<Object> rtxremix$propagationWorkQueue() {
        if (!RtxRemixMod.isRtxEnabled()) {
            rtxremix$propagationQueue.clear();
            return Queues.newArrayDeque();
        }
        rtxremix$propagationQueue.beginSlice();
        return rtxremix$propagationQueue;
    }

    /**
     * Leftover entries index into the state that produced them; a publish invalidates them.
     * Each state owns its seed queue, so the queue vanilla is about to drain identifies the
     * state it loaded for this frame. Re-reading the published state here instead could
     * observe a newer one and leave this frame's leftovers tagged as that state's own.
     */
    private void rtxremix$dropForeignPropagationWork(BlockingQueue<?> seeds) {
        if (rtxremix$propagationSeeds != seeds) {
            rtxremix$propagationQueue.clear();
            rtxremix$propagationSeeds = seeds;
        }
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("HEAD"))
    private void rtxremix$seedHead(BlockPos cameraPosition, Queue<Node> queue, CallbackInfo ci) {
        rtxremix$open(rtxremix$seedScope, "ChunkVisibility.seedQueue", rtxremix$sizeOf(queue));
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("RETURN"))
    private void rtxremix$seedReturn(BlockPos cameraPosition, Queue<Node> queue, CallbackInfo ci) {
        rtxremix$close(rtxremix$seedScope);
    }

    /**
     * A section that empties leaves vanilla's visible set without the recompile whose empty
     * mesh used to report it, so the terrain manager is told here instead. Only a section
     * vanilla has compiled can hold Remix terrain; a chunk arriving with empty sections reports
     * those too, and they have nothing to remove. The terrain manager validates the live
     * section before it removes anything.
     */
    @Inject(method = "updateEmptySections", at = @At("HEAD"))
    private void rtxremix$reportEmptiedSections(LongOpenHashSet added, LongOpenHashSet removed, CallbackInfo ci) {
        ViewArea area = viewArea;
        if (!RtxRemixMod.isRtxEnabled() || added.isEmpty() || area == null) {
            return;
        }
        BuiltChunkStorageSectionAccess slots = (BuiltChunkStorageSectionAccess) area;
        LongIterator sections = added.iterator();
        while (sections.hasNext()) {
            long sectionNode = sections.nextLong();
            int x = SectionPos.x(sectionNode);
            int y = SectionPos.y(sectionNode);
            int z = SectionPos.z(sectionNode);
            SectionRenderDispatcher.RenderSection section = slots.rtxremix$residentSection(x, y, z);
            if (section != null && section.getSectionMesh() instanceof CompiledSectionMesh) {
                SectionLeftRenderSetQueue.enqueueEmptyCandidate(x, y, z);
            }
        }
    }

    /**
     * Vanilla's walk allocates a graph node for every section it reaches, two vectors for every
     * far-section probe and a block position for every step of one. At render distance 32
     * that is most of the garbage the collector cleans up while the game runs, and the
     * collector's concurrent work is what stretches frames. This port keeps vanilla's
     * traversal, arithmetic and evaluation order, reuses one node per section slot, and holds
     * the probe ray in doubles and one mutable position, so the visible set is identical and
     * the walk allocates nothing per section once its pools have grown.
     */
    @Inject(method = "runUpdates", at = @At("HEAD"), cancellable = true)
    private void rtxremix$walkVisibleGraph(
            GraphStorage storage,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean smartCull,
            Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded,
            LongOpenHashSet emptySections,
            LongOpenHashSet loadedChunks,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$walkScope, "ChunkVisibility.walkVisibleGraph", rtxremix$sizeOf(queue));
        if (!RtxRemixMod.isRtxEnabled() || VisibilityWalkSwitches.VANILLA_WALK) {
            return;
        }
        try {
            rtxremix$walk(storage, cameraPos, queue, smartCull, onSectionAdded, emptySections, loadedChunks);
        } finally {
            rtxremix$close(rtxremix$walkScope);
        }
        if (VisibilityWalkSwitches.LOG_VISIBLE_SET) rtxremix$logVisibleSet("port", storage);
        ci.cancel();
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void rtxremix$walkReturn(
            GraphStorage storage,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean smartCull,
            Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded,
            LongOpenHashSet emptySections,
            LongOpenHashSet loadedChunks,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$walkScope);
        if (VisibilityWalkSwitches.LOG_VISIBLE_SET) rtxremix$logVisibleSet("vanilla", storage);
    }

    /** Keeps the visible-set signature for vanilla's own walk, which the port records itself. */
    @WrapOperation(
            method = "runUpdates",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Octree;add(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)Z"))
    private boolean rtxremix$recordVanillaVisible(
            Octree octree, SectionRenderDispatcher.RenderSection section, Operation<Boolean> original,
            @Local(argsOnly = true) GraphStorage storage) {
        boolean added = original.call(octree, section);
        if (added && VisibilityWalkSwitches.LOG_VISIBLE_SET) {
            ((RenderableChunksBuffers) storage).rtxremix$recordVisible(section);
        }
        return added;
    }

    /** Order-independent signature of the visible set: its size and a hash of every section in it. */
    private static void rtxremix$logVisibleSet(String walk, GraphStorage storage) {
        RenderableChunksBuffers buffers = (RenderableChunksBuffers) storage;
        long hash = buffers.rtxremix$visibleSetHash();
        int size = buffers.rtxremix$visibleSetSize();
        long signature = hash ^ ((long) size << 48);
        if (signature != rtxremix$lastVisibleSetSignature) {
            rtxremix$lastVisibleSetSignature = signature;
            RtxRemixMod.LOGGER.info("Visible set ({} walk): {} sections, hash {}",
                    walk, size, Long.toHexString(hash));
        }
    }

    private void rtxremix$walk(
            GraphStorage storage,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean smartCull,
            Consumer<SectionRenderDispatcher.RenderSection> onSectionAdded,
            LongOpenHashSet emptySections,
            LongOpenHashSet loadedChunks) {
        ViewArea area = viewArea;
        SectionToNodeMap nodes = storage.sectionToNodeMap;
        Octree visibleSections = storage.sectionTree;
        Long2ObjectMap<LongList> waitingForChunkLoads = storage.sectionsWaitingForChunkLoads;
        RenderableChunksBuffers buffers = (RenderableChunksBuffers) storage;
        ChunkInfoPool infoPool = buffers.rtxremix$infoPool();
        Direction[] directions = DIRECTIONS;
        int advancedCullingDistance = MINIMUM_ADVANCED_CULLING_SECTION_DISTANCE;
        boolean recordVisible = VisibilityWalkSwitches.LOG_VISIBLE_SET;

        // SectionPos.of(cameraPos) and its centre, without the two objects.
        int cameraSectionX = SectionPos.blockToSectionCoord(cameraPos.x);
        int cameraSectionY = SectionPos.blockToSectionCoord(cameraPos.y);
        int cameraSectionZ = SectionPos.blockToSectionCoord(cameraPos.z);
        long cameraSectionNode = SectionPos.asLong(cameraSectionX, cameraSectionY, cameraSectionZ);
        int cameraSectionCenterX = SectionPos.sectionToBlockCoord(cameraSectionX) + 8;
        int cameraSectionCenterY = SectionPos.sectionToBlockCoord(cameraSectionY) + 8;
        int cameraSectionCenterZ = SectionPos.sectionToBlockCoord(cameraSectionZ) + 8;
        int worldMaxY = area.maxY();
        int worldMinY = area.minY();

        // One position serves every probed step of every probe in the walk, where vanilla
        // allocated a fresh BlockPos per step. Sound while the view area only derives a slot
        // index from the position it is handed; a lookup that kept or keyed by it would alias
        // every probe to the last step.
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        FarChunkProbe.GraphLookup graph = (x, y, z) -> {
            SectionRenderDispatcher.RenderSection checkSection = area.getRenderSectionAt(probe.set(x, y, z));
            return checkSection != null && nodes.get(checkSection) != null;
        };

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            SectionRenderDispatcher.RenderSection currentSection = node.section;
            long sectionNode = currentSection.getSectionNode();
            long chunkNode = ChunkPos.fromSectionNode(sectionNode);
            if (!loadedChunks.contains(chunkNode)) {
                LongList waitingSections = waitingForChunkLoads.get(chunkNode);
                if (waitingSections == null) {
                    waitingSections = new LongArrayList();
                    waitingForChunkLoads.put(chunkNode, waitingSections);
                }
                waitingSections.add(sectionNode);
                continue;
            }

            if (!emptySections.contains(node.section.getSectionNode())) {
                if (visibleSections.add(node.section)) {
                    if (recordVisible) {
                        buffers.rtxremix$recordVisible(node.section);
                    }
                    onSectionAdded.accept(node.section);
                }
            } else {
                node.section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY);
            }

            boolean distantFromCamera = Math.abs(SectionPos.x(sectionNode) - cameraSectionX) > advancedCullingDistance
                    || Math.abs(SectionPos.y(sectionNode) - cameraSectionY) > advancedCullingDistance
                    || Math.abs(SectionPos.z(sectionNode) - cameraSectionZ) > advancedCullingDistance;

            for (Direction direction : directions) {
                SectionRenderDispatcher.RenderSection neighbour = getRelativeFrom(cameraSectionNode, currentSection, direction);
                if (neighbour == null || (smartCull && node.hasDirection(direction.getOpposite()))) {
                    continue;
                }
                if (smartCull && node.hasSourceDirections()) {
                    SectionMesh sectionMesh = currentSection.getSectionMesh();
                    boolean visibleThrough = false;
                    for (int i = 0; i < directions.length; i++) {
                        if (node.hasSourceDirection(i)
                                && sectionMesh.facesCanSeeEachother(directions[i].getOpposite(), direction)) {
                            visibleThrough = true;
                            break;
                        }
                    }
                    if (!visibleThrough) {
                        continue;
                    }
                }
                if (smartCull && distantFromCamera) {
                    // The ray starts from a corner of the propagating section, as 26.2's walk
                    // casts it, not from the neighbour.
                    boolean reaches = FarChunkProbe.reachesCamera(
                            SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)),
                            SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)),
                            SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)),
                            direction.getAxis(),
                            cameraSectionCenterX, cameraSectionCenterY, cameraSectionCenterZ,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            CEILED_SECTION_DIAGONAL,
                            worldMaxY, worldMinY,
                            graph);
                    if (!reaches) {
                        continue;
                    }
                }

                Node existingNode = nodes.get(neighbour);
                if (existingNode != null) {
                    existingNode.addSourceDirection(direction);
                } else {
                    Node newNode = infoPool.acquire(neighbour, direction, node.step + 1);
                    newNode.setDirections(node.directions, direction);
                    queue.add(newNode);
                    nodes.put(neighbour, newNode);
                }
            }
        }
    }

    private static int rtxremix$sizeOf(List<?> values) {
        return values == null ? -1 : values.size();
    }

    private static int rtxremix$sizeOf(Queue<?> values) {
        return values == null ? -1 : values.size();
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName) {
        rtxremix$open(holder, phaseName, -1);
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (captureCount >= 0) {
            scope.captureCount(captureCount);
        }
        stack.push(scope);
    }

    private static void rtxremix$captureCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled() || captureCount < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().captureCount(captureCount);
        }
    }

    private static void rtxremix$budgetHit(ThreadLocal<ArrayDeque<ProfileScope>> holder, boolean budgetHit) {
        if (!ProfileScope.isDeepProfilingEnabled() || !budgetHit) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().budgetHit(true);
        }
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}
