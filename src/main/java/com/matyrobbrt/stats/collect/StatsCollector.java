package com.matyrobbrt.stats.collect;

import com.matyrobbrt.stats.db.InheritanceDB;
import com.matyrobbrt.stats.db.ModIDsDB;
import com.matyrobbrt.stats.db.ProjectsDB;
import com.matyrobbrt.stats.db.RefsDB;
import cpw.mods.jarhandling.SecureJar;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("DuplicatedCode")
public class StatsCollector {
    public static void collect(Map<ModPointer, SecureJar> jars, CollectorRule rule, ProjectsDB projects, InheritanceDB inheritance, RefsDB refs, ModIDsDB modIDsDB, Function<ModPointer, Collector> collectorFactory, ProgressMonitor monitor) throws InterruptedException, ExecutionException {
        jars.entrySet().removeIf(entry -> !rule.shouldCollect(entry.getKey().getModId()));

        projects.insert(0, 0); // Yay not null primary keys

        final Set<ModPointer> keptMods = new HashSet<>(); // Mods in this set shall not be deleted initially because they will not be requeried
        projects.useTransaction(transactional -> jars.keySet().removeIf(modPointer -> {
            if (modPointer.getProjectId() == 0) return false;
            final var oldFileId = transactional.getFileId(modPointer.getProjectId());
            if (Objects.equals(oldFileId, modPointer.getFileId())) { // No need to re-calculate if we already did
                keptMods.add(modPointer);
                return true;
            }
            transactional.insert(modPointer.getProjectId(), modPointer.getFileId());
            return false;
        }));

        final Set<Integer> keptModsId = modIDsDB.inTransaction(transactional -> keptMods.stream()
                .map(pointer -> transactional.get(pointer.getModId(), pointer.getProjectId()))
                .collect(Collectors.toSet()));
        inheritance.delete(inheritance.getAllMods().stream()
                .filter(Predicate.not(keptModsId::contains))
                .toList());
        refs.delete(refs.getAllMods().stream()
                .filter(Predicate.not(keptModsId::contains))
                .toList());

        System.out.println("Found " + jars.size() + " mods to recollect information on.");

        final var executor = Executors.newFixedThreadPool(5, n -> {
            final Thread thread = new Thread(n);
            thread.setUncaughtExceptionHandler((t, e) -> {
                System.err.printf("Encountered exception collecting information: %s", e);
                e.printStackTrace();
            });
            thread.setDaemon(true);
            return thread;
        });
        final List<Callable<Object>> callables = new ArrayList<>();
        for (final var entry : jars.entrySet()) {
            callables.add(() -> {
                collect(jars.size(), entry.getKey().getModId(), entry.getValue(), rule, collectorFactory.apply(entry.getKey()), monitor);
                return null;
            });
        }

        final var futures = executor.invokeAll(callables);
        for (final var future : futures) {
            future.get();
        }
    }

    private static void collect(int total, String modId, SecureJar jar, CollectorRule rule, Collector collector, ProgressMonitor monitor) throws IOException {
        try (final Stream<Path> classes = Files.find(jar.getRootPath(), Integer.MAX_VALUE, (path, basicFileAttributes) -> path.getFileName().toString().endsWith(".class"))) {
            final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
                final ClassNode owner = new ClassNode();

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    owner.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!rule.matches(descriptor)) return null;
                    final AnnotationNode node = new AnnotationNode(api, descriptor);
                    return new AnnotationVisitor(api, node) {
                        @Override
                        public void visitEnd() {
                            collector.acceptAnnotation(modId, owner, owner, node);
                        }
                    };
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!rule.matches(descriptor)) return null;
                            final AnnotationNode node = new AnnotationNode(api, descriptor);
                            return new AnnotationVisitor(api, node) {
                                @Override
                                public void visitEnd() {
                                    collector.acceptAnnotation(modId, owner, new FieldNode(access, name, descriptor, signature, value), node);
                                }
                            };
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    final MethodNode node = new MethodNode(access, name, descriptor, signature, exceptions);
                    final boolean isInit = name.equals("<init>");
                    owner.methods.add(node);
                    return new MethodVisitor(Opcodes.ASM9, node) {

                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!rule.matches(descriptor)) return null;
                            final AnnotationNode anNode = new AnnotationNode(api, descriptor);
                            return new AnnotationVisitor(api, anNode) {
                                @Override
                                public void visitEnd() {
                                    collector.acceptAnnotation(modId, owner, node, anNode);
                                }
                            };
                        }

                        @Override
                        public void visitEnd() {
                            boolean foundSuper = false;
                            for (final var insn : node.instructions) {
                                if (isInit && insn.getOpcode() == Opcodes.INVOKESPECIAL && !foundSuper && ((MethodInsnNode) insn).owner.equals(owner.superName)) {
                                    foundSuper = true;
                                    continue;
                                }

                                if (rule.matches(insn)) {
                                    collector.accept(modId, owner, node, insn);
                                    if (rule.oncePerMethod()) {
                                        break;
                                    } else if (rule.oncePerClass()) {
                                        throw new ExitException();
                                    }
                                }
                            }
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    collector.accept(modId, owner);
                    owner.methods.clear();
                }
            };

            final Iterator<Path> cls = classes.iterator();
            while (cls.hasNext()) {
                final ClassReader reader = new ClassReader(Files.readAllBytes(cls.next()));
                try {
                    reader.accept(visitor, 0);
                } catch (ExitException ignored) {

                }
            }
        }
        collector.commit();
        monitor.completedMod(modId, total);
    }

    public static final class ExitException extends RuntimeException {

    }
}
