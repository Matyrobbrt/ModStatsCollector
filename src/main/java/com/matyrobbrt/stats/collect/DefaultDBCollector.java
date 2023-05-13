package com.matyrobbrt.stats.collect;

import com.matyrobbrt.stats.db.InheritanceDB;
import com.matyrobbrt.stats.db.InheritanceEntry;
import com.matyrobbrt.stats.db.ModIDsDB;
import com.matyrobbrt.stats.db.Reference;
import com.matyrobbrt.stats.db.RefsDB;
import com.matyrobbrt.stats.db.Type;
import com.matyrobbrt.stats.util.Remapper;
import org.jdbi.v3.core.Jdbi;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class DefaultDBCollector implements Collector {
    private final String modId;
    private final Jdbi jdbi;
    private final Remapper remapper;
    private final boolean collectInheritance;

    private final Map<Reference, AtomicInteger> count = new LinkedHashMap<>();
    private final List<InheritanceEntry> inheritance = new ArrayList<>();

    public DefaultDBCollector(String modId, Jdbi jdbi, Remapper remapper, boolean collectInheritance) {
        this.modId = modId;
        this.jdbi = jdbi;
        this.remapper = remapper;
        this.collectInheritance = collectInheritance;
    }

    @Override
    public void accept(String modId, ClassNode clazz) {
        if (collectInheritance && !clazz.name.endsWith("package-info")) {
            inheritance.add(new InheritanceEntry(
                    clazz.name, clazz.superName,
                    clazz.interfaces, clazz.methods.stream()
                    .map(method -> remapper.remapMethod(method.name) + method.desc)
                    .toArray(String[]::new)
            ));
        }
    }

    @Override
    public void accept(String modId, ClassNode owner, MethodNode declaring, AbstractInsnNode node) {
        if (node instanceof FieldInsnNode fieldNode) {
            count.computeIfAbsent(new Reference(fieldNode.owner, remapper.remapField(fieldNode.name), Type.FIELD), k -> new AtomicInteger()).incrementAndGet();
        } else if (node instanceof MethodInsnNode methodNode) {
            count.computeIfAbsent(new Reference(methodNode.owner, remapper.remapMethod(methodNode.name), Type.METHOD), k -> new AtomicInteger()).incrementAndGet();
        } else if (node instanceof LdcInsnNode ldc) {
            final org.objectweb.asm.Type type = (org.objectweb.asm.Type) ldc.cst;
            count.computeIfAbsent(new Reference(type.getSort() == org.objectweb.asm.Type.ARRAY ? type.getElementType().getInternalName() : type.getInternalName(), type.getInternalName() + ".class", Type.CLASS), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    @Override
    public void acceptAnnotation(String modId, ClassNode clazz, Object owner, AnnotationNode node) {
        count.computeIfAbsent(new Reference(
                org.objectweb.asm.Type.getType(node.desc).getInternalName(),
                formatAnnotation(owner, node),
                Type.ANNOTATION
        ), k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void commit() {
        synchronized (jdbi) {
            final int id = jdbi.withExtension(ModIDsDB.class, db -> db.get(modId));
            jdbi.useExtension(RefsDB.class, d -> d.insert(id, count.keySet(), count.values()));
            jdbi.useExtension(InheritanceDB.class, db -> db.insert(id, inheritance));
        }
    }

    private String formatAnnotation(Object owner, AnnotationNode annotationNode) {
        final String type;
        if (owner instanceof ClassNode) {
            type = "class";
        } else if (owner instanceof MethodNode) {
            type = "method";
        } else if (owner instanceof FieldNode) {
            type = "field";
        } else {
            type = "";
        }

        final StringBuilder container = new StringBuilder()
                .append('@').append(type)
                .append('(');

        final List<String> values = new ArrayList<>();

        annotationNode.accept(annotationVisit(values, () -> {}));

        return container.append(String.join(", ", values)).append(')').toString();
    }

    private AnnotationVisitor annotationVisit(List<String> values, Runnable onEnd) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                values.add(name + "=" + valToString(value));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                final List<String> vals = new ArrayList<>();
                return annotationVisit(values, () -> values.add(name + "=@(" + String.join(", ", vals) + ")"));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                final List<String> anns = new ArrayList<>();
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        anns.add(valToString(value));
                    }

                    @Override
                    public void visitEnd() {
                        values.add(name + "=[" + String.join(", ", anns) + "]");
                    }
                };
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                values.add(name + "=" + org.objectweb.asm.Type.getType(descriptor).getInternalName() + "." + value);
            }

            @Override
            public void visitEnd() {
                onEnd.run();
            }

            private static String valToString(Object value) {
                if (value instanceof List<?> list) {
                    return "[" + list.stream().map(it -> it instanceof org.objectweb.asm.Type tp ? tp.getInternalName() + ".class" : it.toString())
                            .collect(Collectors.joining(", ")) + "]";
                } else if (value instanceof org.objectweb.asm.Type tp) {
                    return tp.getInternalName() + ".class";
                } else {
                    return value.toString();
                }
            }
        };
    }

}
