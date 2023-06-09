package com.matyrobbrt.metabase.params;

import java.util.Map;

public class DatabasesQueryParameters implements QueryParameters {
    private boolean includeTables;

    public DatabasesQueryParameters includeTables(boolean includeTables) {
        this.includeTables = includeTables;
        return this;
    }

    @Override
    public Map<String, Object> compile() {
        return Map.of(
                "include_tables", includeTables
        );
    }
}
