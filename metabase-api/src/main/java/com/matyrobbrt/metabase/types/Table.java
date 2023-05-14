package com.matyrobbrt.metabase.types;

import com.google.gson.annotations.SerializedName;
import com.matyrobbrt.metabase.MetabaseClient;
import com.matyrobbrt.metabase.RequestParameters;
import com.matyrobbrt.metabase.Route;
import com.matyrobbrt.metabase.internal.MetabaseType;
import com.matyrobbrt.metabase.params.TableUpdateParameters;

import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

@MetabaseType
public record Table(
        MetabaseClient client,
        int id, @SerializedName("db_id") int dbId,
        boolean active,
        String schema, String name,
        String description
) {
    private static final Route<Table> UPDATE_TABLE = Route.update("/table/:id", Table.class);

    public CompletableFuture<Table> update(UnaryOperator<TableUpdateParameters> parameters) {
        return client.sendRequest(UPDATE_TABLE.compile(RequestParameters.of(
                "id", id, "params", parameters.apply(new TableUpdateParameters())
        )));
    }
}
