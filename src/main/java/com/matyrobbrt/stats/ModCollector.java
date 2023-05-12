package com.matyrobbrt.stats;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cpw.mods.jarhandling.SecureJar;
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.schemas.file.File;
import io.github.matyrobbrt.curseforgeapi.util.CurseForgeException;
import io.github.matyrobbrt.curseforgeapi.util.gson.RecordTypeAdapterFactory;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModCollector {
    private static final TomlParser PARSER = new TomlParser();
    private static final Path DOWNLOAD_CACHE = Path.of("cfCache");
    private final CurseForgeAPI api;

    private final Set<String> jarJars = new HashSet<>();
    private final Map<String, SecureJar> jars = new HashMap<>();

    public ModCollector(CurseForgeAPI api) {
        this.api = api;
    }

    public Map<String, SecureJar> getJarsToProcess() {
        return jars;
    }

    public void fromModpack(int packId, int fileId) throws CurseForgeException, IOException {
        final Path modpackFile = download(api.getHelper().getModFile(packId, fileId).orElseThrow());
        if (modpackFile == null) return;

        final Manifest mf;
        try (final FileSystem zip = FileSystems.newFileSystem(modpackFile)) {
            final Path manifest = zip.getPath("manifest.json");
            if (!Files.exists(manifest)) return;
            try (final BufferedReader reader = Files.newBufferedReader(manifest)) {
                mf = GSON.fromJson(reader, Manifest.class);
            }
        }

        for (final FilePointer filePointer : mf.files) {
            final File file = api.getHelper().getModFile(filePointer.projectID, filePointer.fileID).orElseThrow();
            considerFile(file);
        }
    }

    public void considerFile(File file) throws IOException {
        final Path downloaded = download(file);
        if (downloaded == null) return;
        consider(SecureJar.from(downloaded));
    }

    @Nullable
    public Path download(File file) throws IOException {
        if (file.downloadUrl() == null) return null;
        final Path path = DOWNLOAD_CACHE.resolve(file.modId() + "/" + file.id() + file.downloadUrl().substring(file.downloadUrl().lastIndexOf('.')));
        if (Files.exists(path)) {
            if (Files.size(path) == file.fileLength()) { // TODO - hashes
                return path;
            }
        }
        file.download(path);
        return path;
    }

    public void consider(SecureJar jar) throws IOException {
        final String modId = getModId(jar);
        if (modId != null) {
            jars.put(modId, jar);
        }
        collectJiJFrom(jar);
    }

    public void collectJiJFrom(SecureJar secureJar) throws IOException {
        final Path path = secureJar.getPath("META-INF/jarjar/metadata.json");
        if (Files.exists(path)) {
            final JsonArray array = new Gson().fromJson(
                    Files.newBufferedReader(path), JsonObject.class
            ).getAsJsonArray("jars");

            for (final JsonElement element : array) {
                final JsonObject obj = (JsonObject) element;
                final JsonObject identifier = obj.getAsJsonObject("identifier");

                final String id = identifier.get("group").getAsString() + ":" + identifier.get("artifact").getAsString();
                if (!jarJars.add(id)) continue;

                final SecureJar jar = SecureJar.from(secureJar.getPath(obj.get("path").getAsString()));
                consider(jar);
            }
        }
    }

    @Nullable
    public static String getModId(SecureJar jar) throws IOException {
        final Path path = jar.getPath("META-INF", "mods.toml");
        if (Files.exists(path)) {
            final CommentedConfig config = PARSER.parse(Files.newBufferedReader(path));
            return config.<List<CommentedConfig>>get("mods").get(0).get("modId");
        }
        return null;
    }

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(new RecordTypeAdapterFactory())
            .create();
    record FilePointer(int projectID, int fileID) {}
    record Manifest(List<FilePointer> files) {}
}
