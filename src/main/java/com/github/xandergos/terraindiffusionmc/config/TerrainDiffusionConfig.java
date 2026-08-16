package com.github.xandergos.terraindiffusionmc.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class TerrainDiffusionConfig {
    private static final String FILE_NAME = "terrain-diffusion-next.properties";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();
    private static final boolean DEFAULT_OFFLOAD_MODELS = true;
    private static final boolean DEFAULT_VALIDATE_MODEL = true;
    private static final int DEFAULT_EXPLORER_PORT = 19801;
    private static final int DEFAULT_TILE_SIZE = 256;

    static {
        loadDefaults();
        Path configPath = resolveConfigPath();
        if (configPath != null) {
            loadOverrides(configPath);
        }
    }

    private TerrainDiffusionConfig() {
    }

    /** Inference device: "cpu", "gpu", or "auto" (try GPU then fall back to CPU). */
    public static String inferenceDevice() {
        String device = readString("inference.device", "gpu");
        // On the CPU build "gpu" is meaningless (no dedicated GPU provider), so treat it as "auto":
        // tries CoreML on macOS, falls back to CPU elsewhere.
        if ("cpu".equals(BUILD_VARIANT)) {
            return "auto";
        }
        return device;
    }

    /** Whether to offload inactive models from VRAM between pipeline stages. */
    public static boolean offloadModels() {
        return readBoolean("inference.offload_models", DEFAULT_OFFLOAD_MODELS);
    }

    /**
     * Whether to fall back to CPU when inference.device=gpu is requested but no GPU provider
     * (CUDA, DirectML, CoreML) can be loaded. If false, startup fails instead.
     */
    public static boolean fallbackCpu() {
        return readBoolean("inference.fallback_cpu", true);
    }

    /** TCP port for the local terrain explorer HTTP server. */
    public static int explorerPort() {
        return readInt("explorer.port", DEFAULT_EXPLORER_PORT);
    }

    /** Whether to validate SHA-256 for pre-existing local model files before use. */
    public static boolean validateModel() {
        return readBoolean("validate_model", DEFAULT_VALIDATE_MODEL);
    }

    /**
     * Whether to skip the 26.x server startup preload of a large spawn area around the world
     * origin. That preload generates tens of thousands of diffusion tiles (each 6 s on a
     * mid-range GPU) before the server becomes joinable; skipping it makes the server ready
     * in about a minute and generates terrain on demand (the join-time spawn area is only
     * 7x7 chunks, and the player's surroundings generate as they explore).
     */
    public static boolean skipInitialChunkPreload() {
        return readBoolean("worldgen.skip_initial_chunk_preload", true);
    }

    /** Initial coarse-pixel radius for spawn land search (NxN region centered at origin). */
    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size", 16);
    }

    /** Maximum coarse-pixel region size for spawn land search before giving up. */
    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size", 128);
    }

    /**
     * River generation mode: "hybrid" (default) routes D8 flow paths along the real terrain
     * (with a halo window so paths are seamless across tiles) and carves them below sea level;
     * "carver" uses the pure noise zero-contour carver instead.
     */
    public static String riverMode() {
        String mode = readString("rivers.mode", "hybrid");
        if (!"hybrid".equals(mode) && !"carver".equals(mode)) {
            System.err.println("Invalid rivers.mode: " + mode + ", using default 'hybrid'");
            return "hybrid";
        }
        return mode;
    }

    /** Minimum D8 contributing area (in native pixels) for a river path. Lower = more streams. */
    public static float riverFlowThreshold() {
        return readFloat("rivers.flow_threshold", 50f);
    }

    /** Dilation rings added to a carved path for tapered banks (0 = centre-line only). */
    public static int riverCarveWidth() {
        int width = (int) readFloat("rivers.carve_width", 1f);
        return Math.max(0, width);
    }

    /** Region side length in blocks. Must be a positive power of 2 (128, 256, 512, ...). */
    public static int tileSize() {
        int configuredTileSize = readInt("tile_size", DEFAULT_TILE_SIZE);
        if (configuredTileSize <= 0 || !isPowerOfTwo(configuredTileSize)) {
            System.err.println("Invalid tile_size: " + configuredTileSize + ", using default " + DEFAULT_TILE_SIZE);
            return DEFAULT_TILE_SIZE;
        }
        return configuredTileSize;
    }

    /**
     * DPM-Solver++ step count for the coarse elevation stage (the dominant cost of worldgen).
     * Fewer steps generate faster at a slight fidelity cost; DPM-Solver++ 2nd order stays
     * high quality down to ~12 steps. Clamped to [8, 32]. Default 20 matches the reference
     * pipeline; lower it (e.g. 12-16) only if you accept the fidelity tradeoff.
     */
    public static int coarseSteps() {
        int steps = readInt("pipeline.coarse_steps", 20);
        return Math.max(8, Math.min(32, steps));
    }

    /**
     * Whether to carve river channels into the terrain. Default ON: winding channels are carved
     * into lowland, dipping just below sea level so they hold water. Tune {@code rivers.flow_threshold}
     * (raise it for fewer, larger rivers) and {@code rivers.depth} to control how much land they claim.
     */
    public static boolean riversEnabled() {
        return readBoolean("rivers.enabled", true);
    }

    /** River-network noise frequency per metre. Lower spreads rivers further apart. */
    public static float riverFrequency() {
        return readFloat("rivers.frequency", 0.00035f);
    }

    /** Half-width of a river channel in noise units. Larger makes rivers wider. */
    public static float riverWidth() {
        return readFloat("rivers.width", 0.045f);
    }

    /**
     * Depth (metres below sea level) cut at a river's centre-line. The mod compresses below-sea
     * level depth, so ~16 m yields a channel a few blocks deep (deep enough to read as a river).
     */
    public static float riverDepth() {
        return readFloat("rivers.depth", 16f);
    }

    /**
     * Rivers are only carved into terrain below this elevation (metres). Channels reach below sea
     * level so they hold water; capping the altitude keeps them as valleys rather than deep canyons.
     */
    public static float riverMaxAltitude() {
        return readFloat("rivers.max_altitude", 40f);
    }

    /**
     * Comma-separated Hugging Face hosts for model downloads, tried in order until one succeeds.
     * Falls back to mirrors automatically when the official host fails.
     */
    public static String[] downloadMirrors() {
        String configured = readString("download.mirrors", "huggingface.co,hf-mirror.com");
        String[] hosts = configured.split(",");
        List<String> result = new ArrayList<>();
        for (String host : hosts) {
            String trimmed = host.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        if (result.isEmpty()) result.add("huggingface.co");
        return result.toArray(new String[0]);
    }

    /**
     * Comma-separated PyPI hosts for the automatic CUDA 12 runtime library download,
     * tried in order until one succeeds. Used on Linux servers that are missing
     * libcublasLt.so.12 etc. (the ONNX Runtime Java GPU package always links CUDA 12).
     */
    public static String[] cudaMirrors() {
        String configured = readString("download.cuda_mirrors",
                "pypi.org,pypi.tuna.tsinghua.edu.cn,mirrors.aliyun.com,mirrors.cloud.tencent.com,"
                        + "repo.huaweicloud.com,mirrors.ustc.edu.cn,mirrors.bfsu.edu.cn");
        String[] hosts = configured.split(",");
        List<String> result = new ArrayList<>();
        for (String host : hosts) {
            String trimmed = host.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        if (result.isEmpty()) result.add("pypi.org");
        return result.toArray(new String[0]);
    }

    /**
     * Minimum download speed in KB/s. If the download stays below this for 30 seconds,
     * the current mirror source is abandoned and the next one is tried. 0 disables the check.
     */
    public static double minDownloadSpeedKbps() {
        double speed = readDouble("download.min_speed_kbps", 100.0);
        return Math.max(0.0, speed);
    }

    // =========================================================================
    // Terrain shaping
    // =========================================================================

    /** Whether to apply folded-ridge noise (abs(noise)) for mountain skeleton. */
    public static boolean ridgesEnabled() {
        return readBoolean("terrain.ridges.enabled", true);
    }

    /** Ridge noise amplitude in metres at native resolution. */
    public static float ridgeAmplitude() {
        return readFloat("terrain.ridges.amplitude", 80f);
    }

    /** Whether to apply plateau-step mapping for vertical drama. */
    public static boolean plateausEnabled() {
        return readBoolean("terrain.plateau.enabled", true);
    }

    /** Lower bound of the plateau band (metres). */
    public static float plateauMin() {
        return readFloat("terrain.plateau.min", 600f);
    }

    /** Upper bound of the plateau band (metres). */
    public static float plateauMax() {
        return readFloat("terrain.plateau.max", 1800f);
    }

    /** Vertical compression ratio inside the plateau band (0 = full flat, 1 = no change). */
    public static float plateauCompression() {
        return readFloat("terrain.plateau.compression", 0.3f);
    }

    /** Steepness multiplier above the plateau band (>1 = cliff). */
    public static float plateauCliffSteepness() {
        return readFloat("terrain.plateau.cliff_steepness", 2.5f);
    }

    /**
     * Low-frequency noise amplitude (metres) that shifts the plateau band up/down
     * so plateaus only appear on fraction of the terrain.
     */
    public static float plateauVariability() {
        return readFloat("terrain.plateau.variability", 400f);
    }

    // =========================================================================
    // Wonders
    // =========================================================================

    /** Master switch for all wonder generation. */
    public static boolean wondersEnabled() {
        return readBoolean("wonders.enabled", true);
    }

    // =========================================================================
    // Conditional generation (import a user heightmap)
    // =========================================================================

    /** Whether to replace the synthetic elevation prior with an imported heightmap. */
    public static boolean conditionalEnabled() {
        return readBoolean("conditional.enabled", false);
    }

    /** Path to a grayscale heightmap PNG (empty = disabled). Missing channels fall back to synthetic noise. */
    public static String conditionalHeightmapPath() {
        return readStringRaw("conditional.heightmap_path", "");
    }

    /** Elevation (metres) mapped to the heightmap's darkest pixel. */
    public static float conditionalHeightmapMin() {
        return readFloat("conditional.heightmap_min", -100f);
    }

    /** Elevation (metres) mapped to the heightmap's brightest pixel. */
    public static float conditionalHeightmapMax() {
        return readFloat("conditional.heightmap_max", 4000f);
    }

    // =========================================================================
    // Biome tweaks
    // =========================================================================

    private static volatile Float BIOME_NOISE_STRENGTH_OVERRIDE = null;

    /** Multiplier for fine climate noise amplitude in BiomeClassifier (0 = off, 1 = default). */
    public static float biomeNoiseStrength() {
        Float override = BIOME_NOISE_STRENGTH_OVERRIDE;
        if (override != null) return override;
        return readFloat("biome.noise_strength", 1f);
    }

    /** Explorer A/B: override biome.noise_strength for the current session (null restores config value). */
    public static void setBiomeNoiseStrengthOverride(Float value) {
        BIOME_NOISE_STRENGTH_OVERRIDE = value;
    }

    /** Whether to majority-filter isolated single-pixel biome speckles in BiomeClassifier output. */
    public static boolean biomeSmoothingEnabled() {
        return readBoolean("biome.smoothing_enabled", true);
    }

    private static void loadDefaults() {
        boolean loadedFromResource = false;
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                PROPERTIES.load(in);
                loadedFromResource = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config from resource: " + e.getMessage());
        }

        if (!loadedFromResource) {
            PROPERTIES.setProperty("inference.device", "gpu");
            PROPERTIES.setProperty("validate_model", String.valueOf(DEFAULT_VALIDATE_MODEL));
            PROPERTIES.setProperty("tile_size", String.valueOf(DEFAULT_TILE_SIZE));
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim().toLowerCase() : defaultValue;
    }

    /** Like {@link #readString} but preserves case (for filesystem paths). */
    private static String readStringRaw(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim() : defaultValue;
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException e) {
            System.err.println("Fabric Loader config directory unavailable: " + e.getMessage());
            return null;
        }
    }

    private static void loadOverrides(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    Properties overrides = new Properties();
                    overrides.load(in);
                    PROPERTIES.putAll(overrides);
                }
            } else {
                writeConfig(configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
        }
    }

    private static void writeConfig(Path configPath) {
        try (InputStream defaultConfigInputStream = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaultConfigInputStream != null) {
                Files.copy(defaultConfigInputStream, configPath);
                return;
            }
            System.err.println("Default config resource not found: " + RESOURCE_PATH);
        } catch (IOException e) {
            System.err.println("Failed to copy default config resource: " + e.getMessage());
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static double readDouble(String key, double defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid double for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static float readFloat(String key, float defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid float for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static int readInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    private static String readBuildVariant() {
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

}
