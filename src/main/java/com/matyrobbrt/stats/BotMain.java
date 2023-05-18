package com.matyrobbrt.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.matyrobbrt.metabase.MetabaseClient;
import com.matyrobbrt.metabase.params.DatabaseInclusion;
import com.matyrobbrt.metabase.types.Field;
import com.matyrobbrt.metabase.types.Table;
import com.matyrobbrt.stats.collect.CollectorRule;
import com.matyrobbrt.stats.collect.DefaultDBCollector;
import com.matyrobbrt.stats.collect.DiscordProgressMonitor;
import com.matyrobbrt.stats.collect.StatsCollector;
import com.matyrobbrt.stats.db.InheritanceDB;
import com.matyrobbrt.stats.db.ModIDsDB;
import com.matyrobbrt.stats.db.ProjectsDB;
import com.matyrobbrt.stats.db.RefsDB;
import com.matyrobbrt.stats.util.MappingUtils;
import com.matyrobbrt.stats.util.Remapper;
import com.matyrobbrt.stats.util.SavedTrackedData;
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.request.Method;
import io.github.matyrobbrt.curseforgeapi.request.Request;
import io.github.matyrobbrt.curseforgeapi.request.Requests;
import io.github.matyrobbrt.curseforgeapi.request.query.ModSearchQuery;
import io.github.matyrobbrt.curseforgeapi.schemas.file.File;
import io.github.matyrobbrt.curseforgeapi.schemas.file.FileIndex;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.Mod;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.ModLoaderType;
import io.github.matyrobbrt.curseforgeapi.util.Constants;
import io.github.matyrobbrt.curseforgeapi.util.CurseForgeException;
import io.github.matyrobbrt.curseforgeapi.util.Utils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class BotMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotMain.class);

    private static final CurseForgeAPI CF = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
            .apiKey(System.getenv("CF_TOKEN"))
            .build()).get();

    private static final MetabaseClient METABASE = new MetabaseClient(
    );

    private static final SavedTrackedData<Set<Integer>> PACKS = new SavedTrackedData<>(
            new com.google.gson.reflect.TypeToken<>() {},
            HashSet::new, Path.of("data/modpacks.json")
    );

    private static final SavedTrackedData<Set<String>> GAME_VERSIONS = new SavedTrackedData<>(
            new com.google.gson.reflect.TypeToken<>() {},
            HashSet::new, Path.of("data/game_versions.json")
    );

    private static JDA jda;

    public static void main(String[] args) throws Exception {
        jda = JDABuilder.create(System.getenv("BOT_TOKEN"), EnumSet.of(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES))
                .addEventListeners((EventListener) gevent -> {
                    if (!(gevent instanceof ReadyEvent event)) return;

                    event.getJDA().updateCommands()
                            .addCommands(Commands.slash("modpacks", "Command used to manage collection of stats in modpacks")
                                    .addSubcommands(new SubcommandData("add", "Collect stats from the given modpack")
                                            .addOption(OptionType.INTEGER, "modpack", "The ID of the modpack to collect stats from", true))
                                    .addSubcommands(new SubcommandData("list", "List all watched modpacks"))
                                    .addSubcommands(new SubcommandData("remove", "Remove a modpack from stats collection")
                                            .addOption(OptionType.INTEGER, "modpack", "The ID of the modpack to remove", true)
                                            .addOption(OptionType.BOOLEAN, "removedb", "Whether to remove the modpack from the database", true)))
                            .queue();
                }, (EventListener) gevent -> {
                    if (!(gevent instanceof SlashCommandInteractionEvent event)) return;
                    try {
                        onSlashCommandInteraction(event);
                    } catch (Exception ex) {
                        event.getHook().sendMessage("Encountered exception executing command: " + ex).queue();
                    }
                })
                .build()
                .awaitReady();

        final ExecutorService rescanner = Executors.newFixedThreadPool(3);
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (final Mod mod : CF.makeRequest(getMods(PACKS.read())).orElseThrow()) {
                    rescanner.submit(() -> trigger(mod));
                }
            } catch (CurseForgeException e) {
                throw new RuntimeException(e);
            }
        }, 1, 1, TimeUnit.HOURS);

        scheduler.scheduleAtFixedRate(() -> {
            for (final String version : GAME_VERSIONS.read()) {
                rescanner.submit(() -> {
                    try {
                        triggerGameVersion(version);
                    } catch (Exception ex) {
                        System.out.println(ex.getMessage());
                        ex.printStackTrace();
                    }
                });
            }
        // }, 1, 1, TimeUnit.DAYS);
        }, 1, 30, TimeUnit.MINUTES);
    }

    public static Request<List<Mod>> getMods(Iterable<Integer> modIds) {
        final var body = new JsonObject();
        final var array = new JsonArray();
        for (final var id : modIds) {
            array.add(id);
        }
        body.add("modIds", array);
        return new Request<>("/v1/mods", Method.POST, body, "data", Requests.Types.MOD_LIST);
    }

    public static void onSlashCommandInteraction(final SlashCommandInteractionEvent event) throws Exception {
        switch (event.getFullCommandName()) {
            case "modpacks add" -> {
                final Mod pack = CF.makeRequest(Requests.getMod(event.getOption("modpack", 0, OptionMapping::getAsInt))).orElse(null);
                if (pack == null || pack.gameId() != Constants.GameIDs.MINECRAFT || pack.classId() != 4471) {
                    event.reply("Unknown modpack!").setEphemeral(true).queue();
                    return;
                }

                event.reply("Watching modpack. Started indexing, please wait...").queue();
                ForkJoinPool.commonPool().submit(() -> {
                    trigger(pack);

                    event.getHook().editOriginal("Finished initial indexing.").queue();

                    PACKS.useHandle(packs -> packs.add(pack.id()));
                });
            }
            case "modpacks list" -> { // TODO - make better
                PACKS.useHandle(packs -> {
                    if (packs.isEmpty()) {
                        event.reply("No packs watched!").queue();
                    } else {
                        event.reply(packs.stream().map(String::valueOf).collect(Collectors.joining(", "))).queue();
                    }
                });
            }

            case "modpacks remove" -> {
                final var packs = PACKS.read();
                final int packId = event.getOption("modpack", 0, OptionMapping::getAsInt);
                if (!packs.contains(packId)) {
                    event.reply("Unknown pack!").setEphemeral(true).queue();
                }

                packs.remove(packId);
                PACKS.write();

                if (event.getOption("removedb", false, OptionMapping::getAsBoolean)) {
                    try (final var con = Main.initiateDBConnection()) {
                        try (final var stmt = con.createStatement()) {
                            stmt.execute("drop schema if exists pack_" + packId + " cascade;");
                        }
                    }
                }
                event.reply("Pack removed!").queue();
            }
        }
    }

    private static void trigger(Mod pack) {
        try {
            final String schemaName = "pack_" + pack.id();
            final var connection = Main.createDatabaseConnection(schemaName);
            if (connection.getKey().initialSchemaVersion == null) { // Schema was created
                updateMetabase(schemaName);
            }

            final Jdbi jdbi = connection.getValue();
            final ProjectsDB projects = jdbi.onDemand(ProjectsDB.class);

            final ModCollector collector = new ModCollector(CF);
            final File mainFile = CF.getHelper().getModFile(pack.id(), pack.mainFileId()).orElseThrow();
            if (Objects.equals(projects.getFileId(pack.id()), mainFile.id())) {
                LOGGER.trace("Pack {} is up-to-date.", pack.id());
                return;
            }
            projects.insert(pack.id(), mainFile.id());

            final Message logging = jda.getChannelById(MessageChannel.class, System.getenv("LOGGING_CHANNEL"))
                            .sendMessage("Status of collection of statistics of **" + pack.name() + "**, file ID: " + mainFile.id())
                            .complete();
            LOGGER.info("Found new file ({}) for pack {}: started stats collection.", mainFile.id(), pack.id());

            final DiscordProgressMonitor progressMonitor = new DiscordProgressMonitor(
                    logging, (id, ex) -> LOGGER.error("Collection for mod '{}' in pack {} failed:", id, pack.id(), ex)
            );
            progressMonitor.markCollection(-1);

            collector.fromModpack(mainFile);

            final Remapper remapper = Remapper.fromMappings(MappingUtils.srgToMoj(mainFile.sortableGameVersions()
                    .stream().max(Comparator.comparing(g -> Instant.parse(g.gameVersionReleaseDate()))).orElseThrow().gameVersion()));

            StatsCollector.collect(
                    collector.getJarsToProcess(),
                    CollectorRule.collectAll(),
                    projects,
                    jdbi.onDemand(InheritanceDB.class),
                    jdbi.onDemand(RefsDB.class),
                    jdbi.onDemand(ModIDsDB.class),
                    (mid) -> new DefaultDBCollector(mid, jdbi, remapper, true),
                    progressMonitor,
                    true
            );

            LOGGER.info("Finished stats collection of pack {}", pack.id());
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static void triggerGameVersion(String gameVersion) throws Exception {
        final String schemaName = "gv_" + gameVersion.replace('.', '_').replace('-', '_');
        final var connection = Main.createDatabaseConnection(schemaName);
        if (connection.getKey().initialSchemaVersion == null) { // Schema was created
            updateMetabase(schemaName);
        }

        final Jdbi jdbi = connection.getValue();
        final ProjectsDB projects = jdbi.onDemand(ProjectsDB.class);

        final Set<Integer> fileIds = projects.getFileIDs();

        final List<FileIndex> newMods = new ArrayList<>();
        int idx = 0;
        int maxItems = 10_000;
//        while (page * 50 < maxItems) {
        while (idx < 50) {
            final var response = CF.getHelper().searchModsPaginated(ModSearchQuery.of(Constants.GameIDs.MINECRAFT)
                    .gameVersion(gameVersion).classId(6) // We're interested in mods
                    .sortField(ModSearchQuery.SortField.LAST_UPDATED)
                    .sortOrder(ModSearchQuery.SortOrder.ASCENDENT)
                    .modLoaderType(ModLoaderType.FORGE)
                    .pageSize(50).index(idx))
                    .orElseThrow();
            idx = response.pagination().index() + 50;
            maxItems = Math.min(response.pagination().resultCount(), 10000);
            for (final Mod mod : response.data()) {
                final FileIndex matching = mod.latestFilesIndexes().stream()
                        .filter(f -> f.gameVersion().equals(gameVersion) && f.modLoader() != null && f.modLoaderType() == ModLoaderType.FORGE)
                        .limit(1)
                        .findFirst().orElse(null);
                if (matching == null) continue;
                if (fileIds.contains(matching.fileId())) break;
                newMods.add(matching);
            }
        }

        if (newMods.isEmpty()) {
            LOGGER.info("Found no new mods to collect stats on for game version {}.", gameVersion);
        }

        final Message logging = jda.getChannelById(MessageChannel.class, System.getenv("LOGGING_CHANNEL"))
                .sendMessage("Status of collection of statistics for game version '"+ gameVersion + "'")
                .complete();
        LOGGER.info("Started stats collection for game version '{}'. Found {} mods to scan.", gameVersion, newMods.size());

        final DiscordProgressMonitor progressMonitor = new DiscordProgressMonitor(
                logging, (id, ex) -> LOGGER.error("Collection for mod '{}' in game version '{}' failed:", id, gameVersion, ex)
        );
        progressMonitor.markCollection(newMods.size());

        final ModCollector collector = new ModCollector(CF);
        for (final File modFile : CF.getHelper().getFiles(newMods.stream()
                .mapToInt(FileIndex::fileId)
                .toArray()).orElseThrow()) {
            collector.considerFile(modFile);
        }

        final Remapper remapper = Remapper.fromMappings(MappingUtils.srgToMoj(gameVersion));

        StatsCollector.collect(
                collector.getJarsToProcess(),
                CollectorRule.collectAll(),
                projects,
                jdbi.onDemand(InheritanceDB.class),
                jdbi.onDemand(RefsDB.class),
                jdbi.onDemand(ModIDsDB.class),
                (mid) -> new DefaultDBCollector(mid, jdbi, remapper, true),
                progressMonitor,
                false
        );

        LOGGER.info("Finished stats collection for game version '{}'", gameVersion);
    }

    private static void updateMetabase(String schemaName) {
        CompletableFuture.allOf(METABASE.getDatabases(UnaryOperator.identity())
                .thenApply(databases -> databases.stream().filter(db -> db.details().get("user").getAsString().equals(System.getenv("db.user")))) // TODO - check host
                .thenApply(db -> db.findFirst().orElseThrow())
                .thenCompose(db -> db.syncSchema().thenCompose($ -> METABASE.getDatabase(db.id(), p -> p.include(DatabaseInclusion.TABLES_AND_FIELDS))))
                .thenApply(db -> db.tables().stream().filter(tb -> tb.schema().equals(schemaName)).toList())
                .thenCompose(tbs -> CompletableFuture.allOf(tbs.stream().map(tb -> {
                    if (tb.name().equals("flyway_schema_history")) {
                        return tb.setHidden(true);
                    } else {
                        return tb.update(p -> switch (tb.name()) {
                            case "refs" -> p.withDisplayName("References")
                                    .withDescription("References of fields, methods, classes and annotations");
                            case "inheritance" -> p.withDescription("The class hierarchy of mods");
                            case "projects" ->
                                    p.withDescription("The IDs of the projects that are tracked by this schema");
                            case "modids" -> p.withDisplayName("Mod IDs")
                                    .withDescription("A mapping of text mod IDs to integers in order to save space");
                            default -> null;
                        });
                    }
                }).toArray(CompletableFuture[]::new)).thenApply($ -> tbs))
                .thenCompose(tbs -> {
                    final Table modids = tbs.stream().filter(tb -> tb.name().equals("modids")).findFirst().orElseThrow();
                    final Field target = modids.fields().stream().filter(f -> f.name().equals("modid")).findFirst().orElseThrow();
                    return CompletableFuture.allOf(tbs.stream().filter(tb -> tb.name().equals("inheritance") || tb.name().equals("refs"))
                            .map(tb -> tb.fields().stream().filter(f -> f.name().equals("modid")).findFirst().orElseThrow()
                                    .update(u -> u.setTarget(target))
                                    .thenCompose(f -> f.setDimension(target.id(), target.displayName())))
                            .toArray(CompletableFuture[]::new));
                }));
    }
}
