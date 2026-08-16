package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.Path;

/**
 * Imports a grayscale heightmap PNG and replaces the synthetic elevation prior
 * in the coarse stage. The heightmap tiles the whole world; all other channels
 * (temperature, precipitation, ...) keep their synthetic values.
 *
 * <p>Loading is lazy and tolerant: a missing/unreadable file logs a warning and
 * falls back to the synthetic prior, so the game never fails to start.
 */
public final class HeightmapConditioner {

    private static final Logger LOG = LoggerFactory.getLogger(HeightmapConditioner.class);

    private static volatile HeightmapConditioner INSTANCE;
    private static volatile boolean INITIALIZED = false;

    private final float[] samples;   // row-major normalised [0,1]
    private final int width, height;
    private final float minElev, maxElev;

    HeightmapConditioner(float[] samples, int width, int height, float minElev, float maxElev) {
        this.samples = samples;
        this.width = width;
        this.height = height;
        this.minElev = minElev;
        this.maxElev = maxElev;
    }

    /** Lazily load the configured heightmap once; returns null when disabled or unreadable. */
    public static HeightmapConditioner get() {
        if (!INITIALIZED) {
            synchronized (HeightmapConditioner.class) {
                if (!INITIALIZED) {
                    INSTANCE = load();
                    INITIALIZED = true;
                }
            }
        }
        return INSTANCE;
    }

    private static HeightmapConditioner load() {
        if (!TerrainDiffusionConfig.conditionalEnabled()) return null;
        String pathStr = TerrainDiffusionConfig.conditionalHeightmapPath().trim();
        if (pathStr.isEmpty()) return null;
        try {
            return fromPng(Path.of(pathStr),
                    TerrainDiffusionConfig.conditionalHeightmapMin(),
                    TerrainDiffusionConfig.conditionalHeightmapMax());
        } catch (Exception e) {
            LOG.warn("Conditional heightmap failed to load, falling back to synthetic: {}", e.toString());
            return null;
        }
    }

    /** Load a grayscale PNG and normalise its full dynamic range to [minElev, maxElev] metres. */
    static HeightmapConditioner fromPng(Path path, float minElev, float maxElev) throws Exception {
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) throw new IllegalArgumentException("unreadable image: " + path);
        int w = img.getWidth(), h = img.getHeight();
        Raster raster = img.getRaster();
        if (raster.getNumBands() < 1) throw new IllegalArgumentException("heightmap has no sample band");
        if (img.getColorModel() != null && img.getColorModel().getNumColorComponents() > 1) {
            LOG.warn("Conditional heightmap {} is a colour image; using the red channel as elevation", path);
        }
        float[] samples = new float[w * h];
        float minSample = Float.MAX_VALUE, maxSample = -Float.MAX_VALUE;
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float v = raster.getSample(c, r, 0);
                samples[r * w + c] = v;
                if (v < minSample) minSample = v;
                if (v > maxSample) maxSample = v;
            }
        }
        if (maxSample <= minSample) throw new IllegalArgumentException("heightmap has no elevation variation");
        float range = maxSample - minSample;
        for (int i = 0; i < samples.length; i++) samples[i] = (samples[i] - minSample) / range;
        return new HeightmapConditioner(samples, w, h, minElev, maxElev);
    }

    /** Overwrite a signed-sqrt elevation channel for the window [x1, x1+W) x [y1, y1+H). */
    void applyElevSqrt(float[][] elevSqrt, int x1, int y1, int W, int H) {
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int hx = Math.floorMod(x1 + c, width);
                int hy = Math.floorMod(y1 + r, height);
                float t = samples[hy * width + hx];
                float metres = minElev + t * (maxElev - minElev);
                elevSqrt[r][c] = (float) (Math.signum(metres) * Math.sqrt(Math.abs(metres)));
            }
        }
    }
}
