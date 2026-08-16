package com.github.xandergos.terraindiffusionmc.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeClassifierTest {

    @Test
    void oceanBiomes() {
        int H = 1, W = 4;
        float pixelSizeM = 90f;
        // Ocean (elev < 0) at different temperatures
        float[] elev = {-10, -10, -10, -10};
        float[] elevPadded = {-10,-10,-10,-10,-10,-10, -10,-10,-10,-10,-10,-10, -10,-10,-10,-10,-10,-10};
        // Climate: [temp, t_season, precip, p_cv] x W
        // 4 ocean: frozen (-10°C), cold (0°C), temperate (15°C), warm (30°C)
        float[] climate = {
            -10, 0, 15, 30,        // temp
            200, 200, 200, 200,    // t_season
            500, 500, 500, 500,    // precip
            50, 50, 50, 50         // p_cv
        };

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM);

        assertEquals(BiomeClassifier.FROZEN_OCEAN, biomes[0]);
        assertEquals(BiomeClassifier.COLD_OCEAN,   biomes[1]);
        assertEquals(BiomeClassifier.OCEAN,         biomes[2]);
        assertEquals(BiomeClassifier.WARM_OCEAN,    biomes[3]);
    }

    @Test
    void desertBiome() {
        int H = 1, W = 1;
        float pixelSizeM = 90f;
        float[] elev = {50};
        float[] elevPadded = {50,50,50, 50,50,50, 50,50,50};
        // Hot (30°C), low precip → desert
        float[] climate = {30, 100, 50, 20};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM);
        assertEquals(BiomeClassifier.DESERT, biomes[0]);
    }

    @Test
    void landBiomeNotOcean() {
        int H = 1, W = 1;
        float pixelSizeM = 90f;
        float[] elev = {40};
        float[] elevPadded = {40,40,40, 40,40,40, 40,40,40};
        // Cool, moderate climate — should be some land biome (not ocean)
        float[] climate = {8, 200, 300, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM);
        // Not ocean (41, 44, 46, 48 are ocean biome IDs)
        short b = biomes[0];
        assertNotEquals(BiomeClassifier.FROZEN_OCEAN, b);
        assertNotEquals(BiomeClassifier.COLD_OCEAN, b);
        assertNotEquals(BiomeClassifier.OCEAN, b);
        assertNotEquals(BiomeClassifier.WARM_OCEAN, b);
    }

    @Test
    void nullClimateReturnsPlains() {
        int H = 2, W = 2;
        float[] elev = {10, 20, 30, 40};
        float[] elevPadded = new float[16];
        System.arraycopy(elev, 0, elevPadded, (1 * 4 + 1), 4);

        short[] biomes = BiomeClassifier.classify(elev, null, 0, 0, elevPadded, H, W, 90f);
        for (short b : biomes) assertEquals(BiomeClassifier.PLAINS, b);
    }

    @Test
    void riverMaskOverridesBiome() {
        int H = 1, W = 1;
        float pixelSizeM = 90f;
        float[] elev = {30};
        float[] elevPadded = {30,30,30, 30,30,30, 30,30,30};
        float[] climate = {15, 100, 600, 30};
        boolean[] riverMask = {true};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM, riverMask);
        assertEquals(BiomeClassifier.RIVER, biomes[0]);
    }

    @Test
    void badlandsInHotAridUpland() {
        float pixelSizeM = 90f;
        // Upland (300m), hot (30°C), arid -> badlands
        float[] elevUp = {300};
        float[] elevPaddedUp = {300,300,300, 300,300,300, 300,300,300};
        float[] climateUp = {30, 100, 50, 20};
        short[] up = BiomeClassifier.classify(elevUp, climateUp, 0, 0, elevPaddedUp, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.BADLANDS, up[0]);

        // Lowland (50m), hot (30°C), arid -> desert
        float[] elevLow = {50};
        float[] elevPaddedLow = {50,50,50, 50,50,50, 50,50,50};
        float[] climateLow = {30, 100, 50, 20};
        short[] low = BiomeClassifier.classify(elevLow, climateLow, 0, 0, elevPaddedLow, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.DESERT, low[0]);
    }

    @Test
    void smoothingRemovesIsolatedSpeckle() {
        int H = 3, W = 3;
        float pixelSizeM = 90f;
        float[] elev = new float[9];
        java.util.Arrays.fill(elev, 50f);
        float[] elevPadded = new float[25];
        java.util.Arrays.fill(elevPadded, 50f);
        // Center: hot & arid (desert). 8 neighbours: temperate & moist (forest).
        float[] temp    = {15,15,15, 15,30,15, 15,15,15};
        float[] season  = {200,200,200, 200,100,200, 200,200,200};
        float[] precip  = {800,800,800, 800,50,800, 800,800,800};
        float[] pCV     = {30,30,30, 30,20,30, 30,30,30};
        float[] climate = new float[4 * 9];
        System.arraycopy(temp, 0, climate, 0, 9);
        System.arraycopy(season, 0, climate, 9, 9);
        System.arraycopy(precip, 0, climate, 18, 9);
        System.arraycopy(pCV, 0, climate, 27, 9);

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM);
        assertEquals(BiomeClassifier.DARK_FOREST, biomes[4]);
    }

    @Test
    void smoothingPreservesRiver() {
        int H = 3, W = 3;
        float pixelSizeM = 90f;
        float[] elev = new float[9];
        java.util.Arrays.fill(elev, 50f);
        float[] elevPadded = new float[25];
        java.util.Arrays.fill(elevPadded, 50f);
        boolean[] riverMask = new boolean[9];
        riverMask[4] = true;
        float[] temp    = {15,15,15, 15,15,15, 15,15,15};
        float[] season  = {200,200,200, 200,200,200, 200,200,200};
        float[] precip  = {800,800,800, 800,800,800, 800,800,800};
        float[] pCV     = {30,30,30, 30,30,30, 30,30,30};
        float[] climate = new float[4 * 9];
        System.arraycopy(temp, 0, climate, 0, 9);
        System.arraycopy(season, 0, climate, 9, 9);
        System.arraycopy(precip, 0, climate, 18, 9);
        System.arraycopy(pCV, 0, climate, 27, 9);

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM, riverMask);
        assertEquals(BiomeClassifier.RIVER, biomes[4]);
        assertEquals(BiomeClassifier.DARK_FOREST, biomes[0]);
    }

    @Test
    void smoothingPreservesOcean() {
        int H = 3, W = 3;
        float pixelSizeM = 90f;
        float[] elev = new float[9];
        java.util.Arrays.fill(elev, 50f);
        elev[4] = -10f;
        float[] elevPadded = new float[25];
        java.util.Arrays.fill(elevPadded, 50f);
        elevPadded[12] = -10f;
        float[] temp    = {15,15,15, 15,15,15, 15,15,15};
        float[] season  = {200,200,200, 200,200,200, 200,200,200};
        float[] precip  = {800,800,800, 800,800,800, 800,800,800};
        float[] pCV     = {30,30,30, 30,30,30, 30,30,30};
        float[] climate = new float[4 * 9];
        System.arraycopy(temp, 0, climate, 0, 9);
        System.arraycopy(season, 0, climate, 9, 9);
        System.arraycopy(precip, 0, climate, 18, 9);
        System.arraycopy(pCV, 0, climate, 27, 9);

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, H, W, pixelSizeM);
        assertEquals(BiomeClassifier.OCEAN, biomes[4]);
        // Land neighbours must stay land (coast preserved).
        assertNotEquals(BiomeClassifier.OCEAN, biomes[0]);
        assertNotEquals(BiomeClassifier.WARM_OCEAN, biomes[0]);
        assertNotEquals(BiomeClassifier.COLD_OCEAN, biomes[0]);
        assertNotEquals(BiomeClassifier.FROZEN_OCEAN, biomes[0]);
    }

    @Test
    void meadowInSemiAridTemperateUpland() {
        float pixelSizeM = 90f;
        // Upland (300m), temperate (15°C), semi-arid -> meadow (not grove/plains).
        float[] elev = {300};
        float[] elevPadded = {300,300,300, 300,300,300, 300,300,300};
        float[] climate = {15, 100, 100, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.MEADOW, biomes[0]);
    }

    @Test
    void savannaKeptOnMediumSlope() {
        float pixelSizeM = 100f;
        // Warm (22°C) + sparse trees on a medium slope must stay savanna,
        // not fall through to a cold biome (regression for the !slopeMedium guard).
        // Sobel gradient 65 m/pixel -> slope 0.65 (medium band).
        float[] elev = {65};
        float[] elevPadded = {0,65,130, 0,65,130, 0,65,130};
        float[] climate = {22, 100, 566, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.SAVANNA, biomes[0]);
    }

    @Test
    void savannaPlateauInWarmUpland() {
        float pixelSizeM = 90f;
        // Warm (22°C) + sparse trees on upland (300m) -> savanna plateau.
        float[] elev = {300};
        float[] elevPadded = {300,300,300, 300,300,300, 300,300,300};
        float[] climate = {22, 100, 566, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.SAVANNA_PLATEAU, biomes[0]);
    }

    @Test
    void mangroveSwampInHotWetLowland() {
        float pixelSizeM = 90f;
        // Hot (30°C) + very wet lowland (50m) -> mangrove swamp (dense or rainforest band).
        float[] elev = {50};
        float[] elevPadded = {50,50,50, 50,50,50, 50,50,50};
        float[] climate = {30, 100, 2200, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.MANGROVE_SWAMP, biomes[0]);
    }

    @Test
    void birchForestInTemperate() {
        float pixelSizeM = 90f;
        // Temperate (15°C) + moderate moisture (forest band) -> birch forest.
        float[] elev = {50};
        float[] elevPadded = {50,50,50, 50,50,50, 50,50,50};
        float[] climate = {15, 100, 634, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.BIRCH_FOREST, biomes[0]);
    }

    @Test
    void darkForestInTemperateLowland() {
        float pixelSizeM = 90f;
        // Temperate (15°C) + dense moisture on lowland (50m) -> dark forest.
        float[] elev = {50};
        float[] elevPadded = {50,50,50, 50,50,50, 50,50,50};
        float[] climate = {15, 100, 1015, 50};

        short[] biomes = BiomeClassifier.classify(elev, climate, 0, 0, elevPadded, 1, 1, pixelSizeM);
        assertEquals(BiomeClassifier.DARK_FOREST, biomes[0]);
    }
}
