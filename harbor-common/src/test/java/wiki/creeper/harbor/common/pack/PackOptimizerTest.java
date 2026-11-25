package wiki.creeper.harbor.common.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackOptimizerTest {

    private static final PackBuilder.Meta META = new PackBuilder.Meta("97.1", "t");

    // ------------------------------------------------------------------ PNG

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Exact RGBA per pixel read with ImageIO (raster samples, not colour-managed getRGB). */
    private static int[] pixels(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int w = img.getWidth();
        int h = img.getHeight();
        int[] out = new int[w * h];
        Raster r = img.getRaster();
        int bands = r.getNumBands();
        boolean indexed = img.getColorModel() instanceof IndexColorModel;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb;
                if (indexed) {
                    argb = img.getRGB(x, y);
                } else if (bands == 1) {
                    int g = r.getSample(x, y, 0);
                    argb = 0xff000000 | g << 16 | g << 8 | g;
                } else if (bands == 2) {
                    int g = r.getSample(x, y, 0);
                    argb = r.getSample(x, y, 1) << 24 | g << 16 | g << 8 | g;
                } else if (bands == 3) {
                    argb =
                            0xff000000
                                    | r.getSample(x, y, 0) << 16
                                    | r.getSample(x, y, 1) << 8
                                    | r.getSample(x, y, 2);
                } else {
                    argb =
                            r.getSample(x, y, 3) << 24
                                    | r.getSample(x, y, 0) << 16
                                    | r.getSample(x, y, 1) << 8
                                    | r.getSample(x, y, 2);
                }
                out[y * w + x] = argb;
            }
        }
        return out;
    }

    private static void assertLossless(BufferedImage img) throws IOException {
        byte[] original = png(img);
        byte[] optimized = PngOptimizer.optimize(original);
        assertTrue(optimized.length <= original.length);
        assertArrayEquals(pixels(original), pixels(optimized));
    }

    @Test
    void noisyRgbaKeepsEveryPixelIncludingHiddenColours() throws IOException {
        BufferedImage img = new BufferedImage(40, 33, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(7);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = random.nextInt(4) == 0 ? 0 : random.nextInt(256);
                img.setRGB(x, y, a << 24 | random.nextInt(1 << 24));
            }
        }
        assertLossless(img);
    }

    @Test
    void fewColoursBecomePaletteLosslessly() throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] colours = {0x00123456, 0xff336699, 0x80ff0000};
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, colours[(x / 5 + y / 3) % colours.length]);
            }
        }
        byte[] original = png(img);
        byte[] optimized = PngOptimizer.optimize(original);
        assertTrue(optimized.length < original.length);
        assertArrayEquals(pixels(original), pixels(optimized));
        // colour type 3 (palette) in IHDR
        assertEquals(3, optimized[25]);
    }

    @Test
    void greyImageStaysExact() throws IOException {
        BufferedImage img = new BufferedImage(50, 20, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 50; x++) {
                int g = (x * 5 + y * 3) & 0xff;
                img.setRGB(x, y, 0xff000000 | g << 16 | g << 8 | g);
            }
        }
        assertLossless(img);
    }

    @Test
    void reencodedOptimizedPngIsStable() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(3, 4, 0xff00ff00);
        byte[] once = PngOptimizer.optimize(png(img));
        assertArrayEquals(once, PngOptimizer.optimize(once));
    }

    @Test
    void notAPngIsReturnedAsIs() {
        byte[] junk = "not a png".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(junk, PngOptimizer.optimize(junk));
    }

    // ------------------------------------------------------------------ JSON

    @Test
    void jsonIsMinifiedAndModelBloatDropped() {
        String model =
                """
                {
                  // comment
                  "credit": "Made with Blockbench",
                  "textures": {"0": "harbor:item/x"},
                  "elements": [{"from": [0, 0, 0], "to": [1.5, 2, 3]}],
                  "groups": [{"name": "root", "children": [0]}]
                }
                """;
        String out =
                new String(
                        PackOptimizer.minifyJson(
                                "assets/harbor/models/item/x.json",
                                model.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);
        assertEquals(
                "{\"textures\":{\"0\":\"harbor:item/x\"},\"elements\":[{\"from\":[0,0,0],\"to\":[1.5,2,3]}]}",
                out);
        // Outside models/ the keys are someone's data: kept
        String other =
                new String(
                        PackOptimizer.minifyJson(
                                "assets/harbor/items/x.json",
                                "{ \"credit\": 1 }".getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);
        assertEquals("{\"credit\":1}", other);
    }

    @Test
    void brokenJsonIsKept() {
        byte[] broken = "{\"a\": [1, 2".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(broken, PackOptimizer.minifyJson("assets/x/a.json", broken));
    }

    // ------------------------------------------------------------------ File selection + zip

    private static void write(Path root, String path, byte[] data) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    @Test
    void unknownFilesAreLeftOutAndAudioGetsAHint(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src");
        // High-entropy texture: deflating the PNG again cannot shrink it, so it is stored
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(3);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, random.nextInt());
            }
        }
        write(src, "assets/harbor/textures/item/a.png", png(img));
        write(
                src,
                "assets/harbor/models/item/a.json",
                "{\n  \"parent\": \"x\"\n}".getBytes(StandardCharsets.UTF_8));
        write(src, "assets/harbor/README.md", "# notes".getBytes(StandardCharsets.UTF_8));
        write(src, "assets/harbor/models/item/a.bbmodel", "{}".getBytes(StandardCharsets.UTF_8));
        write(src, "assets/harbor/sounds/music/theme.mp3", new byte[] {1, 2, 3});
        write(src, "assets/minecraft/texts/splashes.txt", "hi".getBytes(StandardCharsets.UTF_8));

        PackBuilder.Built built =
                PackBuilder.build(
                        src,
                        List.of(),
                        dir.resolve("a.zip"),
                        META,
                        Map.of(),
                        new PackBuilder.Settings(
                                new PackOptimizer.Options(true, dir.resolve("cache")), Set.of()));

        try (ZipFile zip = new ZipFile(built.zip().toFile())) {
            assertTrue(zip.getEntry("assets/minecraft/texts/splashes.txt") != null);
            assertTrue(zip.getEntry("assets/harbor/README.md") == null);
            assertTrue(zip.getEntry("assets/harbor/models/item/a.bbmodel") == null);
            assertTrue(zip.getEntry("assets/harbor/sounds/music/theme.mp3") == null);
            assertEquals(
                    "{\"parent\":\"x\"}",
                    new String(
                            zip.getInputStream(zip.getEntry("assets/harbor/models/item/a.json"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
            ZipEntry pngEntry = zip.getEntry("assets/harbor/textures/item/a.png");
            assertEquals(ZipEntry.STORED, pngEntry.getMethod());
        }
        assertEquals(3, built.ignored());
        assertTrue(
                built.notes().stream()
                        .anyMatch(n -> n.contains("theme.mp3") && n.contains(".ogg")));
        assertTrue(Files.isDirectory(dir.resolve("cache")));

        // keep brings a type back in
        PackBuilder.Built kept =
                PackBuilder.build(
                        src,
                        List.of(),
                        dir.resolve("b.zip"),
                        META,
                        Map.of(),
                        new PackBuilder.Settings(PackOptimizer.Options.NONE, Set.of("bbmodel")));
        try (ZipFile zip = new ZipFile(kept.zip().toFile())) {
            assertTrue(zip.getEntry("assets/harbor/models/item/a.bbmodel") != null);
        }
        assertFalse(kept.notes().stream().anyMatch(n -> n.contains("a.bbmodel")));
    }

    // ------------------------------------------------------------------ Zip conformance

    private static Path zipPack(Path dir, String name, ZipConformance level) throws IOException {
        Path src = dir.resolve("zsrc");
        String big =
                "{\"textures\":{\"0\":\"harbor:item/a\"}," + "\"x\":1,".repeat(200) + "\"y\":2}";
        write(src, "assets/harbor/models/item/a.json", big.getBytes(StandardCharsets.UTF_8));
        write(src, "assets/harbor/models/item/b.json", big.getBytes(StandardCharsets.UTF_8));
        write(src, "assets/harbor/models/item/c.json", "{}".getBytes(StandardCharsets.UTF_8));
        return PackBuilder.build(
                        src,
                        List.of(),
                        dir.resolve(name),
                        META,
                        Map.of(),
                        new PackBuilder.Settings(PackOptimizer.Options.NONE, Set.of(), level))
                .zip();
    }

    @Test
    void everyZipLevelReadsBackWithZipFile(@TempDir Path dir) throws IOException {
        Path pedantic = zipPack(dir, "p.zip", ZipConformance.PEDANTIC);
        for (ZipConformance level : ZipConformance.values()) {
            Path zip = zipPack(dir, level.key() + ".zip", level);
            // java.util.zip.ZipFile is what Minecraft uses to read packs
            try (ZipFile expected = new ZipFile(pedantic.toFile());
                    ZipFile actual = new ZipFile(zip.toFile())) {
                assertEquals(expected.size(), actual.size(), level.key());
                for (var e : java.util.Collections.list(expected.entries())) {
                    ZipEntry got = actual.getEntry(e.getName());
                    assertTrue(got != null, level.key() + " " + e.getName());
                    assertArrayEquals(
                            expected.getInputStream(e).readAllBytes(),
                            actual.getInputStream(got).readAllBytes(),
                            level.key() + " " + e.getName());
                }
            }
        }
        // balanced/disregard store the duplicate model once
        assertTrue(
                Files.size(zipPack(dir, "b.zip", ZipConformance.BALANCED)) < Files.size(pedantic));
    }

    @Test
    void disregardHidesNamesFromStreamExtractors(@TempDir Path dir) throws IOException {
        Path zip = zipPack(dir, "d.zip", ZipConformance.DISREGARD);
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry first = in.getNextEntry();
            assertTrue(first != null);
            assertEquals("", first.getName());
        }
    }

    // ------------------------------------------------------------------ Sound lengths

    /** Minimal Ogg stream: an identification header page and a last page with the granule. */
    private static byte[] fakeOgg(int rate, long samples) {
        ByteBuffer b = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OggS".getBytes(StandardCharsets.US_ASCII)).put(new byte[23]);
        b.put((byte) 1).put("vorbis".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0).put((byte) 2).putInt(rate).put(new byte[12]);
        b.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 4);
        b.putLong(samples).put(new byte[19]);
        byte[] out = new byte[b.position()];
        b.flip().get(out);
        return out;
    }

    @Test
    void oggLengthComesFromTheLastGranule() {
        assertEquals(4064, SoundLengths.oggTicks(fakeOgg(44100, 44100L * 203 + 7056)));
        assertEquals(0, SoundLengths.oggTicks(new byte[40]));
    }

    @Test
    void soundEventLengthIsItsLongestFile() {
        Map<String, Integer> files = Map.of("harbor:music/theme", 4064, "harbor:music/b", 100);
        String json =
                """
                {"music.harbor": {"sounds": [{"name": "harbor:music/theme", "stream": true}, "harbor:music/b"]},
                 "ui.click": {"sounds": ["harbor:ui/click"]}}
                """;
        Map<String, Integer> out = new TreeMap<>();
        SoundLengths.events("harbor", json.getBytes(StandardCharsets.UTF_8), files, out);
        assertEquals(Map.of("harbor:music.harbor", 4064), out);
        assertEquals(
                "harbor:music/theme", SoundLengths.fileKey("assets/harbor/sounds/music/theme.ogg"));
    }
}
