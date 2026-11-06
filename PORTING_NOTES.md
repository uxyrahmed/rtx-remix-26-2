# Porting Notes: Minecraft 1.21.1 to 26.2

This branch ports RTX Remix for Minecraft from Minecraft Java 1.21.1 to
**Minecraft Java 26.2**. The newest stable release when the port was made was
26.3 (released 2026-09-15); the repository owner chose 26.2 as the target, so
26.3 is listed under [Beyond 26.2](#beyond-262) instead.

The experimental `forge-1.12.2/` module is untouched.

The port compiles, all 825 JVM and Fabric tests pass, every mixin is checked
to apply to the 26.2 classes, and the game was launched into a world with the
mod loaded. What has not run is path tracing itself: that needs the native
bridge, which is built with Visual Studio C++ tools that the porting machine
lacked, so the game ran with the mod's renderer off. Everything that renders
through Remix must be checked in game on Windows with an RTX GPU; the
[Windows test checklist](#windows-test-checklist) lists what to look at, and
[Verification](#verification) says exactly what was and was not run.

## Versions

| Component | 1.21.1 build | 26.2 build | Source |
|---|---|---|---|
| Minecraft | 1.21.1 | 26.2 | Mojang version manifest, `meta.fabricmc.net/v2/versions/game` |
| Java | 21 | 25 | `javaVersion` in Mojang's 26.2 version JSON (`java-runtime-epsilon`) |
| Mappings | Yarn `1.21.1+build.3` | none: 26.x is not obfuscated | `meta.fabricmc.net/v2/versions/yarn/26.2` is empty; Fabric's obfuscation post |
| Fabric Loader | 0.16.5 | 0.20.1 | `meta.fabricmc.net/v2/versions/loader` (newest stable) |
| Fabric API | 0.116.10+1.21.1 | 0.161.0+26.2 | `maven.fabricmc.net` fabric-api metadata (newest for 26.2) |
| Fabric Loom | 1.15.5, plugin `fabric-loom` | 1.19.3, plugin `net.fabricmc.fabric-loom` | Loom plugin metadata (newest stable; the example mod uses 1.19-SNAPSHOT) |
| Gradle wrapper | 9.3.1 | 9.8.2 | `fabric-example-mod` 26.2 branch |
| Mixin compatibility level | `JAVA_21` | `JAVA_25` | `fabric-example-mod` |
| Access widener | `accessWidener v2 named` | class tweaker, `classTweaker v1 official` | Fabric docs, Class Tweakers |
| LWJGL (from the game) | 3.3.3 | 3.4.1 | Mojang's 26.2 version JSON |
| Fabric installer used by `installer.ps1` | 1.0.1 | 1.1.2 | `meta.fabricmc.net/v2/versions/installer` |
| RTX Remix runtime, C API header, JNI bridge | unchanged | unchanged | `packman/remix-sdk.packman.xml` |

## How The Port Was Done

1. **Baseline.** The unchanged 1.21.1 tree built and passed its tests: in CI
   (`build` on a Windows runner, native bridge included, 3m37s) and locally
   with the native step skipped (835 JVM tests and 11 Fabric tests).
2. **Mappings first.** Fabric publishes no Yarn for 26.x, so the source moved
   to Mojang's names while still on 1.21.1, with Loom's `migrateMappings` and
   `migrateClassTweakerMappings`, then `loom.officialMojangMappings()`. That
   tree built and passed the same 846 tests before any version changed, so
   every later compile error was a real API change, not a rename.
3. **Toolchain.** Minecraft 26.2, the non-remapping Loom plugin, Java 25,
   Gradle 9.8.2, Loader 0.20.1, Fabric API 0.161.0+26.2, then mechanical class
   moves (for example `ResourceLocation` to `Identifier`, the 26.1 Fabric API
   renames from Fabric's migration map).
4. **API port.** Each subsystem was rewritten against the decompiled 26.2
   sources, using NeoForged's per-version porting primers (1.21.2 to 26.2) and
   Fabric's per-version posts to find what replaced each removed API. The jump
   was done in one step from the Mojang-named 1.21.1 tree rather than through
   every intermediate version, which would have rewritten the same rendering
   hooks several times over. Terrain, entities, visuals (particles, textures,
   sky, clouds, weather) and world code (blocks, screens, settings, lights,
   camera paths, benchmark) were ported in parallel against one shared brief;
   the frame, present, window and input core, the integration between the
   parts, and the build, installer, tools and docs were done on the branch
   itself.
5. **Verification**, below.

## Verification

| Check | Result |
|---|---|
| `gradlew build`, native step skipped (`-x setupRuntime`) | Compiles with no errors |
| JVM tests (`test`) | 818 passed, 0 failed |
| Fabric tests (`fabricTest`) | 7 passed, 0 failed, including the new `MixinApplicationTest` |
| Mixin audit | All 76 mixins apply to every target; also run with `mixin.debug.countInjections` and `mixin.debug.strict` |
| Python tools (`tools\rtx.bat check --tests`) | Format and lint clean; 1259 passed, 1 skipped |
| `runClient` without the Remix runtime | Loaded a world and ran in it for a minute with no errors from the mod |
| Native bridge, `package`, path tracing | Not run: no Visual Studio C++ tools on the porting machine; CI's Windows runners build the bridge |

The test count went from 835 JVM and 11 Fabric tests to 818 and 7. Four test
classes went with the code they tested (`ChunkSlotMoveLogTest`,
`ChunkStorageTorusTest`, `HierarchicalFrustumCullerTest`,
`DynamicBufferUploadTest`), `RemixRenderContextTest` was rewritten against
26.2's Indigo with fewer cases, and new cases were added elsewhere.

The game launch found one bug the tests had missed, which is fixed: Mixin
silently dropped the field initializer of the visibility walk's work queue
(`ChunkRenderingDataPreparerMixin`), so the game crashed on world join. JDK 25's
javac gives an implicit constructor's `return` the line of the last static
initializer, which puts an instance initializer above it inside the line range
Mixin treats as the constructor body. The queue is now created in a
constructor injector, `MixinApplicationTest` checks every mixin field
initializer against the classes Mixin exports, and `CONTRIBUTING.md` explains
the trap. The other five mixin field initializers reach their targets.

## Build, Toolchain And Metadata

- `build.gradle`: plugin `net.fabricmc.fabric-loom` 1.19.3; Java toolchain
  and `options.release` 25; no `mappings` line; `implementation` instead of
  `modImplementation`; `packageJar` starts from `jar` because there is no
  `remapJar`; the mixin annotation processor and refmap are gone (the game
  runs with the names the mixins use). `minecraftVersion`,
  `fabricApiCoordinate` and `fabricLoaderVersion` hold the pins the version
  consistency test reads.
- `gradle-wrapper.properties`: Gradle 9.8.2 with its published SHA-256.
- `fabric.mod.json`: `minecraft` 26.2, `fabricloader` >=0.20.1, `fabric-api`
  >=0.161.0+26.2, `java` >=25.
- `rtx-remix-renderer.mixins.json`: `JAVA_25`; `defaultRequire` stays 1, so a
  mixin that misses its target fails at startup instead of silently.
- `fabricTest` now runs in `build/fabricTest/` with `mixin.debug.export` on,
  so `MixinApplicationTest` can read the transformed classes.
- The class tweaker (`rtx-remix-renderer.accesswidener`) holds 29 entries for
  26.2, grouped by what uses them and checked by `validateAccessWidener`. The
  64 entries 1.21.1 needed are gone: their members are public in 26.2, no
  longer exist, or are replaced by the new entries.
- `.github/workflows/build.yml` and `release.yml`: JDK 25 for the mod build.
  The Forge jobs keep JDK 8.

## Core: Frame, Presentation, Window And Input

On 26.2 a frame is `Minecraft.runTick` (tasks, ticks, sound, mouse) followed by
`renderFrame`: `GameRenderer.update` moves the camera, `GameRenderer.extract`
copies the world into render states, `GameRenderer.render` draws, then the
window surface blits and presents.

- **Graphics backend.** 26.2 can render through Vulkan, but the Remix
  composite imports Remix's images into OpenGL (`GL_EXT_memory_object`,
  `GL_EXT_semaphore`). OpenGL is already the default (`PreferredGraphicsApi`
  tries it first); `MinecraftClientMixin` also tries OpenGL first when the
  Remix runtime is installed and the player picked Vulkan, and
  `RemixRenderer.initialize` refuses to start on another backend with a clear
  log line.
- **Present.** The buffer swap moved from `RenderSystem.flipFrame` to the
  OpenGL surface: `GlSurfaceMixin` (new, replaces `RenderSystemMixin`)
  redirects `GLFW.glfwSwapBuffers` in `GlSurface.present()` to
  `RemixRenderer.presentFrameVk()`. 26.2 skips the present while the window is
  minimized or its surface is unconfigured, where 1.21.1 always swapped, so
  `RemixRenderer.finishFramePresentation()` (called from
  `MinecraftClientMixin` before `LevelRenderer.endFrame`) presents through
  Remix on such frames to keep every Remix submit followed by a present.
- **Composite source.** Render targets are Blaze3d textures since 1.21.5 with
  no framebuffer id; `GlInterop.copyFromTexture` attaches the main target's
  `GlTexture.glId()` to its own framebuffer for the blit into the shared image.
- **Context.** `GlBackendMixin` (new) raises the context to 4.4 core after
  `GlBackend.setWindowHints`, where 26.2 sets the window hints; `WindowMixin`
  keeps only the handle log.
- **Frame hooks.** `MinecraftClientMixin` moved its profiling phases to
  `renderFrame` (keeping the names the profiling tools match), applies
  camera-path poses before `GameRenderer.update`, and drops hooks whose
  targets are gone (`Thread.yield`, `LevelRenderer.tick`). `GameRendererMixin`
  submits the Remix frame before `ScreenEffectRenderer.submit` (between the
  hands and the screen overlays, as on 1.21.1) and at the end of
  `renderLevel`, and follows the Remix menu at the start of `extract`, where
  26.2 screens decide hover. `WorldRendererMixin` is reduced to the start and
  end of `LevelRenderer.render` (frame setup and "ready to submit") and the
  profiler phase mirror; Fabric's `START_MAIN` fires too late for that setup,
  after vanilla has submitted every entity.
- **Frame-rate cap.** `UnfocusedFramerateMixin` targets
  `FramerateLimitTracker.getFramerateLimit()`.
- **Input.** `KeyboardMixin` and `MouseMixin` take 1.21.9's `KeyEvent`,
  `CharacterEvent` and `MouseButtonInfo`; capture also withholds 26.x's
  input-method composition (`preeditCallback`).
- **Fabric API.** Level render events moved to `LevelRenderEvents`; the
  targeted-block outline is captured from `BEFORE_BLOCK_OUTLINE`; the reload
  listener uses `ResourceLoader` (resource loader v1) with a vanilla
  `ResourceManagerReloadListener`; key mappings use `KeyMappingHelper` and a
  registered `KeyMapping.Category`.
- **Toggle Held Lights is now H.** 1.21.1 used G because it was unbound;
  vanilla binds G to Quick Actions since 1.21.6, so the same rule picks H.
  F8 (Toggle RTX) is still unbound in 26.2. The in-game guide says H.
- Chat and action-bar messages use `sendSystemMessage` and
  `sendOverlayMessage`; screens open through `Minecraft.gui.setScreen`.

## Terrain

26.2 splits the old `WorldRenderer` into `LevelExtractor` (which decides what is
visible and marks sections dirty) and `LevelRenderer` (which compiles, uploads
and draws). Sections are compiled by `SectionCompiler` from a
`RenderSectionRegion` of 27 `SectionCopy` snapshots, and visibility is
`SectionOcclusionGraph` with a section octree for frustum culling.

- **Capture path.** `BlockQuadEmitter` reads every block model through Fabric's
  `emitQuads`, the path Indigo itself meshes terrain through, so vanilla and
  Fabric API models share one capture path. Quads go straight into
  `DirectQuadWriter` with no per-quad allocation. The sprite comes from
  Fabric's `SpriteFinder`, the layer from each quad's own chunk layer (clear
  glass still forced to "glass", tripwire to "cutout"), the tint from the
  block's tint sources with Fabric's `BlockColorRegistry` as fallback, and
  fluids from the compiler's `FluidModel` set. `RemixRenderContext` is now a
  thin wrapper over the active renderer's emitter, and `ModelSampling` samples
  through the same path and seed.
- **Section compile hooks** (`SectionBuilderMixin` on `SectionCompiler.compile`):
  the per-block hook moved from a redirect of `isSolidRender` to a wrap of
  `RenderSectionRegion.getBlockState`, because 26.2 skips air before
  `isSolidRender` and the capture masks need air. `getRenderShape` still
  returns INVISIBLE while capturing (which also skips Fabric's tessellation
  redirect); `renderLiquid` became a wrap of `FluidRenderer.tesselate`.
- **Region snapshot pooling** keeps the 1.21.1 ownership rules on the new
  classes: `RenderedChunkMixin` (now `SectionCopy`, taking pooled storage for
  `PalettedContainer.copy()`), `ChunkRendererRegionMixin`
  (`RenderSectionRegion`), `ChunkRendererRegionBuilderMixin`
  (`RenderRegionCache`), `RebuildTaskMixin` (`CompileTask`, released at
  `doTask` return) and `PackedIntegerArrayMixin`.
- **Visibility walk** (`ChunkRenderingDataPreparerMixin` on
  `SectionOcclusionGraph`, `RenderableChunksMixin` on its `GraphStorage`): the
  allocation-free walk follows 26.2's loaded-chunk and empty-section gates, its
  octree is rebuilt on pooled nodes (`PooledOctree`) with vanilla's structure
  and visit order, and the far-section probe follows 26.2's JOML arithmetic. A
  new `updateEmptySections` hook reports compiled sections that became empty,
  because 26.2 drops them from the visible set without a recompile.
  `-Drtxremix.vanillaVisibilityWalk` still switches back to vanilla's walk for
  an A/B comparison, and `-Drtxremix.logVisibleSet` still logs the set.
- **Removed because vanilla now does the job:** the custom frustum index and
  its slot move log (`BuiltChunkFrustumIndex`, `HierarchicalFrustumCuller`,
  `ChunkSlotMoveLog`; the octree culls hierarchically), the reposition
  override (`ChunkStorageTorus`; vanilla returns early when the camera section
  is unchanged), `ReachedChunkSet`, and the allocation ports of block model
  selection (`WeightedBakedModelMixin`, `MultipartBakedModelMixin`) and grass
  colour (`GrassColorsMixin`). `VertexBufferMixin` is gone because 26.2
  specifies a buffer's storage once at creation, which was the leak it worked
  around. `IndigoMeshMixin`, `ClientChunkMixin`, `ClientChunkSnapshotAccess`
  and `BuiltChunkBuffersMixin` targeted classes that no longer exist.
- **Former `WorldRendererMixin` terrain hooks** now live in three new mixins:
  `TerrainLevelExtractorMixin` (water occlusion margin, light re-flag filter,
  the frame's region cache), `TerrainChunkLoadReflagMixin` (re-flag filter on
  the chunk-load path in `ClientPacketListener`) and
  `TerrainLevelRendererMixin` (`WorldRendererTerrainAccess` and the compile,
  upload and occlusion-update profiling scopes). `ChunkSectionsToRenderMixin`
  cancels vanilla's terrain draw (`renderGroup`) while Remix renders; it opens
  and closes its own render pass, so there is no state to replay.
- **Recapture** (`RtxRemixMod.rebuildLoadedTerrain`) calls
  `LevelExtractor.allChanged()`. 26.2 replaces the view area at the next
  extract, so the explicit recapture queue waits for that.
- The block-entity chunk narrowing (`iterateBlockEntityChunks`) is not
  re-implemented: 26.2's `LevelExtractor.extractVisibleBlockEntities` already
  skips sections without block entities cheaply.

## Entities And Block Entities

26.x renders entities in two steps: extraction copies each entity into an
`EntityRenderState` (block entities into a `BlockEntityRenderState`), and the
renderer then *submits* draw nodes to a `SubmitNodeCollector`, which vanilla's
feature renderers draw later. The mod now captures at the submit step.

- **Owner tags.** Vanilla extraction is untouched. `RenderStateOwnerMixin`
  adds an owner field to both state classes, and the extraction hooks in
  `EntityRenderDispatcherMixin` and `BlockEntityRenderDispatcherMixin` fill it,
  because retained instances are keyed by owner identity and a state is a new
  object every frame.
- **Capture point.** Where vanilla submits a state
  (`LevelRenderer.submitEntities`, `BlockEntityRenderDispatcher.submit`,
  `ItemInHandRenderer.submitHandsWithItems`), the passes hand the renderer
  `RemixVertexConsumerProvider`, which is now a `SubmitNodeCollector` that
  evaluates each node at once, mirroring vanilla's feature renderers (model,
  item, text, name tag, flame, leash, custom geometry, block model, moving
  block). Vanilla's storage never sees a captured submit, so each model is
  still evaluated once per frame, on the render thread. A glowing entity's
  outline submits are forwarded to vanilla's collector, so vanilla still draws
  outlines; blob shadows are dropped, as before.
- **New mixins:** `LevelRendererCaptureMixin` (replaces the entity and block
  damage hooks of `WorldRendererMixin`), `FirstPersonCaptureMixin` (replaces
  the first-person hook of `GameRendererMixin`), `ItemStackRenderStateMixin`,
  `ItemStackLayerRenderStateMixin` and `ItemModelResolverMixin` (replace
  `ItemRendererMixin`; 26.2 has no `ItemRenderer`), `RenderStateOwnerMixin`,
  and two particle accessors for the held-flame sprites.
- **Removed:** `Deadmau5FeatureRendererMixin` (ears are decided once in the
  `AbstractClientPlayer` constructor), `LivingEntityRendererNameMixin` and
  `PlayerEntityNameMixin` (the upside-down check reads `getCustomName` and
  nothing else used them), `EntityRenderDispatcherAccessor`
  (`shouldRenderShadow` is gone), `MatrixStackAccessor` and
  `MatrixStackEntryAccessor` (`PoseStack` pools its own entries now).
- **Quads.** `BakedQuad` is a record in 26.x that stores only its face
  direction, so `RemixCapturingConsumer.putBakedQuad` takes colour from the
  `QuadInstance` with the overlay folded in and computes the normal from the
  corners. `RenderLayerUtils` reads a `RenderType`'s texture and pipeline
  (`RenderPipelines`) instead of 1.21.1's render state shards.
- **Copper Beacon renderer** is a `BlockEntityRenderer` with its own render
  state: extraction evaluates the shared `CopperBeaconPose`, submission sends
  the yoke and head as custom geometry (the copper UV strip still works), and
  the lens is a `Model` posed from the state.
- **First person** calls `submitHandsWithItems` with the capture collector;
  the field-of-view ratio comes from the camera projection.
- The off-frustum sweep extracts each swept entity's state itself (skipping
  only the shadow step), so it allocates one render state per swept entity per
  frame.

## Particles, Textures, Sky, Clouds And Weather

- **Particles.** `ParticleEngine` keeps particles in per-render-type groups,
  and the billboard particle class is `SingleQuadParticle`.
  `RemixParticleRenderer` reads the groups and picks each particle's material
  from its layer's atlas: block atlas as terrain, the new item atlas with its
  own material, particle atlas blended; other atlases are skipped. Vanilla's
  particle draw is skipped by cancelling `ParticleEngine.extract` while Remix
  draws the world (`ParticleManagerMixin`), which skips every particle submit
  as cancelling 1.21.1's `ParticleEngine.render` did; the tick hooks moved to
  the new `ParticleGroupMixin`. The water rapids particles extend
  `SingleQuadParticle`, and their foam colour comes from the fluid model's
  tint source (Fabric's `FluidRenderHandler.getFluidColor` is gone).
  `ParticleSprites` looks up vanilla's sprite sets, which moved to
  `ParticleResources`; the held-item flame uses it too.
- **Atlases and animation.** 26.2 atlas sprites carry padding, so every pixel
  blit uses the content origin (`BlockTextureManager.spriteContentX/Y`), and
  the stitched copy repeats edge texels into the padding as vanilla does.
  Vanilla animates atlases on the GPU now, so `AtlasAnimationForwarder` works
  from each advanced animation state (`SpriteAtlasTextureMixin` at
  `cycleAnimationFrames`) and blends interpolated frames on the CPU with the
  1.21.1 maths. `NativeImagePixels` replaces the removed `getPixelRGBA`.
- **Glyphs.** Font pages are uploaded through `CommandEncoder`, so the mirror
  hooks `writeToTexture` and `copyBufferToTexture` (`CommandEncoderUploadMixin`)
  plus `FontTexture.add` (`GlyphAtlasTextureMixin`), and records bitmap font
  sheets as they are built (`BitmapGlyphSourceMixin`). This replaces the
  `NativeImage` and `GlStateManager` upload hooks.
- **Skins** arrive as `DynamicTexture`s, so `PlayerSkinTextureMixin` and the
  `HttpTexture` branch are gone, and a dynamic texture counts as released when
  its image is closed.
- **Sky.** The sky type comes from `dimensionType().skybox()`, the sun angle
  from `LightManager.skyAngle` (shared with the sun light), and the moon phase
  from its attribute. Sun and moon textures moved to `environment/celestial/`.
  Star positions are captured from vanilla's star mesh in
  `SkyRenderer.buildStars` (`VanillaSkyRendererMixin`); the sun and moon no
  longer wait for the stars. Star brightness keeps 1.21.1's time-only curve
  because 26.2's attribute already includes the rain fade the mod applies
  itself. `AtmosphericSkyGenerator` restores the previously bound program
  (there is no `ShaderInstance` any more).
- **Clouds.** Colour and height come from the camera's environment probe at the
  frame's partial tick, as vanilla 26.2 reads them; the height attribute
  already includes the 0.33 lift. Classic clouds follow game time, as vanilla
  26.2 does. Vanilla's cloud draw is cancelled in `CloudRenderer.render`
  (`VanillaCloudRendererMixin`).
- **Weather.** `RemixWeatherRenderer` is rewritten in raw OpenGL: vanilla's
  own column builder (through `WeatherEffectRendererAccessor`) turns rain and
  snow into quads, `WeatherEffectRendererMixin` cancels vanilla's draw and
  captures them, rain keeps the refraction shader, and snow has a new shader
  in vanilla's particle style. Remix depth is copied into a private depth
  buffer instead of the main target's, and the projection is built for 26.2's
  zero-to-one clip depth. The shaders rebuild on resource reload.
- **Remix image composite.** `TextureOverlay` draws a raw-GL fullscreen
  triangle into the main target, with the same top-left UVs and alpha-zero
  discard as before. `RawGlPass` brackets raw GL work: tracked state goes
  through `GlStateManager`, and program, vertex array, framebuffers and
  viewport are restored afterwards, so Blaze3d's caches stay right.
- **Removed:** `NativeImageUploadMixin`, `GlStateManagerUploadMixin`,
  `SpriteContentsUploadMixin`, `NativeImageAccessor`,
  `SpriteBillboardParticleAccessor`, `PlayerSkinTextureMixin` (targets gone or
  replaced as above), and `gl/DynamicBufferUpload` with its test (its only
  caller was `VertexBufferMixin`).

## World, Blocks, Screens And Tools In Game

- **Copper Beacon block.** The registry key goes into the block and item
  properties (`setId`); the creative tab entry uses
  `CreativeModeTabEvents.modifyOutputEvent(FUNCTIONAL_BLOCKS)`, which is public
  in 26.2; `MOUNT` is an `EnumProperty<Direction>`; neighbour updates take an
  `Orientation`. Save and load go through `ValueOutput` and `ValueInput`
  (stored keys and defaults unchanged), and the lens tint reads the dye's
  `DataComponents.DYE`. The block entity's contents drop through 26.2's
  `preRemoveSideEffects`. New resources: the item definition
  `items/copper_beacon.json`; the recipe uses 26.2's ingredient syntax.
- **Screens.** `CopperBeaconScreen` draws through 26.2's GUI extraction
  (`extractBackground`, `extractLabels`); settings screens open through
  `Minecraft.gui.setScreen`; the Remix menu request is queued with
  `Minecraft.schedule`. Whole-number RTX sliders now step one value per arrow
  key, as vanilla 26.2 sliders do.
- **Zones** are a `SavedDataType` in the save-wide storage 26.1 introduced, so
  the file moved from `data/rtx_remix_zones.dat` to
  `data/rtx-remix-renderer/zones.dat`. A 1.21.1 save's file is read while the
  new one is missing and left in place; zones move to the new file on the
  first edit.
- **Time of day.** 26.x drives time with world clocks. Camera paths and still
  shots set the client's overworld clock for the frame being built and the
  server clock through `clockManager().setTotalTicks`. 26.2 only syncs clocks
  when they change, so a client-side edit in multiplayer is no longer reset
  within 20 ticks, and playback sends one time packet per frame.
- **Sky light.** `DimensionSpecialEffects` is gone: the sun light runs when
  `dimensionType().skybox()` is the overworld one, and the sun angle comes
  from the dimension's `SUN_ANGLE` attribute (`LightManager.skyAngle`), which
  follows 26.x's timeline easing.
- **Benchmark** records `graphicsPreset` in its metadata and hides the HUD
  through `gui.hud`.
- **Server chunk mixins.** `ChunkDataSenderMixin` keeps its five injectors (its
  drop hook now targets `ServerGamePacketListenerImpl.send`).
  `ChunkHolderMixin` and `ServerChunkManagerMixin` are removed: 26.2's
  `tickChunks` reuses one list and has no per-tick `ChunkAndHolder` records,
  so it already allocates nothing there. `ChunkSkyLightMixin`,
  `WorldChunkMixin` and `ClientChunkManagerMixin` take 26.2's heightmap map
  instead of the NBT. `DownloadingTerrainScreenMixin` re-arms the join hold in
  `LevelLoadingScreen.update`, because 26.2 reuses a loading screen that is
  still up for the next level.

## Mixins

All 76 mixins in `rtx-remix-renderer.mixins.json` keep `defaultRequire: 1`.
`MixinApplicationTest` (new, runs in `fabricTest`) loads every target class
under Fabric Loader and checks that each mixin applied to each target; it also
passed with `-Dmixin.debug.countInjections=true` and `-Dmixin.debug.strict=true`,
which make even the optional (`require = 0`) injectors fail when they match
nothing. Targets are written with 26.2's names; 1.21.1 names below are
Mojang's, as the source used them after the mappings step.

### Kept

| Mixin | 1.21.1 target | 26.2 target | What changed |
|---|---|---|---|
| `BakedQuadMixin` | `BakedQuad` | `BakedQuad` | Nothing in the mixin; `BakedQuad` is a record in 26.x |
| `BillboardParticleAccessor` | `SingleQuadParticle` | `SingleQuadParticle` | Also reads colour and roll and invokes the sprite and `getLayer`, which `ParticleAccessor` used to hold |
| `BiomeMixin` | `Biome` | `Biome` | Caches foliage and dry foliage only; 26.2's grass colour path no longer allocates |
| `BlockBufferBuilderPoolMixin` | `SectionBufferBuilderPool` | same | Unchanged |
| `BlockEntityRenderDispatcherMixin` | `BlockEntityRenderDispatcher` | same | The capture wrap moved from `render(..., MultiBufferSource)` to `submit(BlockEntityRenderState, PoseStack, SubmitNodeCollector, CameraRenderState)`; new hook on `tryExtractRenderState` records the owner |
| `BuiltChunkMixin` | `SectionRenderDispatcher.RenderSection` | same | `setOrigin` became `setSectionNode`, `setCompiled` became `setSectionMesh`; the neighbour probe at `createCompileTask` stays |
| `BuiltChunkStorageMixin` | `ViewArea` | same | The reposition override is gone (vanilla returns early itself); only the slot access interface remains |
| `ChunkDataSenderMixin` | `PlayerChunkSender` | same | Same five injectors; the drop hook targets `ServerGamePacketListenerImpl.send` |
| `ChunkLightProviderAccessor` | `LightEngine` | same | Unchanged |
| `ChunkRendererRegionBuilderMixin` | `RenderRegionCache` | same | Works over `sectionCopyCache` |
| `ChunkRendererRegionMixin` | `RenderChunkRegion` | `RenderSectionRegion` | Renamed class, now 27 section copies |
| `ChunkRenderingDataPreparerMixin` | `SectionOcclusionGraph` | same | Walk follows 26.2 (loaded-chunk and empty-section gates, octree, far probe); the executor is applied by wrapping `runAsync` in `scheduleFullUpdate` instead of a synthetic lambda; new `updateEmptySections` hook |
| `ChunkSkyLightMixin` | `ChunkSkyLightSources` | same | The shadowed `isEdgeOccluded` takes two block states; 26.2 light no longer depends on position |
| `ClientChunkManagerMixin` | `ClientChunkCache` | same | Packet handlers take the heightmap map instead of NBT |
| `ClientWorldColorMixin` | `ClientLevel` | same | Unchanged |
| `ClientWorldMixin` | `ClientLevel` | same | Unchanged |
| `CuboidMixin` | `ModelPart.Cube` | same | Same hook; `polygons` is public now |
| `DownloadingTerrainScreenMixin` | `ReceivingLevelScreen` | `LevelLoadingScreen` | `onClose` hook kept; new `update` hook re-arms the join hold, since 26.2 reuses the screen for the next level |
| `EntityRenderDispatcherMixin` | `EntityRenderDispatcher` | same | `render` became `submit(EntityRenderState, ...)`; new hook on `extractEntity` records the owner |
| `FoliageColorsMixin` | `FoliageColor` | `FoliageColor`, `DryFoliageColor` | Covers the new dry foliage map too |
| `FrustumAccessor` | `Frustum` | same | Unchanged |
| `GameRendererMixin` | `GameRenderer` | same | Frame submit before `ScreenEffectRenderer.submit` and at the end of `renderLevel`; GUI profiling around `GuiRenderer.render`; Remix menu follow at `extract`; first-person hook moved to `FirstPersonCaptureMixin` |
| `GlyphAtlasTextureMixin` | `FontTexture` | same | Wraps `GlyphBitmap.upload` in `FontTexture.add` |
| `HeldItemRendererMixin` | `ItemInHandRenderer` | same | `renderArmWithItem` became `submitArmWithItem`; the holder hooks moved to `ItemModelResolverMixin` |
| `KeyboardMixin` | `KeyboardHandler` | same | 1.21.9's `KeyEvent` and `CharacterEvent` signatures; new `preeditCallback` cancel while the Remix menu has input |
| `LightStorageMixin` | `LayerLightSectionStorage` | same | Same hook; needed class tweaker entries only |
| `MinecraftClientMixin` | `Minecraft` | same | Frame hooks moved to `renderFrame`; new backend preference hook in the constructor; camera-path poses before `GameRenderer.update`; present fallback before `LevelRenderer.endFrame`; tick hooks retargeted; `Thread.yield` and `LevelRenderer.tick` hooks dropped |
| `ModelPartMixin` | `ModelPart` | same | Same hooks; the singleton-list check follows 26.x's `Stream.toList()` lists |
| `MouseMixin` | `MouseHandler` | same | `MouseButtonInfo` signatures; synthetic releases go through `onButton` |
| `NativeImageBackedTextureMixin` | `DynamicTexture` | same | Skips closed images |
| `PackedIntegerArrayMixin` | `SimpleBitStorage` | same | Unchanged target |
| `ParticleAccessor` | `Particle` | same | Position and the removed flag only |
| `ParticleManagerAccessor` | `ParticleEngine` | same | `particles` is the group map; `getResources` replaces `getSpriteAwareFactories` |
| `ParticleManagerMixin` | `ParticleEngine` | same | Tick hooks count through the groups; an `extract` cancel replaces the `ParticleEngine.render` redirect; per-list ticking moved to `ParticleGroupMixin` |
| `ParticleStateMixin` | `Particle` | same | The collision redirect targets `Entity.collideBoundingBox(CollisionContext, ...)` |
| `RebuildTaskMixin` | `RenderSection$RebuildTask` | `RenderSection$CompileTask` | Renamed class; releases its region when `doTask` returns |
| `RenderableChunksMixin` | `SectionOcclusionGraph$GraphStorage` | same | Also reuses the waiting-for-chunk map and the octree (`PooledOctree`) |
| `RenderedChunkMixin` | `RenderChunk` | `SectionCopy` | Wraps `PalettedContainer.copy()` in the constructor to take pooled storage (replaces `ClientChunkMixin`) |
| `SectionBuilderMixin` | `SectionCompiler` | same | Per-block hook wraps `RenderSectionRegion.getBlockState`; `renderLiquid` became a wrap of `FluidRenderer.tesselate` |
| `SpriteAtlasAccessor` | `TextureAtlas` | same | Accessors renamed `getAtlasWidth`/`getAtlasHeight`, because 26.2 added package-private `getWidth()`/`getHeight()` |
| `SpriteAtlasTextureMixin` | `TextureAtlas` | same | `clearTextureData`; wraps `createAnimationState` in `upload`; forwarding when `cycleAnimationFrames` returns |
| `SpriteContentsAccessor` | `SpriteContents` | same | Unchanged |
| `TextureManagerMixin` | `TextureManager` | same | `HttpTexture` branch dropped |
| `ThreadExecutorMixin` | `BlockableEventLoop` | same | Unchanged |
| `TorchBlockAccessor` | `TorchBlock` | same | Unchanged |
| `UnfocusedFramerateMixin` | `Minecraft` | `FramerateLimitTracker` | Hooks the return of `getFramerateLimit()` |
| `WallBlockMixin` | `WallBlock` | same | Unchanged |
| `WindowMixin` | `Window` | same | Only the handle log remains; the context hints moved to `GlBackendMixin` |
| `WorldChunkMixin` | `LevelChunk` | same | Same hook; the handler takes the heightmap map instead of NBT |
| `WorldRendererMixin` | `LevelRenderer` | same | Reduced to the start and end of `render` and the profiler phase mirror; its other hooks moved to the new mixins below |

### New

| Mixin | 26.2 target | Replaces or does |
|---|---|---|
| `BitmapGlyphSourceMixin` | `BitmapProvider$ImageDataHolder` | Records bitmap font sheets for the glyph mirror |
| `ChunkSectionsToRenderMixin` | `ChunkSectionsToRender` | Cancels vanilla's terrain draw while Remix renders (was `WorldRendererMixin` on `renderSectionLayer`) |
| `CommandEncoderUploadMixin` | `CommandEncoder` | Glyph page mirroring at `writeToTexture` and `copyBufferToTexture` (was `NativeImageUploadMixin`, `GlStateManagerUploadMixin`) |
| `FirstPersonCaptureMixin` | `GameRenderer` | First-person capture around `submitHandsWithItems` (was in `GameRendererMixin`) |
| `GlBackendMixin` | `GlBackend` | OpenGL 4.4 core context hints after `setWindowHints` (was in `WindowMixin`) |
| `GlSurfaceMixin` | `GlSurface` | Remix present in place of `glfwSwapBuffers` in `present()` (was `RenderSystemMixin` on `RenderSystem.flipFrame`) |
| `ItemModelResolverMixin` | `ItemModelResolver` | Records the item's block and held-flame decision (part of the old `ItemRendererMixin`) |
| `ItemStackLayerRenderStateMixin` | `ItemStackRenderState$LayerRenderState` | Emits the held flame after the item's quads (part of `ItemRendererMixin`) |
| `ItemStackRenderStateMixin` | `ItemStackRenderState` | Block-item layer and flame around `submit` (part of `ItemRendererMixin`) |
| `LevelRendererCaptureMixin` | `LevelRenderer` | Entity and block-damage capture in `submitEntities` and `submitBlockDestroyAnimation` (was in `WorldRendererMixin`) |
| `ParticleGroupAccessor` | `ParticleGroup` | The group's particle queue |
| `ParticleGroupMixin` | `ParticleGroup` | Per-group tick hooks (was `ParticleManagerMixin.tickParticleList`) |
| `ParticleResourcesAccessor` | `ParticleResources` | Vanilla's sprite sets |
| `RenderStateOwnerMixin` | `EntityRenderState`, `BlockEntityRenderState` | Owner tag for retained instances |
| `SpriteAnimatedTextureAccessor` | `SpriteContents$AnimatedTexture` | Animation frame data |
| `SpriteAnimationStateAccessor` | `SpriteContents$AnimationState` | Current frame and its sprite |
| `TerrainChunkLoadReflagMixin` | `ClientPacketListener` | Re-flag filter on the chunk-load path (was in `WorldRendererMixin`) |
| `TerrainLevelExtractorMixin` | `LevelExtractor` | Water occlusion margin, light re-flag filter, the frame's region cache (were in `WorldRendererMixin`) |
| `TerrainLevelRendererMixin` | `LevelRenderer` | `WorldRendererTerrainAccess` and terrain profiling scopes (were in `WorldRendererMixin`) |
| `TextureAtlasSpriteAccessor` | `TextureAtlasSprite` | Sprite padding |
| `VanillaCloudRendererMixin` | `CloudRenderer` | Cancels vanilla's cloud draw (was `WorldRendererMixin` on `renderClouds`) |
| `VanillaSkyRendererMixin` | `SkyRenderer` | Captures the star mesh (was `WorldRendererMixin` on `drawStars`) |
| `WeatherEffectRendererAccessor` | `WeatherEffectRenderer` | Vanilla's rain and snow column builder |
| `WeatherEffectRendererMixin` | `WeatherEffectRenderer` | Cancels and captures vanilla's weather draw (were `WorldRendererMixin`'s `renderSnowAndRain` hooks) |

### Removed

| Mixin | 1.21.1 target | Why |
|---|---|---|
| `BuiltChunkBuffersMixin` | `RenderSection` | 26.2 has no per-section vertex buffers |
| `ChunkHolderMixin` | `ChunkHolder` | 26.2's `tickChunks` has no per-tick `ChunkAndHolder` records to pool |
| `ClientChunkMixin` | `RenderRegionCache$ChunkInfo` | Class removed; pooling moved to `RenderedChunkMixin` |
| `ClientChunkSnapshotAccess` | `RenderRegionCache$ChunkInfo` | Class removed |
| `Deadmau5FeatureRendererMixin` | `Deadmau5EarsLayer` | Ears are decided once in the `AbstractClientPlayer` constructor |
| `EntityRenderDispatcherAccessor` | `EntityRenderDispatcher` | `shouldRenderShadow` is gone |
| `GlStateManagerUploadMixin` | `GlStateManager` | Glyph uploads go through `CommandEncoder` |
| `GrassColorsMixin` | `GrassColor` | Nothing left to invalidate |
| `IndigoMeshMixin` | Indigo `MeshImpl` | The capture uses the renderer's own emitter; the target method moved |
| `ItemRendererMixin` | `ItemRenderer` | Class removed; replaced by the three item render state mixins |
| `LivingEntityRendererNameMixin` | `LivingEntityRenderer` | The upside-down check reads `getCustomName` |
| `MatrixStackAccessor` | `PoseStack` | `PoseStack` pools its own entries |
| `MatrixStackEntryAccessor` | `PoseStack.Pose` | Same |
| `MultipartBakedModelMixin` | `MultiPartBakedModel` | Vanilla part selection no longer allocates |
| `NativeImageAccessor` | `NativeImage` | Replaced by `NativeImagePixels` and the new accessors |
| `NativeImageUploadMixin` | `NativeImage` | Glyph uploads go through `CommandEncoder` |
| `PlayerEntityNameMixin` | `Player` | No users left |
| `PlayerSkinTextureMixin` | `HttpTexture` | Skins arrive as `DynamicTexture`s |
| `RenderSystemMixin` | `RenderSystem` | The buffer swap moved to `GlSurface.present` |
| `ServerChunkManagerMixin` | `ServerChunkCache` | Same as `ChunkHolderMixin` |
| `SpriteBillboardParticleAccessor` | `TextureSheetParticle` | Class removed |
| `SpriteContentsUploadMixin` | `SpriteContents` | Vanilla animates atlases on the GPU; forwarding moved to `SpriteAtlasTextureMixin` |
| `VertexBufferMixin` | `VertexBuffer` | Class removed; 26.2 specifies buffer storage once, which was the leak it worked around |
| `WeightedBakedModelMixin` | `WeightedBakedModel` | Vanilla variant selection no longer allocates |

## Stubbed, Changed Or Uncertain

**Stubbed.** One place, marked `PORT-STUB(26.2)` in the source (the
repository's public-boundary check rejects the word the task suggested):

- `RemixVertexConsumerProvider.submitCustom`: Fabric's custom submit nodes,
  which only modded renderers produce, are neither captured nor drawn while
  an entity is captured. 1.21.1 had no such nodes; modded geometry reached the
  capture through `MultiBufferSource.getBuffer` like vanilla's.

**Not re-implemented**, because 26.2 vanilla already does the job:

- The block-entity chunk narrowing (`iterateBlockEntityChunks`):
  `LevelExtractor.extractVisibleBlockEntities` skips sections without block
  entities cheaply.
- The terrain frustum index, slot move log and reposition override, the
  allocation ports of model selection and the server's per-tick chunk list,
  and the dynamic vertex buffer reuse; see the subsystem sections.

**Behaviour that differs from 1.21.1:**

- Toggle Held Lights defaults to H (vanilla took G for Quick Actions).
- Zones are saved to `data/rtx-remix-renderer/zones.dat`; a 1.21.1 save's
  `data/rtx_remix_zones.dat` is read until the first zone edit.
- In multiplayer, a client-side time edit (camera paths, still shots) is no
  longer corrected within 20 ticks, and playback sends one time packet per
  frame, because 26.2 syncs world clocks only when they change.
- The sun angle follows 26.x's timeline easing, as vanilla's sun does.
- Classic clouds follow game time, as vanilla 26.2's do.
- Whole-number RTX sliders step one value per arrow key.
- Remix depth for the weather pass goes to a private depth buffer instead of
  overwriting the main target's.
- Item textures live on 26.x's item atlas, which takes the generic material
  path rather than the block atlas special cases.
- The off-frustum entity sweep allocates one render state per swept entity
  per frame.
- Benchmark metadata records the graphics preset name in `graphicsMode`, and
  the developer tools write 26.2's split graphics options.
- Stars appear once vanilla's sky renderer has been created.

**Uncertain until run in game with Remix** (also in the checklist below):

- Whether vanilla's visible section set is reproduced exactly by the ported
  walk on a live world.
- First-person view-model scale and field of view, glowing outlines, held
  flames, block cracks and item materials under the new submit capture.
- Atlas padding offsets in terrain, entity and particle materials, and
  interpolated animated textures.
- Weather depth testing against Remix depth under 26.2's zero-to-one clip
  depth, and that the raw OpenGL passes leave Blaze3d's state intact.
- Memory with the debug chart open, now that `VertexBufferMixin` is gone.
- Whether the RTX Remix Materials pack loads on 26.2 (its `pack.mcmeta` was
  written for 1.21.1) and whether any texture it replaces was renamed.

## Installer, Developer Tools And Docs

- `packaging/installer.ps1`: Minecraft 26.2, Loader 0.20.1, Fabric API
  0.161.0+26.2, Fabric installer 1.1.2, options data version 4903 (26.2's
  `world_version`). Java moved to one `GAME_JAVA_MAJOR` (25): the managed JDK
  (`jdk-25`), the Adoptium download, the ARM64 checks, and the Mojang runtime
  preference (`java-runtime-epsilon` first). Reusing an existing Prism
  instance now also rewrites its Minecraft and Loader versions and its Java
  version, because an instance a 1.21.1 release created would otherwise stay
  on 1.21.1; an ARM64 Java pin to an older Java is replaced.
- Python tools: `GAME_JAVA_MAJOR` 25 (Prism lists only Java 25 for 26.2);
  the game-window title match accepts year-numbered versions ("Minecraft
  26.2"); the benchmark's `graphicsMode` pin is written as 26.2's split
  graphics options (`cutoutLeaves`, `weatherRadius`, `vignette`,
  `improvedTransparency`, preset Custom) with the values Mojang's own data
  fixer uses, and a new `options.txt` carries data version 4903; messages
  and their tests follow. `tools\rtx.bat check --tests` passes.
- Docs: README, `docs/PLAYING.md`, `packaging/INSTALL.md`,
  `packaging/INSTALLER_REFERENCE.md`, `docs/GETTING_STARTED.md`, `AGENTS.md`,
  `CONTRIBUTING.md` (also the bytecode-reading section, which was written for
  Yarn), `docs/TUNING.md`, the tool and wrapper READMEs, the release-content
  READMEs and `THIRD_PARTY_NOTICES.md` now name 26.2, Java 25 and the new
  Fabric versions. Sentences that describe how 1.21.1 itself behaved
  (`CONTRIBUTING.md` cancellation examples, `docs/SETTINGS.md` slider note,
  `tools/docs/BENCHMARK_FIXTURES.md` world format) were left as they are.
- `AGENTS.md` architecture and invariants, `docs/RENDERING_DIAGNOSTICS.md`
  and `CONTRIBUTING.md` name the 26.2 classes (`LevelRenderer`,
  `LevelExtractor`, `SectionOcclusionGraph`, the new mixins); `CONTRIBUTING.md`
  also describes the mixin field initializer trap and the test that catches
  it. The zone file's new path is in `docs/TUNING.md`, `packaging/INSTALL.md`,
  the logic README and `tools/docs/BENCHMARK_FIXTURES.md`, and
  `tools/docs/PROFILING.md` no longer points at the removed buffer workaround.
- The logic README ships in the runtime payload, so its new hash is appended
  to `rtx-remix-shipped-payload.sha256`.

## Windows Test Checklist

Run on Windows 10 or 11 with a GeForce RTX 30-series or newer GPU and driver
616.92 or newer.

1. **Build.** `.\gradlew.bat build test` with JDK 25 and the Visual Studio C++
   tools; `.\gradlew.bat package -Parch=win64`; `.\tools\rtx.bat check --tests`.
2. **Install.** Run the installer bundle on a clean machine and over a 1.21.1
   install: the Prism instance must end up on 26.2 with Java 25, and the
   Microsoft launcher path must create the `fabric-loader-26.2` profile.
3. **Startup.** `logs/latest.log` shows `Using graphics backend OpenGL` and
   `RTX Remix renderer initialized successfully!`, and no mixin errors (every
   injector is required). With the graphics API option set to Vulkan, the log
   warns and OpenGL is still used.
4. **`/rtx status`** reports `Renderer Initialized: true` and
   `SDK Available: true`.
5. **F8** toggles RTX on and off, and the world recaptures each time; **H**
   toggles held lights; **Alt+X** opens and closes the Remix menu, and input
   returns to the game afterwards.
6. **Showcase world** (NVIDIA RTX Demo): opens (Minecraft upgrades the 1.21.1
   save), terrain complete with no holes, water, leaves (fast and fancy),
   glass, tripwire, lava, flowing fluids, biome tints including dry foliage,
   block updates, emptied sections disappearing, and chunk loading at high
   render distance. Change a setting that recaptures terrain and check the
   world comes back whole. Run once with `-Drtxremix.logVisibleSet=true` with
   and once without `-Drtxremix.vanillaVisibilityWalk=true`, at the same spot,
   and compare the last logged sets.
7. **Materials pack** (RTX Remix Materials): listed and enabled; normals,
   roughness, emissive and subsurface maps show; check whether 26.2 marks the
   pack incompatible (its `pack.mcmeta` was written for 1.21.1) and whether
   any vanilla texture it replaces was renamed.
8. **Lighting:** torches, lanterns and glowstone; held torches in both hands;
   underwater, lava and powder-snow fog; time-of-day and weather profiles.
9. **Entities:** mobs, players and their skins, item frames, armor stands,
   banners, signs, chests, beds, name tags, dropped items and their
   materials, enchantment glint, glowing outlines, block cracks, first-person
   hands and held items (scale and field of view), held flames,
   off-screen entities in reflections and shadows.
10. **Sky and clouds:** sun, moon and stars, with the Remix sun where
    vanilla's sun is; sunrise colours, procedural and Classic clouds, the End
    and the Nether.
11. **Particles and weather:** rain and snow hidden behind walls (depth test
    against Remix depth), splashes, smoke, flames, water rapids particles.
12. **Textures:** animated textures, interpolated ones such as prismarine
    included; no offset seams on atlas sprites in terrain, entities or
    particles; LabPBR maps on entities.
13. **Memory:** with the F3 debug chart open for a few minutes, memory stays
    flat (the 1.21.1 buffer-orphaning workaround is gone).
14. **Camera paths and zones:** play a camera path and take still shots
    (time of day follows, the HUD hides for stills); in a 1.21.1 save with
    zones, the zones load.
15. **Copper Beacon:** craft or take it from the creative tab, place it,
    open its screen, change its axes, dye the lens, break it (the dye drops),
    check the beam light, the lens texture and the held beacon.
16. **Multiplayer:** join a dedicated 26.2 server with the mod on the client
    only, then with the mod on both sides (chunk delivery pacing, Copper
    Beacon sync).
17. **`/rtx benchmark`** runs to completion and writes its results.

## Beyond 26.2

- **26.3** (the newest stable at the time of the port) replaces GLFW with SDL
  for the window and input, moves `com.mojang.blaze3d` to
  `com.mojang.renderpearl`, compiles shaders with shaderc (layout locations,
  `#include`), and removes `RenderSystem.outputColorTextureOverride`. The
  window handle for Remix, the keyboard and mouse mixins, and the present hook
  would move again.
- Mojang plans to remove the OpenGL backend once Vulkan is stable. The Remix
  composite imports Remix's images into OpenGL, so that change will need a
  Vulkan interop path instead.
