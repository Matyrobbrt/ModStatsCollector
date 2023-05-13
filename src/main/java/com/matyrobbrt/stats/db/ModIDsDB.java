package com.matyrobbrt.stats.db;

import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.transaction.Transactional;

import javax.annotation.Nullable;

public interface ModIDsDB extends Transactional<ModIDsDB> {
    @Nullable
    @SqlQuery("select id from modids where modId = ?")
    Integer getId(String modId);

    default int get(String modId) {
        final Integer i = getId(modId);
        if (i != null) {
            return i;
        }
        return getHandle().createUpdate("insert into modids(modid) values (?) returning id")
                .bind(0, modId)
                .execute((statementSupplier, ctx) -> statementSupplier.get().getResultSet().getInt("id"));
    }
}
