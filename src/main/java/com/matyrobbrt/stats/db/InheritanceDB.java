package com.matyrobbrt.stats.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.transaction.Transactional;

public interface InheritanceDB extends Transactional<InheritanceDB> {
    @SqlBatch("insert into inheritance(modId, class, super, interfaces, methods) values (:mid, :clazz, :superClass, :interfaces, :methods)")
    void insert(@Bind("mid") int modId, @BindBean Iterable<InheritanceEntry> entries);
}
