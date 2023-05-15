package com.matyrobbrt.stats.collect;

public interface ProgressMonitor {
    void setNumberOfMods(int numberOfMods);
    void startMod(String id);
    void completedMod(String id);
}
