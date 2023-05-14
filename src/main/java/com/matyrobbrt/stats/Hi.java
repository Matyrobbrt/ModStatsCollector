package com.matyrobbrt.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.request.Method;
import io.github.matyrobbrt.curseforgeapi.request.Request;
import io.github.matyrobbrt.curseforgeapi.request.Requests;
import io.github.matyrobbrt.curseforgeapi.request.query.ModSearchQuery;
import io.github.matyrobbrt.curseforgeapi.schemas.mod.Mod;
import io.github.matyrobbrt.curseforgeapi.util.Utils;

import java.util.List;
import java.util.stream.IntStream;

public class Hi {
    public static void main(String[] args) throws Exception {
        final CurseForgeAPI API = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
                .apiKey(System.getenv("CF_TOKEN"))
                .build()).get();

//        final JsonObject jsonFull;
        https://www.curseforge.com/api/v1/mods/search?gameId=432&index=0&classId=6&pageSize=20&sortField=1&gameFlavors[0]=1
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
        System.out.println(API.makeRequest(Requests.getMod(829758)).orElseThrow());
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
