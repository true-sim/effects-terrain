package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An imported site: its local frame and its elevation sources, finest first, each fading into the next over its blend
 * width from its edge, the last clamped at its own edge so the terrain has a height everywhere. Loaded through the
 * asset manager ({@link TerrainAssets#SITE}) from a {@code .terrain} file naming its {@code .dem} rasters beside it.
 */
public final class TerrainSite {

    /**
     * One of its sources.
     *
     * @param id the source's name
     * @param raster its heights
     * @param blend metres over which it fades into the next source from its edge
     * @param attribution what must be shown with terrain made from it
     */
    public record Source(String id, DemRaster raster, double blend, String attribution) {
    }

    private final String id;
    private final String title;
    private final LocalFrame frame;
    private final List<Source> sources;

    /** A site {@code id} round the origin of {@code frame}, from {@code sources}, finest first. */
    public TerrainSite(String id, String title, LocalFrame frame, List<Source> sources) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("A site has at least one source");
        }
        this.id = id;
        this.title = title;
        this.frame = frame;
        this.sources = List.copyOf(sources);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public LocalFrame frame() {
        return frame;
    }

    public List<Source> sources() {
        return sources;
    }

    /** The attributions anything showing this terrain must show, one per source. */
    @AllowAllocation("asked rarely")
    public List<String> attributions() {
        List<String> all = new ArrayList<>();
        for (Source source : sources) {
            if (!all.contains(source.attribution())) {
                all.add(source.attribution());
            }
        }
        return all;
    }

    /** Bytes its rasters take. */
    public long bytes() {
        long bytes = 0;
        for (Source source : sources) {
            bytes += source.raster().bytes();
        }
        return bytes;
    }

    /**
     * The elevation data's height at ({@code x}, {@code z}) of the local frame, metres above sea level, sampled from
     * each source's pyramid at the level for samples {@code footprint} metres apart and blended at their edges.
     */
    public double height(double x, double z, double footprint) {
        double lat = frame.latitude(z);
        double lon = frame.longitude(x, lat);
        double east = Double.NaN;
        double north = Double.NaN;
        int last = sources.size() - 1;
        double h = Double.NaN;
        for (int s = last; s >= 0; s--) {
            DemRaster raster = sources.get(s).raster();
            double cx;
            double cy;
            if (raster.crs() == DemRaster.LV95) {
                if (Double.isNaN(east)) {
                    east = SwissGrid.easting(lat, lon);
                    north = SwissGrid.northing(lat, lon);
                }
                cx = east;
                cy = north;
            } else {
                cx = lon;
                cy = lat;
            }
            double v = raster.sample(cx, cy, raster.levelFor(footprint), s == last);
            if (Double.isNaN(v)) {
                continue;
            }
            double w = Double.isNaN(h) ? 1 : weight(raster.inside(cx, cy), sources.get(s).blend());
            h = Double.isNaN(h) ? v : h + (v - h) * w;
        }
        return Double.isNaN(h) ? 0 : h;
    }

    /** A source's weight {@code inside} metres within its edge, fading in over {@code blend} metres (smoothstep). */
    private static double weight(double inside, double blend) {
        if (blend <= 0) {
            return inside >= 0 ? 1 : 0;
        }
        double t = Math.clamp(inside / blend, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Writes the site's {@code .terrain} file, naming each source's raster {@code <id>.dem} beside it. */
    @AllowAllocation("import")
    public void write(OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write("# A true-sim terrain site (effects-terrain), imported from its manifest.\n");
        w.write("site\t" + id + "\t" + frame.latitude() + "\t" + frame.longitude() + "\t" + title + "\n");
        for (Source source : sources) {
            w.write("source\t" + source.id() + "\t" + source.id() + ".dem\t" + source.blend() + "\t"
                + source.attribution() + "\n");
        }
        w.flush();
    }

    /** Opens a resource of a site's folder by name. */
    @FunctionalInterface
    public interface Resources {
        /** Opens {@code name}; the caller closes it. */
        InputStream open(String name) throws IOException;
    }

    /** Reads a site from its {@code .terrain} file, its rasters from {@code resources}. */
    @AllowAllocation("load")
    public static TerrainSite read(InputStream in, Resources resources) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String id = null;
        String title = null;
        LocalFrame frame = null;
        List<Source> sources = new ArrayList<>();
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f[0].equals("site") && f.length >= 5) {
                id = f[1];
                frame = new LocalFrame(Double.parseDouble(f[2]), Double.parseDouble(f[3]));
                title = f[4];
            } else if (f[0].equals("source") && f.length >= 5) {
                DemRaster raster;
                try (InputStream dem = resources.open(f[2])) {
                    raster = DemRaster.read(new BufferedInputStream(dem, 1 << 16));
                }
                sources.add(new Source(f[1], raster, Double.parseDouble(f[3]), f[4]));
            } else {
                throw new IOException("Unknown entry in a .terrain file: " + line);
            }
        }
        if (frame == null || sources.isEmpty()) {
            throw new IOException("A .terrain file names its site and at least one source");
        }
        return new TerrainSite(id, title, frame, sources);
    }
}
