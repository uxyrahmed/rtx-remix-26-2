/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ZonePersistentStateTest {

    /** The zone data has no fixes, so an identity fixer stands in for the game's. */
    private static final DataFixer IDENTITY_FIXER = new DataFixer() {
        @Override
        public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion) {
            return input;
        }

        @Override
        public Schema getSchema(int key) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void sphereRoundTripsThroughNbt() {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define cave --center 10.5 20 -30.25 --inner 8 --outer 24 --channel mc.zone.underground",
                0, 0, 0);

        ZonePersistentState loaded = roundTrip(zone);

        assertEquals(1, loaded.snapshot().size());
        assertEquals(zone, loaded.snapshot().iterator().next());
    }

    @Test
    void normalizedBoxRoundTripsThroughNbt() {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define hall --box 10 20 30 -5 4 6 --fade 12 --channel mc.zone.interior",
                0, 0, 0);

        ZoneDefinition restored = roundTrip(zone).snapshot().iterator().next();

        assertEquals(zone, restored);
        assertEquals(-5.0, restored.minX());
        assertEquals(10.0, restored.maxX());
        assertEquals(4.0, restored.minY());
        assertEquals(20.0, restored.maxY());
    }

    @Test
    void effectiveChangesAloneDirtyTheState() {
        ZonePersistentState state = new ZonePersistentState();
        ZoneDefinition first = ZoneDefinition.parse(
                "rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0);
        ZoneDefinition moved = ZoneDefinition.parse(
                "rtx zone define cave --center 100 0 0 --inner 5 --outer 10", 0, 0, 0);

        assertFalse(state.isDirty());
        assertTrue(state.put(first));
        assertTrue(state.isDirty());

        state.setDirty(false);
        assertFalse(state.put(first));
        assertFalse(state.isDirty(), "an identical redefinition must not dirty the save");

        assertTrue(state.put(moved));
        assertTrue(state.isDirty());

        state.setDirty(false);
        assertFalse(state.remove("missing"));
        assertFalse(state.isDirty());
        assertTrue(state.remove("cave"));
        assertTrue(state.isDirty());
    }

    @Test
    void malformedEntryDoesNotDiscardValidZones() {
        ZoneDefinition valid = ZoneDefinition.parse(
                "rtx zone define valid --center 1 2 3 --inner 4 --outer 8", 0, 0, 0);
        ZonePersistentState source = new ZonePersistentState();
        source.put(valid);
        CompoundTag nbt = source.writeNbt(new CompoundTag());

        CompoundTag malformed = new CompoundTag();
        malformed.putString("name", "broken");
        malformed.putString("channel", "mc.zone.broken");
        malformed.putString("shape", "sphere");
        nbt.getListOrEmpty("zones").add(malformed);

        ZonePersistentState loaded = ZonePersistentState.fromNbt(nbt);

        assertEquals(1, loaded.snapshot().size());
        assertEquals(valid, loaded.snapshot().iterator().next());
    }

    @Test
    void unsupportedSchemaLoadsAsEmptyWithoutDirtying() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("version", 999);
        nbt.put("zones", new ListTag());

        ZonePersistentState loaded = ZonePersistentState.fromNbt(nbt);

        assertTrue(loaded.snapshot().isEmpty());
        assertFalse(loaded.isDirty());
        assertThrows(IllegalStateException.class,
                () -> loaded.put(ZoneDefinition.parse("rtx zone define newer", 0, 0, 0)));
        assertThrows(IllegalStateException.class, () -> loaded.remove("anything"));
        assertFalse(loaded.isDirty(), "an older mod must never overwrite a newer schema");
    }

    @Test
    void duplicateNamesUseLastDefinition() {
        ZoneDefinition first = ZoneDefinition.parse(
                "rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0);
        ZoneDefinition second = ZoneDefinition.parse(
                "rtx zone define cave --center 100 0 0 --inner 5 --outer 10", 0, 0, 0);

        CompoundTag firstNbt = writeOne(first);
        CompoundTag secondNbt = writeOne(second);
        ListTag zones = new ListTag();
        zones.add(firstNbt);
        zones.add(secondNbt);
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        root.put("zones", zones);

        ZoneDefinition restored = ZonePersistentState.fromNbt(root).snapshot().iterator().next();

        assertEquals(second, restored);
        assertEquals(1.0f, restored.weight(100, 0, 0), 1e-6f);
    }

    @Test
    void snapshotIsImmutableAndNameSorted() {
        ZonePersistentState state = new ZonePersistentState();
        state.put(ZoneDefinition.parse("rtx zone define zed", 0, 0, 0));
        state.put(ZoneDefinition.parse("rtx zone define alpha", 0, 0, 0));

        Collection<ZoneDefinition> snapshot = state.snapshot();
        ArrayList<String> names = new ArrayList<>();
        snapshot.forEach(zone -> names.add(zone.name()));

        assertEquals(List.of("alpha", "zed"), names);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void writesMinecraftSavedDataFile(@TempDir Path tempDir) throws Exception {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define saved --center 4 5 6 --inner 7 --outer 11", 0, 0, 0);
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);

        ensureGameVersionForPersistentStateSave();
        SavedDataStorage storage = storage(tempDir);
        storage.set(ZonePersistentState.TYPE, state);
        storage.saveAndJoin();

        assertFalse(state.isDirty());
        Path file = tempDir.resolve("rtx-remix-renderer").resolve("zones.dat");
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        ZonePersistentState loaded = ZonePersistentState.fromNbt(root.getCompoundOrEmpty("data"));
        assertEquals(List.of(zone), new ArrayList<>(loaded.snapshot()));
    }

    @Test
    void readsTheFileWrittenBeforeSavedDataHadIdentifiers(@TempDir Path tempDir) throws Exception {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define legacy --box 0 0 0 8 8 8 --fade 2", 0, 0, 0);
        ZonePersistentState source = new ZonePersistentState();
        source.put(zone);
        ensureGameVersionForPersistentStateSave();
        CompoundTag root = new CompoundTag();
        root.put("data", source.writeNbt(new CompoundTag()));
        NbtUtils.addCurrentDataVersion(root);
        Path legacyFile = tempDir.resolve("rtx_remix_zones.dat");
        NbtIo.writeCompressed(root, legacyFile);

        ZonePersistentState loaded = ZonePersistentState.readLegacyFile(storage(tempDir), legacyFile);

        assertEquals(List.of(zone), new ArrayList<>(loaded.snapshot()));
        assertFalse(loaded.isDirty(), "reading the old file must not rewrite the save");
        assertNull(ZonePersistentState.readLegacyFile(storage(tempDir), tempDir.resolve("missing.dat")));
    }

    private static SavedDataStorage storage(Path dataFolder) {
        return new SavedDataStorage(dataFolder, IDENTITY_FIXER, HolderLookup.Provider.create(Stream.empty()));
    }

    private static ZonePersistentState roundTrip(ZoneDefinition zone) {
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        CompoundTag nbt = state.writeNbt(new CompoundTag());
        return ZonePersistentState.fromNbt(nbt);
    }

    private static CompoundTag writeOne(ZoneDefinition zone) {
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        return state.writeNbt(new CompoundTag())
                .getListOrEmpty("zones")
                .getCompoundOrEmpty(0)
                .copy();
    }

    private static void ensureGameVersionForPersistentStateSave() {
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
    }
}
