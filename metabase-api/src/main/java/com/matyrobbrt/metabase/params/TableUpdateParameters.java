package com.matyrobbrt.metabase.params;

import com.google.gson.JsonObject;

public class TableUpdateParameters implements UpdateParameters {
    private String description;

    public TableUpdateParameters withDescription(String description) {
        this.description = description;
        return this;
    }

    @Override
    public JsonObject compile() {
        final JsonObject object = new JsonObject();
        if (description != null) {
            object.addProperty("description", description);
        }
        return object;
    }
}
