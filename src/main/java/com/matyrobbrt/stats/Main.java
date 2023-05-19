package com.matyrobbrt.stats;

import com.matyrobbrt.stats.collect.CollectorRule;
import com.matyrobbrt.stats.collect.DefaultDBCollector;
import com.matyrobbrt.stats.collect.ProgressMonitor;
import com.matyrobbrt.stats.collect.StatsCollector;
import com.matyrobbrt.stats.db.InheritanceDB;
import com.matyrobbrt.stats.db.ModIDsDB;
import com.matyrobbrt.stats.db.ProjectsDB;
import com.matyrobbrt.stats.db.RefsDB;
import com.matyrobbrt.stats.util.MappingUtils;
import com.matyrobbrt.stats.util.Remapper;
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.util.Utils;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static final CurseForgeAPI API = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
            .apiKey(System.getenv("CF_TOKEN"))
            .build()).get();

    public static void main(String[] args) throws Exception {
        final Jdbi jdbi = createDatabaseConnection("inheritancetest").getValue();

        final ModCollector collector = new ModCollector(API);

//        collector.fromModpack(520914, 4504859); // ATM8
//        collector.fromModpack(655739, 4497267); // ATM7Sky

         collector.considerFile(238222, 4494410); // JEI

        final Remapper remapper = Remapper.fromMappings(MappingUtils.srgToMoj("1.19.2"));

        StatsCollector.collect(
                collector.getJarsToProcess(),
                CollectorRule.collectAll(),
                jdbi.onDemand(ProjectsDB.class),
                jdbi.onDemand(InheritanceDB.class),
                jdbi.onDemand(RefsDB.class),
                jdbi.onDemand(ModIDsDB.class),
                (mid) -> new DefaultDBCollector(mid, jdbi, remapper, true),
                new ProgressMonitor() {
                    @Override
                    public void setNumberOfMods(int numberOfMods) {

                    }

                    @Override
                    public void startMod(String id) {

                    }

                    @Override
                    public void completedMod(String id, Exception exception) {

                    }
                },
                true
        );
    }

    public static Map.Entry<MigrateResult, Jdbi> createDatabaseConnection(String schemaName) throws Exception {
        final Connection connection = initiateDBConnection();
        connection.setSchema(schemaName);

        final var flyway = Flyway.configure()
                .dataSource(System.getProperty("db.url"), System.getProperty("db.user"), System.getProperty("db.password"))
                .locations("classpath:db")
                .schemas(schemaName)
                .load();
        final var result = flyway.migrate();

        return Map.entry(result, Jdbi.create(connection)
                .registerArgument(new AbstractArgumentFactory<AtomicInteger>(Types.INTEGER) {
                    @Override
                    protected Argument build(AtomicInteger value, ConfigRegistry config) {
                        return (position, statement, ctx) -> statement.setInt(position, value.get());
                    }
                })
                .installPlugin(new SqlObjectPlugin()));
    }

    public static Connection initiateDBConnection() throws SQLException {
        final String user = System.getProperty("db.user");
        final String password = System.getProperty("db.password");
        final String url = System.getProperty("db.url");
        return DriverManager.getConnection(url + "?user=" + user + "&password=" + password);
    }

}
