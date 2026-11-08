package com.truesim.effects.terrain;

import com.truesim.effects.terrain.data.DemRaster;
import com.truesim.effects.terrain.data.LocalFrame;
import com.truesim.effects.terrain.data.TerrainSite;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sites for the tests: a synthetic mountain range built in code (no download, as CI runs), and the real Lauterbrunnen
 * site where the terrain cache holds it.
 */
final class TestSites {

    /** The synthetic site's origin. */
    static final LocalFrame FRAME = new LocalFrame(46.5937, 7.9087);

    private TestSites() {
    }

    /**
     * Rugged mountains, 1 arc second (about 21 by 31 m) apart, {@code size} samples a side round the origin: ridges and
     * valleys of a few kilometres, 500 to 2900 m high, with steep faces.
     */
    static TerrainSite rugged(int size) {
        double step = 1 / 3600.0;
        float[] heights = new float[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double x = i * 0.021;
                double y = j * 0.031;
                double ridge = 1 - Math.abs(Math.sin(x * 0.9 + Math.sin(y * 0.4) * 1.5));
                double h = 1200 + 900 * ridge * ridge + 500 * Math.sin(x * 0.27) * Math.cos(y * 0.33)
                    + 120 * Math.sin(x * 3.1 + y * 2.3) + 40 * Math.sin(x * 9.7 - y * 7.1);
                heights[j * size + i] = (float) h;
            }
        }
        DemRaster raster = new DemRaster(DemRaster.GEOGRAPHIC, FRAME.longitude() - step * (size / 2),
            FRAME.latitude() + step * (size / 2), step, step, size, size, heights);
        return new TerrainSite("rugged", "synthetic mountains", FRAME,
            List.of(new TerrainSite.Source("rugged", raster, 0, "synthetic")));
    }

    /** The terrain cache: {@code -Dtruesim.terrain.cache}, else the repository's {@code terrain-cache}. */
    static Path cache() {
        String given = System.getProperty("truesim.terrain.cache");
        return given != null ? Path.of(given) : Path.of("..", "terrain-cache");
    }

    /** The imported Lauterbrunnen site, or null where the cache does not hold it. */
    static TerrainSite lauterbrunnen() throws IOException {
        Path folder = cache().resolve("sites").resolve("lauterbrunnen");
        Path file = folder.resolve("site.terrain");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return TerrainSite.read(in, name -> Files.newInputStream(folder.resolve(name)));
        }
    }
}
