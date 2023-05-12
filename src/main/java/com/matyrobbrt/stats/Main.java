package com.matyrobbrt.stats;

import com.matyrobbrt.stats.collect.CollectorRule;
import com.matyrobbrt.stats.collect.DefaultDBCollector;
import com.matyrobbrt.stats.collect.StatsCollector;
import com.matyrobbrt.stats.util.Remapper;
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.util.Utils;
import net.minecraftforge.srgutils.IMappingFile;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static final CurseForgeAPI API = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
            .apiKey(System.getenv("CF_TOKEN"))
            .build()).get();
    public static final IMappingFile MAPPINGS = Utils.rethrowSupplier(() -> IMappingFile.load(new File("mappings.srg"))).get();

    public static void main(String[] args) throws Exception {
        final Jdbi jdbi = createDatabaseConnection("atm7sky");

        final ModCollector collector = new ModCollector(API);

        // collector.fromModpack(520914, 4504859); // ATM8
        collector.fromModpack(655739, 4497267); // ATM7Sky

        final Remapper remapper = Remapper.fromMappings(MAPPINGS);

        StatsCollector.collect(
                collector.getJarsToProcess(),
                new CollectorRule() {
                    @Override
                    public boolean shouldCollect(String modId) {
                        return true;
                    }

                    @Override
                    public boolean matches(AbstractInsnNode node) {
                        return node instanceof MethodInsnNode || node instanceof FieldInsnNode ||
                                node instanceof LdcInsnNode ldc && ldc.cst.getClass() == org.objectweb.asm.Type.class;
                    }

                    @Override
                    public boolean matches(String annotationDesc) {
                        return !annotationDesc.endsWith("kotlin/Metadata;") && !annotationDesc.equals("Lscala/reflect/ScalaSignature;");
                    }

                    @Override
                    public boolean oncePerMethod() {
                        return true;
                    }

                    @Override
                    public boolean oncePerClass() {
                        return false;
                    }
                },
                (mid) -> new DefaultDBCollector(mid, jdbi, remapper)
        );
    }

    public static Jdbi createDatabaseConnection(String schemaName) throws Exception {
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
        flyway.migrate();

        return Jdbi.create(connection)
                .registerArgument(new AbstractArgumentFactory<AtomicInteger>(Types.INTEGER) {
                    @Override
                    protected Argument build(AtomicInteger value, ConfigRegistry config) {
                        return (position, statement, ctx) -> statement.setInt(position, value.get());
                    }
                })
                .installPlugin(new SqlObjectPlugin());
    }

}
