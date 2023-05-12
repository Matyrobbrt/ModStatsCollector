package com.matyrobbrt.stats;

import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI;
import io.github.matyrobbrt.curseforgeapi.request.Requests;
import io.github.matyrobbrt.curseforgeapi.util.Utils;

public class Hi {
    public static void main(String[] args) throws Exception {
        final CurseForgeAPI API = Utils.rethrowSupplier(() -> CurseForgeAPI.builder()
                .apiKey(System.getenv("CF_TOKEN"))
                .build()).get();

//        final JsonObject jsonFull;
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
        System.out.println(API.makeRequest(Requests.getMod(520914))
                .get());;
    }

    @Override
    public String toString() {
        return super.toString();
    }
}
