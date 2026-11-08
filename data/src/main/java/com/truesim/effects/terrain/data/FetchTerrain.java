package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.nio.file.Path;

/**
 * Fetches a manifest's tiles into the terrain cache and imports its site: {@code FetchTerrain <manifest> <cache>}, as
 * the Gradle task {@code fetchTerrain} runs it. Prints the attributions anything showing the terrain must show.
 */
@AllowAllocation("a command")
public final class FetchTerrain {

    private FetchTerrain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("FetchTerrain <manifest> <cache>");
            System.exit(2);
        }
        Manifest manifest = Manifest.named(args[0]);
        Path cache = Path.of(args[1]).toAbsolutePath();
        int fetched = new TerrainFetcher(cache).log(System.out::println).fetch(manifest);
        System.out.println(fetched + " of " + manifest.tiles().size() + " tiles fetched, the rest were in " + cache);
        Path site = TerrainImporter.importSite(manifest, cache);
        System.out.println("Imported " + manifest.site().title() + " into " + site);
        for (Manifest.Source source : manifest.sources()) {
            System.out.println("  " + source.title() + ": " + source.attribution());
        }
    }
}
