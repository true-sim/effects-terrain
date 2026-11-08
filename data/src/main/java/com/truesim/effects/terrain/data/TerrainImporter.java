package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Imports a manifest's fetched tiles into a site the asset manager loads: each source's GeoTIFFs merged into one
 * raster on their common grid, cropped to the source's radius round the site, written as {@code <source>.dem} beside
 * the site's {@code site.terrain} in {@code <cache>/sites/<site>/}.
 */
@AllowAllocation("import, once")
public final class TerrainImporter {

    private TerrainImporter() {
    }

    /** Where {@code manifest}'s site is imported in the cache {@code cache}. */
    public static Path siteFolder(Path cache, Manifest manifest) {
        return cache.resolve("sites").resolve(manifest.site().id());
    }

    /** Imports {@code manifest}'s tiles, fetched into {@code cache}; returns the site's {@code .terrain} file. */
    public static Path importSite(Manifest manifest, Path cache) throws IOException {
        Path folder = siteFolder(cache, manifest);
        Files.createDirectories(folder);
        LocalFrame frame = new LocalFrame(manifest.site().latitude(), manifest.site().longitude());
        TerrainFetcher fetcher = new TerrainFetcher(cache);
        List<TerrainSite.Source> sources = new ArrayList<>();
        for (Manifest.Source source : manifest.sources()) {
            List<GeoTiff> tiffs = new ArrayList<>();
            for (Manifest.Tile tile : manifest.tiles(source.id())) {
                Path file = fetcher.file(tile);
                if (!Files.isRegularFile(file)) {
                    throw new IOException("Tile " + tile.id() + " of " + source.id() + " is not fetched: " + file);
                }
                tiffs.add(GeoTiff.read(file));
            }
            if (tiffs.isEmpty()) {
                throw new IOException("Source " + source.id() + " has no tiles");
            }
            DemRaster raster = merge(source, frame, tiffs);
            write(folder.resolve(source.id() + ".dem"), raster::write);
            sources.add(new TerrainSite.Source(source.id(), raster, source.blend(), source.attribution()));
        }
        TerrainSite site = new TerrainSite(manifest.site().id(), manifest.site().title(), frame, sources);
        Path file = folder.resolve("site.terrain");
        write(file, site::write);
        return file;
    }

    /** Writes a file through a temporary beside it, moved in once whole. */
    private static void write(Path file, Writer writer) throws IOException {
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
            writer.write(out);
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @FunctionalInterface
    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    /** The source's tiles on one grid, cropped to its radius round the site (all of them without one). */
    static DemRaster merge(Manifest.Source source, LocalFrame frame, List<GeoTiff> tiffs) throws IOException {
        GeoTiff first = tiffs.getFirst();
        double dx = first.dx();
        double dy = first.dy();
        double x0 = first.centreX(0);
        double y0 = first.centreY(0);
        // The window of the common grid (column and row of sample centres, relative to the first tile's (0, 0))
        double minI = Double.MAX_VALUE;
        double maxI = -Double.MAX_VALUE;
        double minJ = Double.MAX_VALUE;
        double maxJ = -Double.MAX_VALUE;
        for (GeoTiff tiff : tiffs) {
            if (Math.abs(tiff.dx() - dx) > dx * 1e-9 || Math.abs(tiff.dy() - dy) > dy * 1e-9) {
                throw new IOException("Tiles of " + source.id() + " have different sample steps");
            }
            double i = (tiff.centreX(0) - x0) / dx;
            double j = (y0 - tiff.centreY(0)) / dy;
            if (Math.abs(i - Math.rint(i)) > 1e-4 || Math.abs(j - Math.rint(j)) > 1e-4) {
                throw new IOException("Tiles of " + source.id() + " are not on one grid");
            }
            minI = Math.min(minI, Math.rint(i));
            maxI = Math.max(maxI, Math.rint(i) + tiff.width() - 1);
            minJ = Math.min(minJ, Math.rint(j));
            maxJ = Math.max(maxJ, Math.rint(j) + tiff.height() - 1);
        }
        if (source.crop() > 0) {
            double[] box = cropBox(source, frame);
            minI = Math.max(minI, Math.floor((box[0] - x0) / dx) - 1);
            maxI = Math.min(maxI, Math.ceil((box[2] - x0) / dx) + 1);
            minJ = Math.max(minJ, Math.floor((y0 - box[3]) / dy) - 1);
            maxJ = Math.min(maxJ, Math.ceil((y0 - box[1]) / dy) + 1);
        }
        int width = (int) (maxI - minI + 1);
        int height = (int) (maxJ - minJ + 1);
        if (width < 2 || height < 2) {
            throw new IOException("The tiles of " + source.id() + " do not reach the site");
        }
        float[] heights = new float[width * height];
        Arrays.fill(heights, Float.NaN);
        for (GeoTiff tiff : tiffs) {
            int ti = (int) Math.rint((tiff.centreX(0) - x0) / dx - minI);
            int tj = (int) Math.rint((y0 - tiff.centreY(0)) / dy - minJ);
            for (int row = Math.max(0, -tj); row < tiff.height() && tj + row < height; row++) {
                for (int column = Math.max(0, -ti); column < tiff.width() && ti + column < width; column++) {
                    float h = tiff.at(column, row);
                    if (!Float.isNaN(h)) {
                        heights[(tj + row) * width + ti + column] = h;
                    }
                }
            }
        }
        int crs = source.crs().equals(Manifest.LV95) ? DemRaster.LV95 : DemRaster.GEOGRAPHIC;
        return new DemRaster(crs, x0 + minI * dx, y0 - minJ * dy, dx, dy, width, height, heights);
    }

    /** The box (min x, min y, max x, max y) of the source's crs holding the square of its crop radius round the site. */
    private static double[] cropBox(Manifest.Source source, LocalFrame frame) {
        double r = source.crop();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean lv95 = source.crs().equals(Manifest.LV95);
        for (int k = 0; k <= 32; k++) { // along the square's edge: the parallels' widths vary
            double t = -r + 2 * r * k / 32;
            double[][] points = {{t, -r}, {t, r}, {-r, t}, {r, t}};
            for (double[] p : points) {
                double lat = frame.latitude(p[1]);
                double lon = frame.longitude(p[0], lat);
                double cx = lv95 ? SwissGrid.easting(lat, lon) : lon;
                double cy = lv95 ? SwissGrid.northing(lat, lon) : lat;
                minX = Math.min(minX, cx);
                maxX = Math.max(maxX, cx);
                minY = Math.min(minY, cy);
                maxY = Math.max(maxY, cy);
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }
}
