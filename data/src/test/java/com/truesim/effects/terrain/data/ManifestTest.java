package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The pinned manifest: every tile from a named public source over HTTPS with its SHA-256 and size, every source with
 * its licence and attribution, each attribution recorded in CREDITS.md; and what a manifest must say.
 */
class ManifestTest {

    @Test
    void lauterbrunnenPinsEveryTileOfItsThreeSources() throws IOException {
        Manifest manifest = Manifest.named("lauterbrunnen");
        assertThat(manifest.site().id()).isEqualTo("lauterbrunnen");
        assertThat(manifest.sources()).extracting(Manifest.Source::id)
            .containsExactly("swissalti3d", "glo30", "glo90");
        assertThat(manifest.tiles(("swissalti3d"))).hasSize(45);
        assertThat(manifest.tiles("glo30")).hasSize(2);
        assertThat(manifest.tiles("glo90")).hasSize(12);
        for (Manifest.Tile tile : manifest.tiles()) {
            assertThat(tile.url().getHost()).isIn("data.geo.admin.ch", "copernicus-dem-30m.s3.amazonaws.com",
                "copernicus-dem-90m.s3.amazonaws.com");
            assertThat(tile.sha256()).hasSize(64);
            assertThat(tile.bytes()).isPositive();
        }
        String credits = Files.readString(Path.of("..", "CREDITS.md"));
        for (Manifest.Source source : manifest.sources()) {
            assertThat(source.licence()).isNotBlank();
            assertThat(credits).as("CREDITS.md records %s's attribution", source.id()).contains(source.attribution());
        }
    }

    @Test
    void aManifestNamesItsSiteAndEverySourceItsLicence() {
        assertThatThrownBy(() -> read("source\ta\tEPSG:4326\tpoint\t0\t0\tA\n")).hasMessageContaining("names its site");
        assertThatThrownBy(() -> read("site\ts\t1\t2\tS\nsource\ta\tEPSG:4326\tpoint\t0\t0\tA\n"))
            .hasMessageContaining("no licence or attribution");
        assertThatThrownBy(() -> read("site\ts\t1\t2\tS\ntile\ta\tt\thttp://x/t.tif\t" + "0".repeat(64) + "\t1\n"))
            .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> read("site\ts\t1\t2\tS\nsource\ta\tEPSG:31287\tpoint\t0\t0\tA\n"))
            .hasMessageContaining("unknown crs");
    }

    private static Manifest read(String text) throws IOException {
        return Manifest.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
