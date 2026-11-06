/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Per-block mesh emission for RTX Remix, designed to be driven from
 * {@link com.nvidia.rtxremix.minecraft.mixin.SectionBuilderMixin} hooks
 * that piggyback on MC's own block iteration loop.
 *
 * <p>Lifecycle per section:
 * <ol>
 *   <li>{@link #beginSection}: reset state, called at HEAD of compile()</li>
 *   <li>{@link #processBlock}: opacity/masks/lights, called for every block of the section,
 *       air included, as the loop reads it</li>
 *   <li>{@link #emitBlock}: block model quads for MODEL blocks, called from the same hook;
 *       vanilla's block tessellation never runs because captured blocks are reported
 *       invisible</li>
 *   <li>{@link #emitFluid}: fluid quads, called in place of vanilla's fluid tessellation</li>
 *   <li>{@link #finishSection}: build SectionBlockData, called at RETURN</li>
 * </ol>
 *
 * <p>Every block model is read through Fabric's {@code emitQuads}, vanilla models included,
 * which is the one accurate way to read a model and the way Indigo meshes terrain; see
 * {@link RemixRenderContext}. Each emitted quad is staged straight into the
 * {@link DirectQuadWriter}, with its sprite, its terrain layer and its block tint, without
 * allocating.
 *
 * <p>One instance per worker thread via {@code ThreadLocal}.
 */
public final class BlockQuadEmitter {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** Every face list of a model part, the unculled one last, as vanilla reads them. */
    private static final Direction[] PART_FACES = {
        Direction.DOWN, Direction.UP, Direction.NORTH,
        Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    /** Terrain layer names, as {@link BlockTextureManager#layerType} reads them. */
    private static final String LAYER_SOLID = "solid";
    private static final String LAYER_CUTOUT = "cutout";
    private static final String LAYER_TRANSLUCENT = "translucent";
    private static final String LAYER_GLASS = "glass";

    private static final ThreadLocal<BlockQuadEmitter> INSTANCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> threadNamed =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final RemixRenderContext renderContext = new RemixRenderContext(this::stageCapturedQuad);
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42);
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidCheckPos = new BlockPos.MutableBlockPos();
    private final DirectQuadWriter writer = new DirectQuadWriter();
    private final ModelSampling modelSampling = new ModelSampling();
    private final List<LightEntry> lights = new ArrayList<>();
    /** Face cull for the block being emitted; reads {@link #emitState} and {@link #emitWorld}. */
    private final Predicate<@Nullable Direction> faceCullTest = this::isFaceCulled;

    private static final int TINT_CACHE_SIZE = 8;
    private static final int SECTION_SIZE = 16;
    private static final int LOCAL_BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int WATER_COLOR_CACHE_SIZE = 4 * 2 * 4;
    private static final float WATER_CEILING_CULL_MIN_HEIGHT = 8.0f / 9.0f;
    private static final float WATER_HEIGHT_EPSILON = 0.0001f;
    private static final int WATER_TOP_CULL_RADIUS = 2;
    private static final int WATER_TOP_CULL_WIDTH = WATER_TOP_CULL_RADIUS * 2 + 1;
    private static final int WATER_TOP_CULL_CELL_COUNT = WATER_TOP_CULL_WIDTH * WATER_TOP_CULL_WIDTH;
    private static final int WATER_TOP_CACHE_WIDTH = SECTION_SIZE + WATER_TOP_CULL_RADIUS * 2;
    private static final int WATER_TOP_CACHE_CELL_COUNT =
            WATER_TOP_CACHE_WIDTH * SECTION_SIZE * WATER_TOP_CACHE_WIDTH;
    // BlockState render type is immutable; cache by raw state id to avoid a per-block virtual call.
    private static volatile RenderShape[] renderTypeByStateId = new RenderShape[0];

    private final float[] tempPositions = new float[12];
    private final float[] tempUvs = new float[8];
    private final int[] tintCache = new int[TINT_CACHE_SIZE];
    private final int[] waterColorCache = new int[WATER_COLOR_CACHE_SIZE];
    private final byte[] waterTopCellCache = new byte[WATER_TOP_CACHE_CELL_COUNT];
    private final int[] waterTopCullStack = new int[WATER_TOP_CULL_CELL_COUNT];
    private final FluidTopOcclusion.CellClassifier waterTopCellClassifier =
            this::classifyWaterTopCellAtOffset;
    private final BlockState[] blockStateCache = new BlockState[LOCAL_BLOCK_COUNT];
    private final FluidState[] fluidStateCache = new FluidState[LOCAL_BLOCK_COUNT];

    /**
     * Results of {@link Block#shouldRenderFace}, keyed like vanilla's own decision by (state,
     * neighbour state, side), which makes it position-independent. Vanilla allocates a shape-pair
     * key whenever both faces are partial; this direct-mapped table of packed state ids allocates
     * nothing on a hit and defers to vanilla on a miss.
     */
    private static final int FACE_CULL_CACHE_BITS = 13;
    private static final byte FACE_CULL_UNKNOWN = 0;
    private static final byte FACE_CULL_HIDDEN = 1;
    private static final byte FACE_CULL_DRAWN = 2;
    private final long[] faceCullKeys = new long[1 << FACE_CULL_CACHE_BITS];
    private final byte[] faceCullResults = new byte[1 << FACE_CULL_CACHE_BITS];

    // Per-section state set by beginSection / processBlock
    /**
     * Whether {@link #beginSection} has run without a matching {@link #finishSection}.
     * Capture is gated on it, and a build that never reaches its RETURN inject leaves it
     * set, so the next build on this thread releases the writer's grown buffers before
     * it starts.
     */
    private boolean active;
    private BlockTextureManager texManager;
    private BlockStateModelSet blockModels;
    private FluidStateModelSet fluidModels;
    private BlockColors blockColors;
    private SpriteFinder blockSpriteFinder;
    private SpriteFinder itemSpriteFinder;
    private boolean itemSpriteFinderResolved;
    private int baseX, baseY, baseZ;
    private long topMask, bottomMask, eastMask, westMask, southMask, northMask;
    private final long[] opacity = new long[64];
    private final long[] blockStateKnown = new long[64];
    private final long[] fluidStateKnown = new long[64];
    private int blockCount, fluidCount;
    private int tintValidMask;
    /** Tints a Fabric {@link BlockTintsFactory} collected for the block being emitted. */
    private final IntArrayList factoryTints = new IntArrayList();
    private boolean factoryTintsCollected;
    private int waterColorKnownMask;
    private FluidState waterTopCellCacheFluid;
    private BlockAndTintGetter waterTopCullWorld;
    private FluidState waterTopCullReferenceFluid;
    private int waterTopCullOriginX, waterTopCullOriginY, waterTopCullOriginZ;

    // Per-block state for the quads the model emits
    private BlockState emitState;
    private BlockAndTintGetter emitWorld;
    private float emitOriginX, emitOriginY, emitOriginZ;
    /** A layer every quad of the block takes regardless of its own, or null to keep each quad's. */
    private String emitLayerOverride;

    private BlockQuadEmitter() {}

    /** Returns the per-thread instance. */
    public static BlockQuadEmitter threadLocal() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) {
            emitter = new BlockQuadEmitter();
            INSTANCE.set(emitter);
        }
        return emitter;
    }

    /** Returns the current per-thread instance without allocating one. */
    public static BlockQuadEmitter current() {
        return INSTANCE.get();
    }

    public static int getWaterTopOcclusionRadius() {
        return WATER_TOP_CULL_RADIUS;
    }

    // --- Section lifecycle (called from mixin) ---

    /**
     * Initialises emission state for a new section. Called from the
     * SectionBuilderMixin HEAD inject before MC's block loop starts.
     *
     * @param blockModels the compiler's block models, the ones vanilla would tessellate
     * @param fluidModels the compiler's fluid models, for the fluid sprites and tint
     * @param blockColors the compiler's block colours, for the block tints
     */
    public void beginSection(SectionPos sectionPos,
                             BlockTextureManager tex,
                             BlockStateModelSet blockModels,
                             FluidStateModelSet fluidModels,
                             BlockColors blockColors) {
        if (!threadNamed.get()) {
            RemixAPI.tracyThreadName("Worker " + Thread.currentThread().getName());
            threadNamed.set(Boolean.TRUE);
        }
        if (active) {
            writer.releaseBuffers();
        }
        this.active = true;
        this.texManager = tex;
        this.blockModels = blockModels;
        this.fluidModels = fluidModels;
        this.blockColors = blockColors;
        this.baseX = sectionPos.minBlockX();
        this.baseY = sectionPos.minBlockY();
        this.baseZ = sectionPos.minBlockZ();
        // A finder is valid until its atlas is next uploaded, which a resource reload follows
        // with a rebuild of every section, so one lookup per section is current.
        this.blockSpriteFinder = spriteFinder(QuadAtlas.BLOCK);
        this.itemSpriteFinder = null;
        this.itemSpriteFinderResolved = false;

        writer.beginSection(baseX, baseY, baseZ);
        lights.clear();
        topMask = bottomMask = eastMask = westMask = southMask = northMask = 0;
        Arrays.fill(opacity, 0);
        Arrays.fill(blockStateKnown, 0);
        Arrays.fill(fluidStateKnown, 0);
        // Clear the water classification cache lazily on the first eligible
        // surface so sections without covered water pay no cache-reset cost.
        waterTopCellCacheFluid = null;
        waterColorKnownMask = 0;
        blockCount = 0;
        fluidCount = 0;
    }

    public boolean isActive() { return active; }
    public int getBlockCount() { return blockCount; }
    public int getFluidCount() { return fluidCount; }

    public static RenderShape getRenderTypeCached(BlockState state) {
        int id = Block.getId(state);
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            RenderShape renderType = cache[id];
            if (renderType != null) {
                return renderType;
            }
        }

        RenderShape renderType = state.getRenderShape();
        cache = ensureRenderTypeCacheCapacity(id);
        cache[id] = renderType;
        return renderType;
    }

    private static RenderShape[] ensureRenderTypeCacheCapacity(int id) {
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            return cache;
        }

        synchronized (BlockQuadEmitter.class) {
            cache = renderTypeByStateId;
            if (id >= cache.length) {
                int newSize = Math.max(id + 1, Math.max(1024, cache.length * 2));
                renderTypeByStateId = Arrays.copyOf(cache, newSize);
            }
            return renderTypeByStateId;
        }
    }

    /**
     * Captures opacity bitmap, boundary face masks, and light-emitting
     * blocks. Called once for every block of the section, air included.
     */
    public void processBlock(BlockState state, BlockAndTintGetter world, BlockPos pos, boolean opaque) {
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());

        int bitIdx = (ly << 8) | (lz << 4) | lx;
        cacheLocalBlockState(bitIdx, state);

        long bit = 1L << (bitIdx & 63);
        int word = bitIdx >> 6;
        if (opaque) {
            opacity[word] |= bit;
        } else {
            opacity[word] &= ~bit;
        }

        if (!opaque && (ly == 0 || ly == 15 || lx == 0 || lx == 15 || lz == 0 || lz == 15)) {
            if (ly == 15) topMask    |= 1L << ((lz >> 1) * 8 + (lx >> 1));
            if (ly == 0)  bottomMask |= 1L << ((lz >> 1) * 8 + (lx >> 1));
            if (lx == 15) eastMask   |= 1L << ((ly >> 1) * 8 + (lz >> 1));
            if (lx == 0)  westMask   |= 1L << ((ly >> 1) * 8 + (lz >> 1));
            if (lz == 15) southMask  |= 1L << ((ly >> 1) * 8 + (lx >> 1));
            if (lz == 0)  northMask  |= 1L << ((ly >> 1) * 8 + (lx >> 1));
        }

        if (state.isAir()) return;
        if (!BlockLightRuleManager.hasMatchingRules(state)) return;

        // The sampler needs the same seed the mesh uses, so a rule reading
        // model geometry sees the variant this position actually draws.
        //
        // Lights resolve against the unoffset block position, and the sampler
        // reads the unoffset model, while emitBlock folds state.getOffset into
        // the geometry origin. No vanilla block combines an offset function with
        // a luminance, so the two frames agree for every vanilla block; a modded
        // or resource-pack rule on an offset block would need the same offset
        // passed into begin() and into the LightEntry positions.
        modelSampling.begin(blockModels, world, pos, state, state.getSeed(pos));
        List<LightEntry> resolvedLights = BlockLightRuleManager.resolve(state, pos, modelSampling);
        lights.addAll(resolvedLights);
    }

    /**
     * Emits block model quads for the current block. Called for blocks vanilla would hand to
     * its block renderer, a call the build then skips by reporting the block invisible.
     */
    public void emitBlock(BlockState state, BlockPos pos, BlockAndTintGetter world) {
        if (blockModels == null) return;
        blockCount++;
        mutablePos.set(pos);
        tintValidMask = 0;
        factoryTintsCollected = false;

        float lx = SectionPos.sectionRelative(pos.getX());
        float ly = SectionPos.sectionRelative(pos.getY());
        float lz = SectionPos.sectionRelative(pos.getZ());

        // Vanilla applies the per-position model offset (offset functions XZ/XYZ:
        // flowers, saplings, grass, dripstone) inside its block renderer, which this
        // emitter replaces wholesale. Applying it here is what keeps Remix geometry
        // on the same spot as vanilla's.
        //
        // Folded into the section-local origin rather than applied per vertex: every
        // staged position already adds that origin, so the offset rides along for
        // free. hasOffsetFunction() is a bare null check, while getOffset() allocates
        // a Vec3, so the gate keeps that allocation off a path that runs for every
        // block in every section build.
        //
        // This moves geometry only. Block lights are resolved in processBlock
        // against the unoffset grid position; see the note there before adding a
        // light rule to a block that offsets.
        if (state.hasOffsetFunction()) {
            Vec3 modelOffset = state.getOffset(pos);
            lx += (float) modelOffset.x;
            ly += (float) modelOffset.y;
            lz += (float) modelOffset.z;
        }

        BlockStateModel model = blockModels.get(state);
        emitState = state;
        emitWorld = world;
        emitOriginX = lx;
        emitOriginY = ly;
        emitOriginZ = lz;
        emitLayerOverride = blockLayerOverride(state);
        try {
            // Vanilla seeds the model random with the block's rendering seed once per block,
            // then lets the model draw its variants from it. A multi-variant block picks its
            // variant from this, so a constant makes every instance identical; where the
            // variants are rotations of one model that is misplaced geometry, not merely a
            // repeated texture (a sea pickle cluster rotates its pickles around the block).
            random.setSeed(state.getSeed(pos));
            renderContext.emitBlock(model, world, mutablePos, state, random, faceCullTest);
        } finally {
            emitState = null;
            emitWorld = null;
        }

        writer.flushBlock(texManager);
    }

    /**
     * Emits fluid quads for the current block. Called in place of vanilla's fluid
     * tessellation.
     */
    public void emitFluid(BlockPos pos, BlockAndTintGetter world, BlockState blockState, FluidState fluidState) {
        fluidCount++;
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        cacheLocalFluidState(localIndex(lx, ly, lz), fluidState);
        emitFluidQuads(world, blockState, fluidState, lx, ly, lz);
    }

    /**
     * Finalises the section, producing a {@link SectionBlockData} with
     * all accumulated mesh/light/mask data. Deactivates the emitter.
     */
    public SectionBlockData finishSection(SectionPos sectionPos) {
        try (ProfileScope finishScope = ProfileScope.begin("finishSection")) {
            finishScope.blockCount(blockCount).fluidCount(fluidCount);
            SectionMeshData meshData = writer.buildMeshData();
            SectionBlockData result = new SectionBlockData(
                    sectionPos, meshData, List.copyOf(lights), topMask, bottomMask,
                    eastMask, westMask, southMask, northMask,
                    Arrays.copyOf(opacity, opacity.length));
            lights.clear();
            return result;
        } finally {
            active = false;
            modelSampling.clear();
            blockSpriteFinder = null;
            itemSpriteFinder = null;
        }
    }

    // --- Model quads ---

    private boolean isFaceCulled(@Nullable Direction face) {
        return face != null && !shouldDrawBlockSide(emitWorld, emitState, face);
    }

    /**
     * Stages one quad the block's model emitted. The quad's positions are block-local; the
     * block's section-local origin, model offset included, is added here.
     */
    private void stageCapturedQuad(QuadView quad) {
        for (int v = 0; v < 4; v++) {
            tempPositions[v * 3]     = quad.x(v) + emitOriginX;
            tempPositions[v * 3 + 1] = quad.y(v) + emitOriginY;
            tempPositions[v * 3 + 2] = quad.z(v) + emitOriginZ;
            tempUvs[v * 2]     = quad.u(v);
            tempUvs[v * 2 + 1] = quad.v(v);
        }

        // The geometric normal of the first three vertices: vanilla quads carry no vertex
        // normal, and a modded quad's face normal is the same plane.
        float e1x = tempPositions[3] - tempPositions[0], e1y = tempPositions[4] - tempPositions[1], e1z = tempPositions[5] - tempPositions[2];
        float e2x = tempPositions[6] - tempPositions[0], e2y = tempPositions[7] - tempPositions[1], e2z = tempPositions[8] - tempPositions[2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0.001f) { nx /= len; ny /= len; nz /= len; }

        // The model leaves the block tint to the renderer, which multiplies it into every
        // quad with a tint index, vanilla's included; a vanilla quad's vertex color is white,
        // so the result is the opaque tint itself.
        int color = quad.color(0);
        int tintIndex = quad.tintIndex();
        if (tintIndex >= 0 && blockColors != null) {
            color = tintFrapiColor(color, getBlockTint(emitState, emitWorld, tintIndex));
        }

        TextureAtlasSprite sprite = findSprite(quad);
        Identifier spriteId = sprite != null ? sprite.contents().name() : null;
        String layerName = emitLayerOverride != null ? emitLayerOverride : layerName(quad.chunkLayer());
        if (isWaterSprite(spriteId)) {
            emitBlockWaterQuad(nx, ny, nz, color, layerName);
            return;
        }
        float su0 = sprite != null ? sprite.getU0() : 0;
        float sv0 = sprite != null ? sprite.getV0() : 0;
        float su1 = sprite != null ? sprite.getU1() : 1;
        float sv1 = sprite != null ? sprite.getV1() : 1;

        writer.stageQuad(tempPositions, tempUvs, nx, ny, nz, color,
                spriteId, su0, sv0, su1, sv1, layerName);
    }

    /**
     * The atlas sprite a quad samples. The emitter keeps a quad's texture coordinates, not its
     * sprite, so the sprite is found from the coordinates, as Fabric's own code finds it.
     */
    private @Nullable TextureAtlasSprite findSprite(QuadView quad) {
        SpriteFinder finder;
        if (quad.atlas() == QuadAtlas.ITEM) {
            if (!itemSpriteFinderResolved) {
                itemSpriteFinder = spriteFinder(QuadAtlas.ITEM);
                itemSpriteFinderResolved = true;
            }
            finder = itemSpriteFinder;
        } else {
            finder = blockSpriteFinder;
        }
        return finder != null ? finder.find(quad) : null;
    }

    private static @Nullable SpriteFinder spriteFinder(QuadAtlas atlas) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getAtlasManager() == null) {
            return null;
        }
        return ((FabricTextureAtlas) client.getAtlasManager().getAtlasOrThrow(atlas.getId())).spriteFinder();
    }

    private static final Identifier WATER_STILL_SPRITE = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier WATER_FLOW_SPRITE = Identifier.withDefaultNamespace("block/water_flow");

    /** A block-model face painted with the water texture: the cauldron's water level is the vanilla case. */
    static boolean isWaterSprite(Identifier spriteId) {
        return WATER_STILL_SPRITE.equals(spriteId) || WATER_FLOW_SPRITE.equals(spriteId);
    }

    /**
     * Emits the staged quad in {@code tempPositions} as RTX water instead of an
     * atlas-textured face, so a cauldron's water matches the lake outside. The
     * water material tiles in world space, so the UVs come from the vertex
     * positions along the face's dominant axes, exactly as the fluid path maps
     * its top and side faces.
     */
    private void emitBlockWaterQuad(float nx, float ny, float nz, int color, String layerName) {
        float tileSize = BlockTextureManager.getWaterTileSize();
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        for (int v = 0; v < 4; v++) {
            float wx = baseX + tempPositions[v * 3];
            float wy = baseY + tempPositions[v * 3 + 1];
            float wz = baseZ + tempPositions[v * 3 + 2];
            float u, w;
            if (ay >= ax && ay >= az) {
                u = wx; w = wz;
            } else if (ax >= az) {
                u = wz; w = wy;
            } else {
                u = wx; w = wy;
            }
            tempUvs[v * 2] = u / tileSize;
            tempUvs[v * 2 + 1] = w / tileSize;
        }
        writer.writeFluidQuad(tempPositions, tempUvs, nx, ny, nz, color,
                null, 0, 0, 1, 1, layerName, texManager);
    }

    // --- Fluid geometry ---

    private void emitFluidQuads(BlockAndTintGetter world, BlockState blockState, FluidState fluidState,
                                 int lx, int ly, int lz) {
        if (fluidModels == null) return;
        FluidModel model = fluidModels.get(fluidState);
        if (model == null) return;

        fluidPos.set(baseX + lx, baseY + ly, baseZ + lz);

        TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
        TextureAtlasSprite flowSprite = model.flowingMaterial().sprite();
        if (stillSprite == null || flowSprite == null) return;

        boolean isLava = fluidState.is(FluidTags.LAVA);
        boolean isWater = !isLava;
        String layer = isLava ? LAYER_SOLID : LAYER_TRANSLUCENT;
        int fluidColor = isWater
                ? getCachedWaterColor(model, world, blockState, lx, ly, lz)
                : fluidTint(model, world, blockState);
        int r = (fluidColor >> 16) & 0xFF, g = (fluidColor >> 8) & 0xFF, b = fluidColor & 0xFF;
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;

        float h00 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz);
        float h10 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz);
        float h01 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz + 1);
        float h11 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz + 1);

        float tileSize = BlockTextureManager.getWaterTileSize();
        int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;

        Identifier stillId = stillSprite.contents().name();
        float su0 = stillSprite.getU0(), sv0 = stillSprite.getV0();
        float su1 = stillSprite.getU1(), sv1 = stillSprite.getV1();

        Identifier flowId = flowSprite.contents().name();
        float fu0 = flowSprite.getU0(), fv0 = flowSprite.getV0();
        float fu1 = flowSprite.getU1(), fv1 = flowSprite.getV1();

        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() + 1, fluidPos.getZ());
        FluidState aboveFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        boolean submerged = aboveFluid.getType().isSame(fluidState.getType());
        boolean waterTopCovered = false;
        if (isWater && !submerged && fluidState.isSource()) {
            BlockState aboveState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            float topCoverageHeight = Math.min(Math.min(h00, h10), Math.min(h01, h11));
            if (isFluidTopCoveredByCeiling(world, topCoverageHeight, fluidCheckPos, aboveState)) {
                waterTopCovered = classifyWaterTopExposure(world, fluidState, wx, wy, wz)
                        == FluidTopOcclusion.Exposure.HIDDEN;
            }
        }

        // Top face
        if (!submerged && !waterTopCovered) {
            setPos4(tempPositions, lx, ly + h00, lz, lx + 1, ly + h10, lz,
                    lx + 1, ly + h11, lz + 1, lx, ly + h01, lz + 1);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v0, u1, v0, u1, v1, u0, v1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
                setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                        lx + 1, ly + h10, lz, lx, ly + h00, lz);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer: a still surface shows the still sprite in
                // full; a moving one shows the centre quarter of the flowing sprite
                // (two tiles across, so a quarter is one tile) rotated to the flow.
                Vec3 flow = fluidState.getFlow(world, fluidPos);
                if (flow.x == 0.0 && flow.z == 0.0) {
                    setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                } else {
                    float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
                    float sin = Mth.sin(angle) * 0.25f;
                    float cos = Mth.cos(angle) * 0.25f;
                    // Vanilla's corners in its order (0,0) (0,1) (1,1) (1,0), reordered to
                    // this emitter's (0,0) (1,0) (1,1) (0,1).
                    float c00u = flowSprite.getU(0.5f - cos - sin), c00v = flowSprite.getV(0.5f - cos + sin);
                    float c01u = flowSprite.getU(0.5f - cos + sin), c01v = flowSprite.getV(0.5f + cos + sin);
                    float c11u = flowSprite.getU(0.5f + cos + sin), c11v = flowSprite.getV(0.5f + cos - sin);
                    float c10u = flowSprite.getU(0.5f + cos - sin), c10v = flowSprite.getV(0.5f - cos - sin);
                    setUv4(tempUvs, c00u, c00v, c10u, c10v, c11u, c11v, c01u, c01v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    setUv4(tempUvs, c01u, c01v, c11u, c11v, c10u, c10v, c00u, c00v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                }
            }
        }

        // Bottom face
        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() - 1, fluidPos.getZ());
        FluidState belowFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        BlockState belowState = getCachedBlockState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        if (!belowFluid.getType().isSame(fluidState.getType()) && !belowState.isSolidRender()) {
            setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                    lx + 1, ly, lz, lx, ly, lz);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v1, u1, v1, u1, v0, u0, v0);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        stillId, su0, sv0, su1, sv1, layer, texManager);
            }
        }

        // Side faces
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            fluidCheckPos.set(
                fluidPos.getX() + dir.getStepX(),
                fluidPos.getY(),
                fluidPos.getZ() + dir.getStepZ());
            FluidState sideFluid = getCachedFluidState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            BlockState sideState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());

            if (sideFluid.getType().isSame(fluidState.getType())) continue;
            if (sideState.isSolidRender()) continue;

            float sideH0, sideH1;
            switch (dir) {
                case NORTH -> {
                    sideH0 = h10; sideH1 = h00;
                    setPos4(tempPositions, lx + 1, ly, lz, lx, ly, lz,
                            lx, ly + sideH1, lz, lx + 1, ly + sideH0, lz);
                }
                case SOUTH -> {
                    sideH0 = h01; sideH1 = h11;
                    setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                            lx + 1, ly + sideH1, lz + 1, lx, ly + sideH0, lz + 1);
                }
                case EAST -> {
                    sideH0 = h11; sideH1 = h10;
                    setPos4(tempPositions, lx + 1, ly, lz + 1, lx + 1, ly, lz,
                            lx + 1, ly + sideH1, lz, lx + 1, ly + sideH0, lz + 1);
                }
                case WEST -> {
                    sideH0 = h00; sideH1 = h01;
                    setPos4(tempPositions, lx, ly, lz, lx, ly, lz + 1,
                            lx, ly + sideH1, lz + 1, lx, ly + sideH0, lz);
                }
                default -> { continue; }
            }

            float fnx = dir.getStepX(), fny = 0, fnz = dir.getStepZ();
            if (isWater) {
                boolean xAxis = (dir == Direction.EAST || dir == Direction.WEST);
                float sU0, sU1, sV0, sV1;
                if (xAxis) {
                    sU0 = wz / tileSize; sU1 = (wz + 1) / tileSize;
                } else {
                    sU0 = wx / tileSize; sU1 = (wx + 1) / tileSize;
                }
                sV0 = wy / tileSize; sV1 = (wy + 1) / tileSize;
                setUv4(tempUvs, sU0, sV1, sU1, sV1, sU1, sV0, sU0, sV0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer side mapping: one tile is half the flowing
                // sprite, so U spans its left half and V runs from the fluid surface
                // ((1 - height) / 2) down to the half-way line. The full sprite over a
                // one-block face showed the texture at twice the density.
                float sideU0 = flowSprite.getU(0.0f);
                float sideU1 = flowSprite.getU(0.5f);
                float sideVBottom = flowSprite.getV(0.5f);
                float sideVTop0 = flowSprite.getV((1.0f - sideH0) * 0.5f);
                float sideVTop1 = flowSprite.getV((1.0f - sideH1) * 0.5f);
                setUv4(tempUvs, sideU0, sideVBottom, sideU1, sideVBottom, sideU1, sideVTop1, sideU0, sideVTop0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        flowId, fu0, fv0, fu1, fv1, layer, texManager);
            }
        }
    }

    static boolean isFluidTopCoveredByCeiling(BlockGetter world, float fluidHeight,
                                              BlockPos neighborPos, BlockState neighborState) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT
                || !neighborState.canOcclude()) {
            return false;
        }
        if (neighborState.isSolidRender()) {
            return true;
        }

        VoxelShape neighborShape = neighborState.getOcclusionShape();
        return isFluidSideCovered(Direction.UP, 1.0f, true, neighborShape);
    }

    static boolean isFluidTopCoveredByCeiling(float fluidHeight,
                                              boolean neighborOpaque, VoxelShape neighborShape) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT) return false;

        return isFluidSideCovered(Direction.UP, 1.0f, neighborOpaque, neighborShape);
    }

    static boolean isFluidSideCovered(Direction direction, float fluidHeight,
                                      boolean neighborOpaque, VoxelShape neighborShape) {
        if (!neighborOpaque) return false;

        VoxelShape fluidShape = Shapes.box(0.0, 0.0, 0.0, 1.0, fluidHeight, 1.0);
        return Shapes.blockOccludes(fluidShape, neighborShape, direction);
    }

    private FluidTopOcclusion.Exposure classifyWaterTopExposure(
            BlockAndTintGetter world, FluidState referenceFluid, int worldX, int worldY, int worldZ) {
        if (getCachedBlockState(world, worldX, worldY, worldZ).canOcclude()) {
            return FluidTopOcclusion.Exposure.EXPOSED;
        }

        if (waterTopCellCacheFluid == null
                || !waterTopCellCacheFluid.getType().isSame(referenceFluid.getType())) {
            Arrays.fill(waterTopCellCache, FluidTopOcclusion.UNKNOWN);
            waterTopCellCacheFluid = referenceFluid;
        }

        int originCacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (originCacheIndex >= 0) {
            // The caller already established that this still-fluid origin has
            // a covering ceiling, so do not repeat its shape comparison.
            waterTopCellCache[originCacheIndex] = FluidTopOcclusion.CONNECTED;
        }

        waterTopCullWorld = world;
        waterTopCullReferenceFluid = referenceFluid;
        waterTopCullOriginX = worldX;
        waterTopCullOriginY = worldY;
        waterTopCullOriginZ = worldZ;
        try {
            return FluidTopOcclusion.classifyConnectedOrigin(
                    WATER_TOP_CULL_RADIUS, waterTopCullStack, waterTopCellClassifier);
        } finally {
            waterTopCullWorld = null;
            waterTopCullReferenceFluid = null;
        }
    }

    private byte classifyWaterTopCellAtOffset(int xOffset, int zOffset) {
        int worldX = waterTopCullOriginX + xOffset;
        int worldY = waterTopCullOriginY;
        int worldZ = waterTopCullOriginZ + zOffset;
        int cacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (cacheIndex < 0) {
            return classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
        }

        byte classification = waterTopCellCache[cacheIndex];
        if (classification == FluidTopOcclusion.UNKNOWN) {
            classification = classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
            waterTopCellCache[cacheIndex] = classification;
        }
        return classification;
    }

    private int waterTopCellCacheIndex(int worldX, int worldY, int worldZ) {
        int x = worldX - baseX + WATER_TOP_CULL_RADIUS;
        int y = worldY - baseY;
        int z = worldZ - baseZ + WATER_TOP_CULL_RADIUS;
        if ((x | y | z) < 0
                || x >= WATER_TOP_CACHE_WIDTH
                || y >= SECTION_SIZE
                || z >= WATER_TOP_CACHE_WIDTH) {
            return -1;
        }
        return (y * WATER_TOP_CACHE_WIDTH + z) * WATER_TOP_CACHE_WIDTH + x;
    }

    private byte classifyWaterTopCell(BlockAndTintGetter world, FluidState referenceFluid,
                                      int worldX, int worldY, int worldZ) {
        BlockState state = getCachedBlockState(world, worldX, worldY, worldZ);
        if (state.canOcclude()) {
            return FluidTopOcclusion.BLOCKED;
        }

        FluidState fluid = getCachedFluidState(world, worldX, worldY, worldZ);
        if (!fluid.getType().isSame(referenceFluid.getType()) || !fluid.isSource()) {
            return FluidTopOcclusion.EXPOSED;
        }

        FluidState aboveFluid = getCachedFluidState(world, worldX, worldY + 1, worldZ);
        if (aboveFluid.getType().isSame(referenceFluid.getType())) {
            return FluidTopOcclusion.BLOCKED;
        }

        fluidCheckPos.set(worldX, worldY + 1, worldZ);
        BlockState aboveState = getCachedBlockState(world, worldX, worldY + 1, worldZ);
        return isFluidTopCoveredByCeiling(world, fluid.getOwnHeight(), fluidCheckPos, aboveState)
                ? FluidTopOcclusion.CONNECTED
                : FluidTopOcclusion.EXPOSED;
    }

    private float getFluidHeight(BlockAndTintGetter world, FluidState referenceFluid,
                                  int worldX, int worldY, int worldZ) {
        float totalHeight = 0;
        int count = 0;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                fluidCheckPos.set(worldX + dx, worldY + 1, worldZ + dz);
                FluidState aboveFluid = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (aboveFluid.getType().isSame(referenceFluid.getType())) {
                    return 1.0f;
                }

                fluidCheckPos.set(worldX + dx, worldY, worldZ + dz);
                FluidState fs = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (fs.getType().isSame(referenceFluid.getType())) {
                    float h = fs.getOwnHeight();
                    totalHeight += h;
                    count++;
                } else if (!getCachedBlockState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ()).isSolid()) {
                    count++;
                }
            }
        }

        return count > 0 ? totalHeight / count : 0.5f;
    }

    /**
     * The fluid model's tint at the fluid's position, as vanilla's fluid renderer reads it:
     * the model's tint source evaluated for the block state there, or white without one.
     */
    private int fluidTint(FluidModel model, BlockAndTintGetter world, BlockState blockState) {
        BlockTintSource tintSource = model.tintSource();
        return tintSource != null ? tintSource.colorInWorld(blockState, world, fluidPos) : -1;
    }

    private int getCachedWaterColor(FluidModel model, BlockAndTintGetter world, BlockState blockState,
                                    int lx, int ly, int lz) {
        int cell = ((ly >> 3) << 4) | ((lz >> 2) << 2) | (lx >> 2);
        int bit = 1 << cell;
        if ((waterColorKnownMask & bit) == 0) {
            waterColorCache[cell] = fluidTint(model, world, blockState);
            waterColorKnownMask |= bit;
        }
        return waterColorCache[cell];
    }

    // --- Helpers ---

    private boolean shouldDrawBlockSide(BlockAndTintGetter world, BlockState state, Direction side) {
        int neighborX = mutablePos.getX() + side.getStepX();
        int neighborY = mutablePos.getY() + side.getStepY();
        int neighborZ = mutablePos.getZ() + side.getStepZ();
        BlockState neighbor = getCachedBlockState(world, neighborX, neighborY, neighborZ);
        long key = ((long) Block.getId(state) << 35)
                | ((long) Block.getId(neighbor) << 3)
                | side.ordinal();
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - FACE_CULL_CACHE_BITS));
        if (faceCullKeys[slot] == key && faceCullResults[slot] != FACE_CULL_UNKNOWN) {
            return faceCullResults[slot] == FACE_CULL_DRAWN;
        }

        boolean drawn = Block.shouldRenderFace(state, neighbor, side);
        faceCullKeys[slot] = key;
        faceCullResults[slot] = drawn ? FACE_CULL_DRAWN : FACE_CULL_HIDDEN;
        return drawn;
    }

    private BlockState getCachedBlockState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getBlockState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((blockStateKnown[word] & bit) != 0) {
            return blockStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        BlockState state = world.getBlockState(neighborPos);
        cacheLocalBlockState(idx, state);
        return state;
    }

    private FluidState getCachedFluidState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getFluidState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((fluidStateKnown[word] & bit) != 0) {
            return fluidStateCache[idx];
        }

        FluidState fluidState = getCachedBlockState(world, worldX, worldY, worldZ).getFluidState();
        cacheLocalFluidState(idx, fluidState);
        return fluidState;
    }

    private void cacheLocalBlockState(int idx, BlockState state) {
        blockStateCache[idx] = state;
        blockStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private void cacheLocalFluidState(int idx, FluidState fluidState) {
        fluidStateCache[idx] = fluidState;
        fluidStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    private int getBlockTint(BlockState state, BlockAndTintGetter world, int tintIndex) {
        if (tintIndex >= TINT_CACHE_SIZE) {
            return computeBlockTint(state, world, tintIndex);
        }

        int bit = 1 << tintIndex;
        if ((tintValidMask & bit) == 0) {
            tintCache[tintIndex] = computeBlockTint(state, world, tintIndex);
            tintValidMask |= bit;
        }
        return tintCache[tintIndex];
    }

    /**
     * The block's tint for {@code tintIndex}, as Indigo's block renderer resolves it: the
     * block's registered tint sources evaluated in the world, or, for a block without any,
     * the tints a Fabric {@link BlockTintsFactory} collects for it. An index past the block's
     * tints is untinted, -1.
     */
    private int computeBlockTint(BlockState state, BlockAndTintGetter world, int tintIndex) {
        List<BlockTintSource> tintSources = blockColors.getTintSources(state);
        if (!tintSources.isEmpty()) {
            if (tintIndex >= tintSources.size()) {
                return -1;
            }
            BlockTintSource tintSource = tintSources.get(tintIndex);
            return tintSource != null ? tintSource.colorInWorld(state, world, mutablePos) : -1;
        }

        if (!factoryTintsCollected) {
            factoryTints.clear();
            BlockTintsFactory factory = BlockColorRegistry.getFactory(state);
            if (factory != null) {
                factory.collect(state, world, mutablePos, factoryTints);
            }
            factoryTintsCollected = true;
        }
        return tintIndex < factoryTints.size() ? factoryTints.getInt(tintIndex) : -1;
    }

    /**
     * A captured quad's vertex color under its block tint, as Indigo's
     * {@code multiplyColor} computes it: each channel multiplied, rounding down. The tint is
     * treated as opaque, so the vertex alpha is kept. A block without a tint source has the
     * tint -1, which is white and changes nothing.
     */
    static int tintFrapiColor(int vertexColor, int tint) {
        int red = ((vertexColor >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 0xFF;
        int green = ((vertexColor >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 0xFF;
        int blue = (vertexColor & 0xFF) * (tint & 0xFF) / 0xFF;
        return (vertexColor & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }

    /**
     * The terrain layer a block's quads all take regardless of their own, or null to keep
     * each quad's layer.
     *
     * <p>Vanilla draws clear glass as a cutout: the texture is alpha 0 between the frame
     * pixels and the GL path simply discards those. Remix can refract through it instead, so
     * clear glass takes its own layer and a translucent material with the frame as a diffuse
     * layer. Stained glass is already translucent and keeps vanilla's layer. Tripwire drew in
     * a layer of its own that Remix shaded as a cutout; it stays a cutout.
     */
    private static @Nullable String blockLayerOverride(BlockState state) {
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return LAYER_GLASS;
        if (state.is(Blocks.TRIPWIRE)) return LAYER_CUTOUT;
        return null;
    }

    /** The terrain layer name of a quad's chunk layer. */
    static String layerName(@Nullable ChunkSectionLayer layer) {
        if (layer == null) return LAYER_SOLID;
        return switch (layer) {
            case SOLID -> LAYER_SOLID;
            case CUTOUT -> LAYER_CUTOUT;
            case TRANSLUCENT -> LAYER_TRANSLUCENT;
        };
    }

    /** Per-state answers of {@link #classifyBlockLayer}, valid for one model set. */
    private record BlockLayerCache(BlockStateModelSet models, boolean cutoutLeaves, String[] layerByStateId) {}

    private static volatile BlockLayerCache blockLayerCache;

    /**
     * The terrain layer a block is bucketed under as a whole, by
     * {@link BlockTextureManager#layerType} name. A held block item asks this, so it shades
     * as its placed counterpart does.
     *
     * <p>Terrain quads carry their own layer, chosen at bake time from their sprite's
     * transparency. A block's answer is the most transparent layer among its model's quads,
     * which is what the whole block's layer was when blocks had one: a grass block is a
     * cutout because of its overlay, leaves are solid when the options force them opaque.
     */
    public static String classifyBlockLayer(BlockState state) {
        String override = blockLayerOverride(state);
        if (override != null) return override;

        Minecraft client = Minecraft.getInstance();
        ModelManager modelManager = client != null ? client.getModelManager() : null;
        BlockStateModelSet models = modelManager != null ? modelManager.getBlockStateModelSet() : null;
        if (models == null) return LAYER_SOLID;
        boolean cutoutLeaves = client.options.cutoutLeaves().get();

        BlockLayerCache cache = blockLayerCache;
        if (cache == null || cache.models() != models || cache.cutoutLeaves() != cutoutLeaves) {
            cache = new BlockLayerCache(models, cutoutLeaves, new String[1024]);
            blockLayerCache = cache;
        }
        int id = Block.getId(state);
        String[] byId = cache.layerByStateId();
        if (id < byId.length && byId[id] != null) {
            return byId[id];
        }

        String layer = classifyModelLayer(state, models.get(state), cutoutLeaves);
        if (id >= byId.length) {
            byId = Arrays.copyOf(byId, Math.max(id + 1, byId.length * 2));
            blockLayerCache = new BlockLayerCache(models, cutoutLeaves, byId);
        }
        byId[id] = layer;
        return layer;
    }

    private static String classifyModelLayer(BlockState state, BlockStateModel model, boolean cutoutLeaves) {
        if (model == null || ModelBlockRenderer.forceOpaque(cutoutLeaves, state)) return LAYER_SOLID;
        if (model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT)) return LAYER_TRANSLUCENT;

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(new SingleThreadedRandomSource(42L), parts);
        ChunkSectionLayer layer = ChunkSectionLayer.SOLID;
        for (BlockStateModelPart part : parts) {
            for (Direction face : PART_FACES) {
                for (BakedQuad quad : part.getQuads(face)) {
                    ChunkSectionLayer quadLayer = quad.materialInfo().layer();
                    if (quadLayer.ordinal() > layer.ordinal()) {
                        layer = quadLayer;
                    }
                }
            }
        }
        return layerName(layer);
    }

    private static void setPos4(float[] out,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3) {
        out[0] = x0; out[1] = y0; out[2] = z0;
        out[3] = x1; out[4] = y1; out[5] = z1;
        out[6] = x2; out[7] = y2; out[8] = z2;
        out[9] = x3; out[10] = y3; out[11] = z3;
    }

    private static void setUv4(float[] out,
                                float u0, float v0, float u1, float v1,
                                float u2, float v2, float u3, float v3) {
        out[0] = u0; out[1] = v0; out[2] = u1; out[3] = v1;
        out[4] = u2; out[5] = v2; out[6] = u3; out[7] = v3;
    }
}
