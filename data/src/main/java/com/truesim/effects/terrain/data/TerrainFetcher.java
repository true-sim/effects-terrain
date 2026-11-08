package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Downloads a manifest's tiles into the terrain cache ({@code <cache>/downloads/<source>/<file>}): only what the cache
 * lacks or holds damaged, each checked against the manifest's SHA-256 and size before it takes its place (written
 * beside it and moved in, so an interrupted download never looks fetched). Only the URLs the manifest pins are
 * fetched, over HTTPS.
 */
@AllowAllocation("downloads, rarely")
public final class TerrainFetcher {

    private final Path cache;
    private final HttpClient client;
    private Consumer<String> log = line -> { };

    /** A fetcher into the terrain cache at {@code cache}. */
    public TerrainFetcher(Path cache) {
        this.cache = cache;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** Reports each tile fetched or found to {@code lines}. */
    public TerrainFetcher log(Consumer<String> lines) {
        this.log = lines;
        return this;
    }

    /** Where tile {@code tile} is kept in the cache. */
    public Path file(Manifest.Tile tile) {
        return cache.resolve("downloads").resolve(tile.source()).resolve(tile.fileName());
    }

    /** Fetches every tile of {@code manifest} the cache does not hold intact; returns how many were downloaded. */
    public int fetch(Manifest manifest) throws IOException, InterruptedException {
        int fetched = 0;
        for (Manifest.Tile tile : manifest.tiles()) {
            Path file = file(tile);
            if (Files.isRegularFile(file) && Files.size(file) == tile.bytes() && sha256(file).equals(tile.sha256())) {
                continue;
            }
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            HttpRequest request = HttpRequest.newBuilder(tile.url()).timeout(Duration.ofMinutes(10)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("Fetching " + tile.url() + ": HTTP " + response.statusCode());
            }
            MessageDigest digest = digest();
            long bytes = 0;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    bytes += n;
                }
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            if (bytes != tile.bytes() || !sha.equals(tile.sha256())) {
                Files.deleteIfExists(part);
                throw new IOException("Tile " + tile.id() + " of " + tile.source() + " from " + tile.url() + " is "
                    + bytes + " bytes with SHA-256 " + sha + ", the manifest pins " + tile.bytes() + " bytes and "
                    + tile.sha256());
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.accept("fetched " + tile.source() + " " + tile.id() + " (" + bytes / 1024 + " KiB)");
            fetched++;
        }
        return fetched;
    }

    /** The SHA-256 of {@code file}, hexadecimal. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JDK has SHA-256", e);
        }
    }
}
