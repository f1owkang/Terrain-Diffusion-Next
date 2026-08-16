package com.github.xandergos.terraindiffusionmc.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.nio.file.Files;
import java.nio.file.Path;

class HeightmapConditionerTest {

    private Path writeGrayPng(int w, int h, int[] samples) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster raster = img.getRaster();
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++)
                raster.setSample(c, r, 0, samples[r * w + c]);
        Path p = Files.createTempFile("td-heightmap", ".png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void samplesAndSignedSqrt() throws Exception {
        // 4x4 ramp: value = index * 4096, so t = index/15 after normalisation
        int[] raw = new int[16];
        for (int i = 0; i < 16; i++) raw[i] = i * 4096;
        Path p = writeGrayPng(4, 4, raw);

        HeightmapConditioner cond = HeightmapConditioner.fromPng(p, 0f, 1500f);

        // Window (0,0,2,2): sample top-left 2x2 of the heightmap
        float[][] elev = new float[2][2];
        cond.applyElevSqrt(elev, 0, 0, 2, 2);
        assertEquals(0f, elev[0][0], 1e-4f);            // t=0 -> 0 m
        assertEquals((float) Math.sqrt(500f), elev[1][1], 1e-3f); // index 5 -> t=5/15 -> 500 m

        // Negative minimum maps black below sea level (signed-sqrt)
        HeightmapConditioner cond2 = HeightmapConditioner.fromPng(p, -100f, 4000f);
        float[][] elev2 = new float[1][1];
        cond2.applyElevSqrt(elev2, 0, 0, 1, 1);
        assertEquals(-10f, elev2[0][0], 1e-4f);          // t=0 -> -100 m -> -sqrt(100)

        Files.deleteIfExists(p);
    }

    @Test
    void tilesWholeWorld() throws Exception {
        int[] raw = new int[16];
        for (int i = 0; i < 16; i++) raw[i] = i * 4096;
        Path p = writeGrayPng(4, 4, raw);

        HeightmapConditioner cond = HeightmapConditioner.fromPng(p, 0f, 1500f);

        // Window starting at x1=4 (beyond width): wraps to column 0
        float[][] elev = new float[1][1];
        cond.applyElevSqrt(elev, 4, 0, 1, 1);
        assertEquals(0f, elev[0][0], 1e-4f);            // floorMod(4,4)=0 -> t=0

        // y wraps too
        cond.applyElevSqrt(elev, 0, 4, 1, 1);
        assertEquals(0f, elev[0][0], 1e-4f);            // floorMod(4,4)=0 -> t=0

        Files.deleteIfExists(p);
    }

    @Test
    void normalisesToFullDynamicRange() throws Exception {
        // Min sample is 100, not 0: darkest pixel must still map to heightmap_min.
        int[] raw = {100, 200, 300, 400};
        Path p = writeGrayPng(2, 2, raw);

        HeightmapConditioner cond = HeightmapConditioner.fromPng(p, 0f, 1500f);
        float[][] elev = new float[2][2];
        cond.applyElevSqrt(elev, 0, 0, 2, 2);
        assertEquals(0f, elev[0][0], 1e-4f);                  // v=100 -> t=0 -> 0 m
        assertEquals((float) Math.sqrt(1500f), elev[1][1], 1e-3f); // v=400 -> t=1 -> 1500 m

        Files.deleteIfExists(p);
    }

    @Test
    void constantImageIsRejected() throws Exception {
        int[] raw = {200, 200, 200, 200};
        Path p = writeGrayPng(2, 2, raw);

        assertThrows(IllegalArgumentException.class, () -> HeightmapConditioner.fromPng(p, 0f, 1500f));

        Files.deleteIfExists(p);
    }
}
