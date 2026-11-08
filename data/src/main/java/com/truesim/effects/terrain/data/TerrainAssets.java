package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * The terrain's assets: sites ({@code .terrain} files and the {@code .dem} rasters beside them), loaded through the
 * asset manager like everything else.
 *
 * <pre>{@code
 * TerrainAssets.register(paths);                                    // the .terrain importer
 * TerrainAssets.mount(paths, cache);                                // terrain:// is <cache>/sites
 * AssetRef<TerrainSite> site = TerrainAssets.site(paths, "lauterbrunnen");
 * assets.acquire(site);                                             // loads in the background
 * }</pre>
 */
public final class TerrainAssets {

    /** A terrain site: its frame and its rasters. */
    public static final AssetType<TerrainSite> SITE = AssetType.of("terrain site", TerrainSite.class,
        TerrainSite::bytes);
    /** The mount the terrain cache's imported sites are under. */
    public static final String MOUNT = "terrain";

    /** Reads a {@code .terrain} file and the rasters it names, beside it. */
    public static final AssetLoader<TerrainSite> IMPORTER = new Importer();

    @AllowAllocation("loads a site, rarely")
    private static final class Importer implements AssetLoader<TerrainSite> {
        @Override
        public TerrainSite load(AssetInput input) throws Exception {
            try (InputStream in = input.open()) {
                return TerrainSite.read(in, input::open);
            }
        }
    }

    private TerrainAssets() {
    }

    /** Registers the {@code .terrain} importer with {@code paths}. */
    public static void register(PathAssets paths) {
        paths.importers().add(SITE, "terrain", IMPORTER);
    }

    /** Mounts the imported sites of the terrain cache {@code cache} as {@code terrain://}. */
    @AllowAllocation("once")
    public static void mount(PathAssets paths, Path cache) {
        paths.mounts().add(MOUNT, PathSource.folder(cache.resolve("sites")));
    }

    /** The site called {@code id}, at {@code terrain://<id>/site.terrain}. */
    @AllowAllocation("once")
    public static AssetRef<TerrainSite> site(PathAssets paths, String id) {
        return paths.ref(SITE, MOUNT + "://" + id + "/site.terrain");
    }
}
