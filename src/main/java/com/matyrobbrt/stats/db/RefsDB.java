package com.matyrobbrt.stats.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.transaction.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

public interface RefsDB extends Transactional<RefsDB> {

//    @BatchChunkSize(5000)
    @SqlBatch("insert into refs(modId, amount, owner, member, type) values (:mid, :amount, :owner, :member, :type)")
    void insert(@Bind("mid") String modId, @BindBean Iterable<Reference> references, @Bind("amount") Iterable<AtomicInteger> amount);

}
