package com.matyrobbrt.metabase.params;

import com.google.gson.JsonObject;
import com.matyrobbrt.metabase.types.Field;

public class FieldUpdateParameters implements UpdateParameters {
    private final Field source;
    private Field target;

    public FieldUpdateParameters(Field source) {
        this.source = source;
    }

    public FieldUpdateParameters setTarget(Field target) {
        this.target = target;
        return this;
    }

    @Override
    public JsonObject compile() {
        final JsonObject update = source._json().deepCopy();
        if (target != null) {
            update.add("target", target._json().deepCopy());
        }
        return update;
    }
}
