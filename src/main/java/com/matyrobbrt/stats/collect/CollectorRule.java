package com.matyrobbrt.stats.collect;

import org.objectweb.asm.tree.AbstractInsnNode;

public interface CollectorRule {

    boolean shouldCollect(String modId);

    boolean matches(AbstractInsnNode node);

    boolean matches(String annotationDesc);

    boolean oncePerMethod();

    boolean oncePerClass();
}
