package com.matyrobbrt.stats;

import com.matyrobbrt.metabase.MetabaseClient;
import com.matyrobbrt.metabase.params.DatabaseInclusion;
import com.matyrobbrt.stats.collect.CollectorRule;
import com.matyrobbrt.stats.collect.DefaultDBCollector;
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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

public class Main {

    public static final CurseForgeAPI API = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
            .apiKey(System.getenv("CF_TOKEN"))
            .build()).get();

    public static void main(String[] args) throws Exception {
        final Jdbi jdbi = createDatabaseConnection("jeionly").getValue();

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
                (mid) -> new DefaultDBCollector(mid, jdbi, remapper, false),
                (id, numberOfMods) -> {}
        );

        final MetabaseClient client = new MetabaseClient(
                null,
                null,
                null
        );
        CompletableFuture.allOf(client.getDatabases(UnaryOperator.identity())
                .thenApply(databases -> databases.stream().filter(db -> db.details().get("user").getAsString().equals(System.getenv("db.user"))))
                .thenApply(db -> db.findFirst().orElseThrow())
                .thenCompose(db -> db.syncSchema().thenCompose($ -> client.getDatabase(db.id(), p -> p.include(DatabaseInclusion.TABLES))))
                .thenApply(db -> db.tables().stream().filter(tb -> tb.schema().equals("jeionly")))
                .thenApply(tables -> tables.map(tb -> tb.update(p -> p.withDescription(switch (tb.name()) {
                    case "refs" -> "References of fields, methods, classes and annotations";
                    case "inheritance" -> "The class hierarchy of mods";
                    default -> null;
                }))).toArray(CompletableFuture[]::new)))
                .join();
    }

    public static Map.Entry<MigrateResult, Jdbi> createDatabaseConnection(String schemaName) throws Exception {
        final String user = System.getenv("db.user");
        final String password = System.getenv("db.password");
        final String url = System.getenv("db.url");
        final Connection connection = DriverManager.getConnection(url + "?user=" + user + "&password=" + password);
        connection.setSchema(schemaName);

        final var flyway = Flyway.configure()
                .dataSource(url, user, password)
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

}
