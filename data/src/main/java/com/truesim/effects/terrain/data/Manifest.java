package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A site's pinned elevation sources: where the site is, which public sources cover it (finest first), their licences
 * and attributions, and every tile to fetch with its URL, SHA-256 and size. Read from a tab-separated text (the format
 * is described at the top of {@code lauterbrunnen.manifest}); the manifests ship as resources of this package.
 *
 * <pre>{@code
 * Manifest manifest = Manifest.named("lauterbrunnen");
 * new TerrainFetcher(cache).fetch(manifest);          // downloads what the cache lacks, checks every SHA-256
 * TerrainImporter.importSite(manifest, cache);        // the site the asset manager loads
 * }</pre>
 *
 * @param site where the terrain is
 * @param sources the sources, finest first
 * @param tiles every tile, of every source
 */
@AllowAllocation("read once")
public record Manifest(Site site, List<Source> sources, List<Tile> tiles) {

    /** The crs of a source: geographic latitude and longitude on WGS 84. */
    public static final String GEOGRAPHIC = "EPSG:4326";
    /** The crs of a source: the Swiss LV95 grid (CH1903+), metres. */
    public static final String LV95 = "EPSG:2056";

    /**
     * The site.
     *
     * @param id its name, the folder it is imported into
     * @param latitude the local frame's origin, degrees north
     * @param longitude the local frame's origin, degrees east
     * @param title what it is
     */
    public record Site(String id, double latitude, double longitude, String title) {
    }

    /**
     * A source of elevation data.
     *
     * @param id its name
     * @param crs {@link #GEOGRAPHIC} or {@link #LV95}
     * @param pixelIsPoint whether the georeference sits on a sample (true) or on its corner
     * @param blend metres over which it fades into the next source from its edge (0 for the last)
     * @param crop metres around the site's origin kept on import (0: all of it)
     * @param title what it is
     * @param licence its licence
     * @param attribution what must be shown with anything made from it
     */
    public record Source(String id, String crs, boolean pixelIsPoint, double blend, double crop, String title,
        String licence, String attribution) {
    }

    /**
     * A tile to fetch.
     *
     * @param source the source's id
     * @param id the tile's name in its source
     * @param url where it is (HTTPS)
     * @param sha256 its SHA-256, hexadecimal
     * @param bytes its size
     */
    public record Tile(String source, String id, URI url, String sha256, long bytes) {

        /** The file name the tile is kept under in the cache. */
        public String fileName() {
            String path = url.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public Manifest {
        Objects.requireNonNull(site, "site");
        sources = List.copyOf(sources);
        tiles = List.copyOf(tiles);
    }

    /** The source called {@code id}. */
    public Source source(String id) {
        for (Source source : sources) {
            if (source.id().equals(id)) {
                return source;
            }
        }
        throw new IllegalArgumentException("No source " + id + " in the manifest of " + site.id());
    }

    /** The tiles of source {@code id}. */
    public List<Tile> tiles(String id) {
        List<Tile> of = new ArrayList<>();
        for (Tile tile : tiles) {
            if (tile.source().equals(id)) {
                of.add(tile);
            }
        }
        return of;
    }

    /** The manifest called {@code name}, shipped as a resource of this package ({@code name.manifest}). */
    public static Manifest named(String name) {
        try (InputStream in = Manifest.class.getResourceAsStream(name + ".manifest")) {
            if (in == null) {
                throw new IllegalArgumentException("No manifest " + name);
            }
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a manifest. */
    public static Manifest read(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Site site = null;
        Map<String, String[]> sources = new LinkedHashMap<>();
        Map<String, String> licences = new LinkedHashMap<>();
        Map<String, String> attributions = new LinkedHashMap<>();
        List<Tile> tiles = new ArrayList<>();
        int number = 0;
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            number++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            switch (f[0]) {
                case "site" -> {
                    need(f, 5, number);
                    site = new Site(f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]), f[4]);
                }
                case "source" -> {
                    need(f, 7, number);
                    if (!f[2].equals(GEOGRAPHIC) && !f[2].equals(LV95)) {
                        throw new IOException("Line " + number + ": unknown crs " + f[2]);
                    }
                    sources.put(f[1], f);
                }
                case "licence" -> {
                    need(f, 3, number);
                    licences.put(f[1], f[2]);
                }
                case "attribution" -> {
                    need(f, 3, number);
                    attributions.put(f[1], f[2]);
                }
                case "tile" -> {
                    need(f, 6, number);
                    URI url = URI.create(f[3]);
                    if (!"https".equals(url.getScheme())) {
                        throw new IOException("Line " + number + ": tiles are fetched over HTTPS: " + url);
                    }
                    if (!f[4].matches("[0-9a-f]{64}")) {
                        throw new IOException("Line " + number + ": not a SHA-256: " + f[4]);
                    }
                    tiles.add(new Tile(f[1], f[2], url, f[4], Long.parseLong(f[5])));
                }
                default -> throw new IOException("Line " + number + ": unknown entry " + f[0]);
            }
        }
        if (site == null) {
            throw new IOException("A manifest names its site");
        }
        List<Source> list = new ArrayList<>();
        for (String[] f : sources.values()) {
            String licence = licences.get(f[1]);
            String attribution = attributions.get(f[1]);
            if (licence == null || attribution == null) {
                throw new IOException("Source " + f[1] + " has no licence or attribution: both are required");
            }
            list.add(new Source(f[1], f[2], f[3].equals("point"), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
                f[6], licence, attribution));
        }
        for (Tile tile : tiles) {
            if (!sources.containsKey(tile.source())) {
                throw new IOException("Tile " + tile.id() + " of unknown source " + tile.source());
            }
        }
        return new Manifest(site, list, tiles);
    }

    private static void need(String[] fields, int count, int line) throws IOException {
        if (fields.length < count) {
            throw new IOException("Line " + line + ": " + fields[0] + " needs " + (count - 1) + " fields, has "
                + (fields.length - 1));
        }
    }
}
