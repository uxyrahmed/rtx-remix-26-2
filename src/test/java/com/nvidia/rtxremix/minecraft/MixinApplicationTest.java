/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Executable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads every class the mod's mixins target, without initializing it, so each
 * mixin is applied under Fabric Loader, then checks what Mixin made of them.
 *
 * <p>The config's {@code defaultRequire} of 1 turns an injection point that no
 * longer matches the game into a load failure here rather than at launch. Each
 * mixin must also leave a mark on each target: an accessor or other interface
 * mixin is implemented by the target, and a class mixin merged at least one
 * member, which Mixin tags with {@link MixinMerged}.
 *
 * <p>Field initializers get their own check, because Mixin drops one without a
 * warning when it cannot tell it apart from the mixin constructor's body, which
 * JDK 25's line numbers can cause. With {@code mixin.debug.export} on, as the
 * {@code fabricTest} task sets it, every initializer a mixin declares must
 * reach every target constructor that calls a super constructor.
 *
 * <p>The targets are read from the config and the mixins' {@code @Mixin}
 * annotations rather than from Mixin's registry, which drops a config once it
 * has been selected.
 */
@Tag("fabric")
class MixinApplicationTest {

    private static final String CONFIG = "rtx-remix-renderer.mixins.json";
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Path EXPORTED_CLASSES = Path.of(".mixin.out", "class");

    private record MixinSpec(ClassNode node, List<String> targets) {
        boolean isInterface() {
            return (node.access & Opcodes.ACC_INTERFACE) != 0;
        }
    }

    @Test
    void everyMixinAppliesToEveryTarget() throws IOException {
        Map<String, MixinSpec> mixins = readMixins();
        List<String> failures = new ArrayList<>();
        Map<String, Class<?>> loaded = loadTargets(mixins, failures);
        for (Map.Entry<String, MixinSpec> entry : mixins.entrySet()) {
            String mixin = entry.getKey();
            MixinSpec spec = entry.getValue();
            for (String target : spec.targets()) {
                Class<?> targetClass = loaded.get(target);
                if (targetClass != null && !applied(mixin, spec, targetClass)) {
                    failures.add(mixin + " left no trace on " + target);
                }
            }
        }
        assertTrue(failures.isEmpty(),
                failures.size() + " mixin problem(s):\n" + String.join("\n", failures));
    }

    @Test
    void everyMixinFieldInitializerReachesTheTargetConstructors() throws IOException {
        assumeTrue(MixinEnvironment.getCurrentEnvironment().getOption(MixinEnvironment.Option.DEBUG_EXPORT),
                "needs -Dmixin.debug.export=true, which the fabricTest task sets");
        Map<String, MixinSpec> mixins = readMixins();
        List<String> failures = new ArrayList<>();
        loadTargets(mixins, failures);
        for (Map.Entry<String, MixinSpec> entry : mixins.entrySet()) {
            String mixin = entry.getKey();
            MixinSpec spec = entry.getValue();
            Set<String> initialized = initializedFields(spec.node());
            if (initialized.isEmpty()) {
                continue;
            }
            for (String target : spec.targets()) {
                Path exported = EXPORTED_CLASSES.resolve(target.replace('.', '/') + ".class");
                if (!Files.isRegularFile(exported)) {
                    failures.add(target + " was not exported to " + exported.toAbsolutePath());
                    continue;
                }
                ClassNode transformed = new ClassNode();
                new ClassReader(Files.readAllBytes(exported)).accept(transformed, 0);
                for (MethodNode constructor : superCallingConstructors(transformed)) {
                    for (String field : initialized) {
                        if (!storesField(constructor, transformed.name, field)) {
                            failures.add(mixin + " initializes " + field + ", but " + target
                                    + ".<init>" + constructor.desc + " never sets it");
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(),
                failures.size() + " dropped initializer(s):\n" + String.join("\n", failures));
    }

    /** Loads every target once, recording the classes that failed to load. */
    private static Map<String, Class<?>> loadTargets(Map<String, MixinSpec> mixins, List<String> failures) {
        ClassLoader loader = MixinApplicationTest.class.getClassLoader();
        Map<String, Class<?>> loaded = new LinkedHashMap<>();
        for (MixinSpec spec : mixins.values()) {
            for (String target : spec.targets()) {
                if (loaded.containsKey(target)) {
                    continue;
                }
                try {
                    loaded.put(target, Class.forName(target, false, loader));
                } catch (Throwable failure) {
                    loaded.put(target, null);
                    failures.add(target + " failed to load: " + describe(failure));
                }
            }
        }
        return loaded;
    }

    private static boolean applied(String mixin, MixinSpec spec, Class<?> target) {
        Set<String> interfaces = new LinkedHashSet<>();
        for (Class<?> implemented : target.getInterfaces()) {
            interfaces.add(implemented.getName());
        }
        if (spec.isInterface()) {
            return interfaces.contains(mixin);
        }
        for (String declared : spec.node().interfaces) {
            if (interfaces.contains(declared.replace('/', '.'))) {
                return true;
            }
        }
        List<Executable> members = new ArrayList<>(List.of(target.getDeclaredMethods()));
        members.addAll(List.of(target.getDeclaredConstructors()));
        for (Executable member : members) {
            MixinMerged merged = member.getAnnotation(MixinMerged.class);
            if (merged != null && mixin.equals(merged.mixin())) {
                return true;
            }
        }
        return false;
    }

    /** Instance fields of its own that a mixin's constructors assign: its field initializers. */
    private static Set<String> initializedFields(ClassNode mixin) {
        Set<String> fields = new LinkedHashSet<>();
        for (MethodNode method : mixin.methods) {
            if (!"<init>".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode field
                        && field.owner.equals(mixin.name)) {
                    fields.add(field.name);
                }
            }
        }
        return fields;
    }

    /** Constructors that call a super constructor; Mixin adds initializers to these only. */
    private static List<MethodNode> superCallingConstructors(ClassNode target) {
        List<MethodNode> constructors = new ArrayList<>();
        for (MethodNode method : target.methods) {
            if (!"<init>".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.INVOKESPECIAL && insn instanceof MethodInsnNode call
                        && "<init>".equals(call.name)) {
                    if (call.owner.equals(target.superName)) {
                        constructors.add(method);
                    }
                    break;
                }
            }
        }
        return constructors;
    }

    private static boolean storesField(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode field
                    && field.owner.equals(owner) && field.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Every mixin in the config, by class name, in config order. */
    private static Map<String, MixinSpec> readMixins() throws IOException {
        ClassLoader loader = MixinApplicationTest.class.getClassLoader();
        JsonObject config;
        try (InputStream in = loader.getResourceAsStream(CONFIG)) {
            assertTrue(in != null, CONFIG + " is not on the class path");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                config = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
        String mixinPackage = config.get("package").getAsString();
        Map<String, MixinSpec> mixins = new LinkedHashMap<>();
        for (String section : List.of("mixins", "client", "server")) {
            JsonArray names = config.getAsJsonArray(section);
            if (names == null) {
                continue;
            }
            for (JsonElement name : names) {
                String mixin = mixinPackage + "." + name.getAsString();
                mixins.put(mixin, readMixin(loader, mixin));
            }
        }
        assertFalse(mixins.isEmpty(), CONFIG + " lists no mixins");
        return mixins;
    }

    /** A mixin's bytes and its {@code @Mixin} targets. */
    private static MixinSpec readMixin(ClassLoader loader, String mixin) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = loader.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
            assertTrue(in != null, "Mixin class " + mixin + " is not on the class path");
            new ClassReader(in).accept(node, 0);
        }
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.invisibleAnnotations != null) {
            annotations.addAll(node.invisibleAnnotations);
        }
        if (node.visibleAnnotations != null) {
            annotations.addAll(node.visibleAnnotations);
        }
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : annotations) {
            if (!MIXIN_ANNOTATION.equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                String key = (String) annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                if ("value".equals(key)) {
                    for (Object type : (List<?>) value) {
                        targets.add(((Type) type).getClassName());
                    }
                } else if ("targets".equals(key)) {
                    for (Object name : (List<?>) value) {
                        targets.add(((String) name).replace('/', '.'));
                    }
                }
            }
        }
        assertFalse(targets.isEmpty(), mixin + " has no @Mixin targets");
        return new MixinSpec(node, targets);
    }

    private static String describe(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!chain.isEmpty()) {
                chain.append(" <- ");
            }
            chain.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            if (cause.getCause() == cause) {
                break;
            }
        }
        return chain.toString();
    }
}
