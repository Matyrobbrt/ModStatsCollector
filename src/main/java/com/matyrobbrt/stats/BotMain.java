package com.matyrobbrt.stats;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
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
import io.github.matyrobbrt.curseforgeapi.request.Requests;
import io.github.matyrobbrt.curseforgeapi.schemas.file.File;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.Mod;
import io.github.matyrobbrt.curseforgeapi.util.Constants;
import io.github.matyrobbrt.curseforgeapi.util.Utils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class BotMain {
    private static final CurseForgeAPI CF = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
            .apiKey(System.getenv("CF_TOKEN"))
            .build()).get();

    public static void main(String[] args) {
        final JDA jda = JDABuilder.create(System.getenv("BOT_TOKEN"), EnumSet.of(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES))
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
                .build();

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
                trigger(pack);

                event.getHook().editOriginal("Finished initial indexing.").queue();

                packs.add(pack.id());
                write();
            }
            case "modpacks list" -> { // TODO

            }

            case "modpacks remove" -> {

            }
        }
    }

    private static final Gson GSON = new Gson();
    private static final Path TRACKED_MODPACKS = Path.of("data/modpacks.json");
    private static List<Integer> packs;
    static {
        refresh();
    }

    private static void refresh() {
        try {
            if (!Files.exists(TRACKED_MODPACKS)) {
                Files.createDirectories(TRACKED_MODPACKS.getParent());
                packs = new ArrayList<>();
                Files.writeString(TRACKED_MODPACKS, GSON.toJson(packs));
            } else {
                try (final var reader = Files.newBufferedReader(TRACKED_MODPACKS)) {
                    packs = GSON.fromJson(reader, new TypeToken<>() {}.getType());
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Could not refresh tracked modpacks: ", ex);
        }
    }

    private static void write() {
        try {
            Files.writeString(TRACKED_MODPACKS, GSON.toJson(packs));
        } catch (IOException e) {
            throw new RuntimeException("Could not write tracked modpacks: ", e);
        }
    }

    private static void trigger(Mod pack) {
        try {
            final Jdbi jdbi = Main.createDatabaseConnection("pack_" + pack.id()).getValue();
            final ProjectsDB projects = jdbi.onDemand(ProjectsDB.class);

            final ModCollector collector = new ModCollector(CF);
            final File mainFile = CF.getHelper().getModFile(pack.id(), pack.mainFileId()).orElseThrow();

            collector.considerFile(mainFile);

            final Remapper remapper = Remapper.fromMappings(MappingUtils.srgToMoj(mainFile.sortableGameVersions()
                    .stream().max(Comparator.comparing(g -> Instant.parse(g.gameVersionReleaseDate()))).orElseThrow().gameVersion()));

            StatsCollector.collect(
                    collector.getJarsToProcess(),
                    CollectorRule.collectAll(),
                    projects,
                    jdbi.onDemand(InheritanceDB.class),
                    jdbi.onDemand(RefsDB.class),
                    jdbi.onDemand(ModIDsDB.class),
                    (mid) -> new DefaultDBCollector(mid, jdbi, remapper, false),
                    (id, numberOfMods) -> {}
            );
        } catch (Exception ex) {

        }
    }
}
