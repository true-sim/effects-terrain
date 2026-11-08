package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Lauterbrunnen site as fetched and imported ({@code ./gradlew fetchTerrain}; skipped where the terrain cache does
 * not hold it, as on CI, which never downloads): the village, the valley floor and the summits around stand at their
 * surveyed heights, and every source agrees with the others where they overlap.
 */
class RealSiteTest {

    private static TerrainSite site;

    /** The terrain cache: {@code -Dtruesim.terrain.cache}, else the repository's {@code terrain-cache}. */
    static Path cache() {
        String given = System.getProperty("truesim.terrain.cache");
        return given != null ? Path.of(given) : Path.of("..", "terrain-cache");
    }

    /** The imported Lauterbrunnen site, or null where it is not in the cache. */
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

    @BeforeAll
    static void load() throws IOException {
        site = lauterbrunnen();
    }

    private double at(double latitude, double longitude, double footprint) {
        LocalFrame frame = site.frame();
        double z = frame.z(latitude);
        double x = frame.x(latitude, longitude);
        return site.height(x, z, footprint);
    }

    @Test
    void summitsAndTheVillageStandAtTheirSurveyedHeights() {
        assumeThat(site).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        // The village's church, on the valley floor (swissALTI3D)
        assertThat(at(46.5937, 7.9087, 2)).as("Lauterbrunnen").isCloseTo(800, within(20.0));
        // Summits, at 30 m and 90 m (a DSM: the peak is sampled between its samples, a little below)
        assertThat(at(46.53672, 7.96262, 30)).as("Jungfrau, 4158 m").isCloseTo(4130, within(60.0));
        assertThat(at(46.57750, 8.00528, 30)).as("Eiger, 3967 m").isCloseTo(3940, within(60.0));
        assertThat(at(45.83265, 6.86502, 90)).as("Mont Blanc, 4806 m, 120 km away").isCloseTo(4760, within(90.0));
        // Lake Thun's surface, 558 m
        assertThat(at(46.70, 7.73, 30)).as("Lake Thun").isCloseTo(558, within(6.0));
    }

    @Test
    void sourcesAgreeWhereTheyOverlap() {
        assumeThat(site).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        // Within the swissALTI3D area: the 30 m data is a surface model (over forest and houses), the 2 m data bare
        // ground: the middle of their differences is the canopy's few metres, not a shifted grid's tens
        TerrainSite.Source swiss = site.sources().get(0);
        TerrainSite.Source glo30 = site.sources().get(1);
        LocalFrame frame = site.frame();
        java.util.List<Double> differences = new java.util.ArrayList<>();
        for (double z = -3000; z <= 3000; z += 250) {
            for (double x = -1500; x <= 1500; x += 250) {
                double lat = frame.latitude(z);
                double lon = frame.longitude(x, lat);
                double fine = swiss.raster().sample(SwissGrid.easting(lat, lon), SwissGrid.northing(lat, lon),
                    swiss.raster().levelFor(30), false);
                double coarse = glo30.raster().sample(lon, lat, 0, false);
                if (!Double.isNaN(fine) && !Double.isNaN(coarse)) {
                    differences.add(coarse - fine);
                }
            }
        }
        assertThat(differences).hasSizeGreaterThan(50);
        java.util.Collections.sort(differences);
        assertThat(differences.get(differences.size() / 2)).as("median GLO-30 above swissALTI3D, metres")
            .isBetween(0.0, 15.0);
    }
}
