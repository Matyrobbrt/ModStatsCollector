package com.matyrobbrt.stats.db;

import java.util.List;

public record InheritanceEntry(
        String clazz, String superClass,
        List<String> interfaces, String[] methods
) {
    public String getClazz() {
        return clazz;
    }

    public String getSuperClass() {
        return superClass;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    public String[] getMethods() {
        return methods;
    }
}
