package com.matyrobbrt.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.request.Method;
import io.github.matyrobbrt.curseforgeapi.request.Request;
import io.github.matyrobbrt.curseforgeapi.request.Requests;
import io.github.matyrobbrt.curseforgeapi.request.query.ModSearchQuery;
import io.github.matyrobbrt.curseforgeapi.schemas.file.FileIndex;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.Mod;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.ModLoaderType;
import io.github.matyrobbrt.curseforgeapi.util.Constants;
import io.github.matyrobbrt.curseforgeapi.util.Utils;

import java.util.List;
import java.util.stream.IntStream;

public class Hi {
    public static void main(String[] args) throws Exception {
        final CurseForgeAPI API = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
                .apiKey(System.getenv("CF_TOKEN"))
                .build()).get();

        final var res = API.getHelper().searchModsPaginated(ModSearchQuery.of(Constants.GameIDs.MINECRAFT)
                        .gameVersion("1.19.2")
                        .sortField(ModSearchQuery.SortField.LAST_UPDATED)
                        .sortOrder(ModSearchQuery.SortOrder.ASCENDENT)
                        .modLoaderType(ModLoaderType.FORGE).classId(6)
                        .pageSize(50).index(0))
                .orElseThrow();

        System.out.println(res.data().stream()
                .flatMap(m -> m.latestFilesIndexes().stream().filter(f -> f.gameVersion().equals("1.19.2") && f.modLoader() != null && (f.modLoaderType() == ModLoaderType.FORGE)).limit(1))
                .count());

//        final var response = API.getHelper().searchModsPaginated(ModSearchQuery.of(Constants.GameIDs.MINECRAFT)
//                        .gameVersion("1.19.2")
//                        .sortField(ModSearchQuery.SortField.LAST_UPDATED)
//                        .sortOrder(ModSearchQuery.SortOrder.ASCENDENT)
//                        .modLoaderType(ModLoaderType.FORGE).classId(6)
//                        .pageSize(50).index(0))
//                .orElseThrow();
//        final var main = response.data().stream()
//                .flatMap(m -> m.latestFilesIndexes().stream().filter(f -> f.gameVersion().equals("1.19.2")).limit(1))
//                .toList();
//        System.out.println("Main Files: " + main.size());
//        System.out.println("Files: " + API.getHelper().getFiles(response.data().stream()
//                .flatMap(mod -> mod.latestFilesIndexes().stream()
//                        .filter(f -> f.gameVersion().equals("1.19.2") && f.modLoader() != null && f.modLoaderType() == ModLoaderType.FORGE)
//                        .limit(1)).mapToInt(FileIndex::fileId)
//                .toArray()).orElseThrow().size());

//        final JsonObject jsonFull;
        //https://www.curseforge.com/api/v1/mods/search?gameId=432&index=0&classId=6&pageSize=20&sortField=1&gameFlavors[0]=1
//        try (final var is = new URL("https://www.curseforge.com/api/v1/mods/search?gameId=432&index=0&classId=6&pageSize=20&sortField=5").openStream()) {
//            jsonFull = API.getGson().fromJson(new InputStreamReader(is), JsonObject.class);
//        }
//
//        final var res = API.getGson().fromJson(
//                jsonFull.getAsJsonArray("data"),
//                Requests.Types.MOD_LIST
//        );
//
//        System.out.println(res);
    }

    public static Request<List<Mod>> getMods(int... modIds) {
        final var body = new JsonObject();
        final var array = new JsonArray();
        for (final var id : modIds) {
            array.add(id);
        }
        body.add("modIds", array);
        return new Request<>("/v1/mods", Method.POST, body, "data", Requests.Types.MOD_LIST);
    }

    @Override
    public String toString() {
        return super.toString();
    }
}
