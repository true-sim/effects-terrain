package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sun.net.httpserver.HttpServer;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fetcher and the importer end to end, against a local server standing in for the sources: tiles download into
 * the cache once, checked against their SHA-256 (a changed tile is refused and leaves nothing behind), merge into one
 * raster per source cropped round the site, and the site loads through the asset manager with its heights.
 */
class FetchAndImportTest {

    private static final double STEP = 1 / 1200.0; // GLO-90's
    private static final int SIZE = 120;            // a tenth of a degree a tile

    @TempDir
    Path cache;
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private Map<String, byte[]> files;

    /** A tile of SIZE x SIZE samples, its corner sample at (lon, lat): heights from the place. */
    private static byte[] tile(double lon, double lat) throws IOException {
        float[] heights = new float[SIZE * SIZE];
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                heights[j * SIZE + i] = (float) height(lon + i * STEP, lat - j * STEP);
            }
        }
        return new TiffWriter(ByteOrder.LITTLE_ENDIAN, 32, 3, SIZE, SIZE).tiled(64, 64).compression(8).predictor(3)
            .georeference(lon, lat, STEP, STEP, true).write(heights);
    }

    private static double height(double lon, double lat) {
        return 1000 + (lon - 7.9) * 5000 + (lat - 46.6) * 3000;
    }

    @BeforeEach
    void serve() throws IOException {
        files = Map.of("/a.tif", tile(7.80, 46.70), "/b.tif", tile(7.90, 46.70), "/c.tif", tile(7.80, 46.60),
            "/d.tif", tile(7.90, 46.60));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = files.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Manifest manifest(double crop) throws Exception {
        List<Manifest.Tile> tiles = new java.util.ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            byte[] body = files.get("/" + name + ".tif");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            tiles.add(new Manifest.Tile("dem", name, URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/" + name + ".tif"), sha, body.length));
        }
        return new Manifest(new Manifest.Site("test", 46.62, 7.93, "a test"), List.of(new Manifest.Source("dem",
            Manifest.GEOGRAPHIC, true, 0, crop, "a test source", "free", "© test")), tiles);
    }

    @Test
    void tilesDownloadOnceAndImportIntoASiteTheAssetManagerLoads() throws Exception {
        Manifest manifest = manifest(4000);
        TerrainFetcher fetcher = new TerrainFetcher(cache);
        assertThat(fetcher.fetch(manifest)).isEqualTo(4);
        assertThat(fetcher.fetch(manifest)).as("already in the cache").isZero();
        assertThat(requests.get()).isEqualTo(4);
        Path file = TerrainImporter.importSite(manifest, cache);
        assertThat(file).exists();
        // Through the asset manager: the mount, the importer, the site's rasters beside it
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache assets = new AssetCache(tasks)) {
            PathAssets paths = new PathAssets(assets);
            TerrainAssets.register(paths);
            TerrainAssets.mount(paths, cache);
            AssetRef<TerrainSite> ref = TerrainAssets.site(paths, "test");
            assets.acquire(ref);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (assets.get(ref) == null && assets.failure(ref) == null) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
                tasks.drainCompleted();
            }
            assertThat(assets.failure(ref)).isNull();
            TerrainSite site = assets.get(ref);
            assertThat(site.attributions()).containsExactly("© test");
            DemRaster raster = site.sources().getFirst().raster();
            // Cropped to 4 km round the site: about 0.07 degrees of 0.2 across, on the tiles' grid
            assertThat(raster.width()).isBetween(100, 140);
            assertThat(raster.height()).isBetween(80, 120);
            LocalFrame frame = site.frame();
            double lat = 46.6312;
            double lon = 7.9105;
            assertThat(site.height(frame.x(lat, lon), frame.z(lat), 1)).isCloseTo(height(lon, lat), within(0.01));
            assets.release(ref);
        }
    }

    @Test
    void aTileNotAsPinnedIsRefusedAndLeavesNothingBehind() throws Exception {
        Manifest manifest = manifest(0);
        Manifest.Tile pinned = manifest.tiles().getFirst();
        Manifest.Tile changed = new Manifest.Tile(pinned.source(), pinned.id(), pinned.url(), "0".repeat(64),
            pinned.bytes());
        Manifest wrong = new Manifest(manifest.site(), manifest.sources(), List.of(changed));
        TerrainFetcher fetcher = new TerrainFetcher(cache);
        assertThatThrownBy(() -> fetcher.fetch(wrong)).hasMessageContaining("the manifest pins");
        assertThat(Files.exists(fetcher.file(changed))).isFalse();
        assertThat(Files.list(fetcher.file(changed).getParent())).isEmpty();
        Manifest missing = new Manifest(manifest.site(), manifest.sources(), List.of(new Manifest.Tile("dem", "x",
            URI.create(pinned.url().resolve("/missing.tif").toString()), pinned.sha256(), 1)));
        assertThatThrownBy(() -> fetcher.fetch(missing)).hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> TerrainImporter.importSite(manifest, cache)).hasMessageContaining("is not fetched");
        // Uncropped: the four tiles merge into one raster of twice their side
        fetcher.fetch(manifest);
        TerrainImporter.importSite(manifest, cache);
        DemRaster all = DemRaster.read(Files.newInputStream(TerrainImporter.siteFolder(cache, manifest)
            .resolve("dem.dem")));
        assertThat(all.width()).isEqualTo(2 * SIZE);
        assertThat(all.height()).isEqualTo(2 * SIZE);
        assertThat(all.at(SIZE + 3, SIZE + 5)).isEqualTo((float) height(7.90 + 3 * STEP, 46.60 - 5 * STEP));
        assertThat(all.levelCount()).isGreaterThan(5);
        assertThat(all.levelFor(1000)).isGreaterThan(2);
    }
}
