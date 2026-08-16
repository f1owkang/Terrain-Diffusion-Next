package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

public final class WonderGenerator {

    private static final int SPIRE   = 0;
    private static final int CALDERA = 1;
    private static final int MESA    = 2;
    private static final int PILLARS = 3;
    private static final int GORGE   = 4;

    private static final float[] GATE_FREQ = { 1f/800f, 1f/1200f, 1f/1000f, 1f/500f, 1f/900f };
    private static final float[] THRESHOLD = { 0.97f, 0.98f, 0.985f, 0.96f, 0.985f };
    private static final float[] KERNEL_R  = { 120f, 250f, 300f, 100f, 80f };
    private static final float   PILLAR_COUNT = 5;
    private static final float   GORGE_LENGTH = 300f;

    private static final short[][] BIOME_GATES = {
        { BiomeClassifier.TAIGA, BiomeClassifier.SNOWY_TAIGA, BiomeClassifier.TAIGA_SPARSE,
          BiomeClassifier.SNOWY_TAIGA_SPARSE, BiomeClassifier.STONY_PEAKS, BiomeClassifier.FROZEN_PEAKS },
        { BiomeClassifier.BADLANDS, BiomeClassifier.DESERT },
        { BiomeClassifier.SAVANNA, BiomeClassifier.PLAINS, BiomeClassifier.BADLANDS },
        { BiomeClassifier.DESERT, BiomeClassifier.BADLANDS },
        { BiomeClassifier.BADLANDS, BiomeClassifier.SAVANNA }
    };

    private static final int[] NOISE_SEEDS = { 11111, 22222, 33333, 44444, 55555 };

    private WonderGenerator() {}

    /** Applies all wonder types, returning a mask of placed wonder centre pixels (or null when disabled). */
    public static boolean[] apply(float[] elev, short[] biomes, int i0, int j0,
                              int H, int W, float pixelSizeM, long seed) {
        if (!TerrainDiffusionConfig.wondersEnabled()) return null;

        boolean[] mask = new boolean[H * W];
        for (int type = 0; type < 5; type++) {
            applyType(elev, biomes, i0, j0, H, W, pixelSizeM, seed, type, mask);
        }
        return mask;
    }

    private static void applyType(float[] elev, short[] biomes, int i0, int j0,
                                   int H, int W, float pixelSizeM, long seed, int type, boolean[] mask) {
        boolean needsRidge = (type == GORGE);
        boolean[] ridgeMask = null;
        if (needsRidge) ridgeMask = computeRidgeMask(elev, H, W);

        int noiseSeed = (int)(seed ^ (0x7FFFFFFFL * (type + 1)));
        FastNoiseLite gateNoise = makeGateNoise(noiseSeed, GATE_FREQ[type]);
        FastNoiseLite shapeNoise = new FastNoiseLite(noiseSeed ^ 0x55555555);
        shapeNoise.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        shapeNoise.SetFrequency(0.05f);

        int stepPx = Math.max(4, (int)(8f * 90f / pixelSizeM));
        float threshold = THRESHOLD[type];

        for (int r = 0; r < H; r += stepPx) {
            for (int c = 0; c < W; c += stepPx) {
                float wx = (j0 + c) * pixelSizeM;
                float wy = (i0 + r) * pixelSizeM;
                if (gateNoise.GetNoise(wx, wy) < threshold) continue;

                int idx = r * W + c;
                if (elev[idx] < 0f) continue;
                if (!biomeGate(biomes[idx], type)) continue;
                if (needsRidge && !ridgeMask[idx]) continue;

                mask[idx] = true;
                applyKernel(elev, biomes, r, c, H, W, type, shapeNoise, pixelSizeM);
            }
        }
    }

    private static FastNoiseLite makeGateNoise(int seed, float freq) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(2);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private static boolean biomeGate(short biome, int type) {
        for (short b : BIOME_GATES[type]) {
            if (b == biome) return true;
        }
        return false;
    }

    private static void applyKernel(float[] elev, short[] biomes, int cr, int cc,
                                     int H, int W, int type, FastNoiseLite shapeNoise, float pixelSizeM) {
        float baseR = KERNEL_R[type] / pixelSizeM;
        int rPx = Math.max(1, Math.round(baseR));
        int r0 = Math.max(0, cr - rPx);
        int r1 = Math.min(H - 1, cr + rPx);
        int c0 = Math.max(0, cc - rPx);
        int c1 = Math.min(W - 1, cc + rPx);

        switch (type) {
            case SPIRE: {
                float h = 60f + shapeNoise.GetNoise(cr, cc) * 40f;
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        if (elev[r * W + c] < 0f) continue;
                        elev[r * W + c] += coneKernel(r, c, cr, cc, baseR, h);
                    }
                }
                break;
            }
            case CALDERA: {
                float depth = 25f + shapeNoise.GetNoise(cr, cc) * 15f;
                float lipH  = 15f + Math.abs(shapeNoise.GetNoise(cr + 100, cc + 100)) * 20f;
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        if (elev[r * W + c] < 0f) continue;
                        elev[r * W + c] += ringKernel(r, c, cr, cc, baseR, depth, lipH);
                    }
                }
                break;
            }
            case MESA: {
                float h = 40f + Math.abs(shapeNoise.GetNoise(cr, cc)) * 60f;
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        if (elev[r * W + c] < 0f) continue;
                        elev[r * W + c] += mesaKernel(r, c, cr, cc, baseR, h);
                    }
                }
                break;
            }
            case PILLARS: {
                for (int p = 0; p < PILLAR_COUNT; p++) {
                    float ox = (shapeNoise.GetNoise(cr + p * 7, cc) * 0.5f) * baseR;
                    float oy = (shapeNoise.GetNoise(cr, cr + p * 7) * 0.5f) * baseR;
                    int pc = Math.round(cc + ox);
                    int pr = Math.round(cr + oy);
                    float ph = 15f + Math.abs(shapeNoise.GetNoise(pr, pc)) * 25f;
                    float prR = 3f + Math.abs(shapeNoise.GetNoise(pr + 10, pc + 10)) * 5f;
                    int ppr0 = Math.max(0, pr - (int)prR);
                    int ppr1 = Math.min(H - 1, pr + (int)prR);
                    int ppc0 = Math.max(0, pc - (int)prR);
                    int ppc1 = Math.min(W - 1, pc + (int)prR);
                    for (int r = ppr0; r <= ppr1; r++) {
                        for (int c = ppc0; c <= ppc1; c++) {
                            if (elev[r * W + c] < 0f) continue;
                            elev[r * W + c] += coneKernel(r, c, pr, pc, prR, ph);
                        }
                    }
                }
                break;
            }
            case GORGE: {
                float depth = 30f + Math.abs(shapeNoise.GetNoise(cr, cc)) * 30f;
                float dirX = shapeNoise.GetNoise(cr, cc + 50);
                float dirY = shapeNoise.GetNoise(cr + 50, cc);
                float len = (float)Math.sqrt(dirX * dirX + dirY * dirY);
                if (len < 1e-6f) len = 1f;
                dirX /= len; dirY /= len;
                float halfLenPx = GORGE_LENGTH / 2f / pixelSizeM;
                float widthPx = 5f;
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        if (elev[r * W + c] < 0f) continue;
                        float dx = c - cc, dy = r - cr;
                        float along = dx * dirX + dy * dirY;
                        float cross = dx * dirY - dy * dirX;
                        if (Math.abs(along) > halfLenPx) continue;
                        float t = 1f - Math.abs(cross) / widthPx;
                        if (t <= 0f) continue;
                        elev[r * W + c] -= depth * t * t * (1f - Math.abs(along) / halfLenPx);
                    }
                }
                break;
            }
        }
    }

    static float coneKernel(int r, int c, int cr, int cc, float radius, float height) {
        float dist = (float)Math.sqrt((c - cc) * (c - cc) + (r - cr) * (r - cr));
        if (dist >= radius) return 0f;
        float t = 1f - dist / radius;
        return height * t * t;
    }

    static float ringKernel(int r, int c, int cr, int cc, float outerR, float depth, float lipH) {
        float dist = (float)Math.sqrt((c - cc) * (c - cc) + (r - cr) * (r - cr));
        if (dist >= outerR) return 0f;
        float innerR = outerR * 0.55f;
        if (dist < innerR) {
            float t = 1f - dist / innerR;
            return -depth * t;
        }
        float t = (dist - innerR) / (outerR - innerR);
        return lipH * (1f - t) * (1f - t);
    }

    static float mesaKernel(int r, int c, int cr, int cc, float radius, float height) {
        float dist = (float)Math.sqrt((c - cc) * (c - cc) + (r - cr) * (r - cr));
        if (dist < radius) return height;
        float t = (dist - radius) / (radius * 0.25f);
        if (t >= 1f) return 0f;
        return height * (1f - t * t);
    }

    static boolean[] computeRidgeMask(float[] elev, int H, int W) {
        boolean[] mask = new boolean[H * W];
        for (int r = 1; r < H - 1; r++) {
            for (int c = 1; c < W - 1; c++) {
                int idx = r * W + c;
                if (elev[idx] < 0f) continue;
                float e = elev[idx];
                float eN = elev[(r - 1) * W + c];
                float eS = elev[(r + 1) * W + c];
                float eW = elev[r * W + c - 1];
                float eE = elev[r * W + c + 1];
                float neigh = (eN + eS + eW + eE) / 4f;
                float dx = (eE - eW) / 2f;
                float dy = (eS - eN) / 2f;
                float slope = (float)Math.sqrt(dx * dx + dy * dy);
                mask[idx] = e > neigh && slope > 0.3f;
            }
        }
        return mask;
    }
}
