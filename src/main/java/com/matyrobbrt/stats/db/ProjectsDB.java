package com.matyrobbrt.stats.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;

import javax.annotation.Nullable;

public interface ProjectsDB extends Transactional<ProjectsDB> {
    @Nullable
    @SqlQuery("select fileId from projects where projectId = ?")
    Integer getFileId(int projectId);

    @SqlUpdate("insert into projects(projectId, fileId) values (:projectId, :fileId) on conflict(projectId) do update set fileId = :fileId")
    void insert(@Bind("projectId") int projectId, @Bind("fileId") int fileId);
}
