package com.matyrobbrt.stats;

import com.google.common.base.Stopwatch;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
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
import io.github.matyrobbrt.curseforgeapi.request.Requests;
import io.github.matyrobbrt.curseforgeapi.schemas.file.File;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.Mod;
import io.github.matyrobbrt.curseforgeapi.util.Constants;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BotMain {
    private static final CurseForgeAPI CF = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
            .apiKey(System.getenv("CF_TOKEN"))
            .build()).get();
    private static JDA jda;

    public static void main(String[] args) {
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
                ForkJoinPool.commonPool().submit(() -> {
                    trigger(pack);

                    event.getHook().editOriginal("Finished initial indexing.").queue();

                    packs.add(pack.id());
                    write();
                });
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
            if (Objects.equals(projects.getFileId(pack.id()), mainFile.id())) return;
            projects.insert(pack.id(), mainFile.id());

            final Message logging = jda.getChannelById(MessageChannel.class, System.getenv("LOGGING_CHANNEL"))
                            .sendMessage("Starting recollection of **" + pack.name() + "**, file ID: " + mainFile.id() + "\nCollecting mods.")
                            .complete();

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
                    (mid) -> new DefaultDBCollector(mid, jdbi, remapper, false),
                    new ProgressMonitor() {
                        {
                            this.numberOfMods = new AtomicInteger(-1);
                            this.completed = new AtomicInteger();
                            this.currentMods = new ArrayList<>();
                            setupMonitor();
                        }

                        private void setupMonitor() {
                            final Stopwatch start = Stopwatch.createStarted();
                            ForkJoinPool.commonPool().submit(() -> {
                                final long monitoringInterval = Duration.ofSeconds(2).toMillis();
                                final AtomicLong last = new AtomicLong(System.currentTimeMillis());

                                while (true) {
                                    if (System.currentTimeMillis() - last.get() < monitoringInterval) continue;

                                    final int num = numberOfMods.get();
                                    if (num == -1) continue; // We haven't started yet
                                    final int com = completed.get();
                                    final StringBuilder content = new StringBuilder()
                                            .append("Status of collection of statics of pack **").append(pack.name()).append("**, file with ID ").append(mainFile.id()).append(":\n");
                                    if (num == com) {
                                        content.append("Completed scanning of ").append(num).append(" mods in ").append(start.stop().elapsed(TimeUnit.SECONDS)).append(" seconds!");
                                    } else {
                                        synchronized (currentMods) {
                                            if (currentMods.isEmpty()) {
                                                content.append("Currently idling...");
                                            } else {
                                                content.append(IntStream.range(0, currentMods.size())
                                                        .mapToObj(i -> "- " + currentMods.get(i) + " (" + (com + i + 1) + "/" + num + ")")
                                                        .collect(Collectors.joining("\n")));
                                            }
                                        }
                                    }

                                    logging.editMessage(content.toString()).complete();
                                    if (num == com) {
                                        break;
                                    }
                                }
                            });
                        }

                        final AtomicInteger numberOfMods;
                        final AtomicInteger completed;
                        final List<String> currentMods;
                        @Override
                        public void setNumberOfMods(int numberOfMods) {
                            this.numberOfMods.set(numberOfMods);
                        }

                        @Override
                        public void startMod(String id) {
                            synchronized (currentMods) {
                                currentMods.add(id);
                            }
                        }

                        @Override
                        public void completedMod(String id) {
                            synchronized (currentMods) {
                                currentMods.remove(id);
                                completed.incrementAndGet();
                            }
                        }
                    }
            );
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
        }
    }
}
