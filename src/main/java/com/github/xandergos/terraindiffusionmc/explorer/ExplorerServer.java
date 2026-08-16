package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.SpawnSelector;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded terrain explorer HTTP server. Java port of
 * terrain_diffusion/inference/explorer/server.py.
 *
 * <p>Bound to 127.0.0.1 only. All pipeline calls are routed through
 * LocalTerrainProvider's inference thread for thread safety.
 */
public final class ExplorerServer {

    private static final Logger LOG = LoggerFactory.getLogger(ExplorerServer.class);
    private static final Gson GSON = new Gson();

    private static final String[] CHANNEL_NAMES = {"Elev", "p5", "Temp", "T std", "Precip", "Precip CV"};
    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static volatile HttpServer SERVER;
    private static volatile int SERVER_PORT = -1;

    private ExplorerServer() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Start the server if not already running. Returns the port.
     */
    public static synchronized int startIfNotRunning() throws IOException {
        if (SERVER != null) return SERVER_PORT;
        int port = TerrainDiffusionConfig.explorerPort();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", ExplorerServer::handleRoot);
        server.createContext("/api/status", ExplorerServer::handleStatus);
        server.createContext("/api/seed", ExplorerServer::handleSeed);
        server.createContext("/api/spawn", ExplorerServer::handleSpawn);
        server.createContext("/api/coarse.png", ExplorerServer::handleCoarsePng);
        server.createContext("/api/coarse_data.json", ExplorerServer::handleCoarseData);
        server.createContext("/api/coarse_stats", ExplorerServer::handleCoarseStats);
        server.createContext("/api/detail.png", ExplorerServer::handleDetailPng);
        server.createContext("/api/detail_raw", ExplorerServer::handleDetailRaw);
        server.createContext("/api/config", ExplorerServer::handleConfig);
        // Single-thread executor matches Python's threaded=False
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terrain-explorer-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SERVER = server;
        SERVER_PORT = port;
        LOG.info("Terrain explorer started at http://127.0.0.1:{}", port);
        return port;
    }

    public static synchronized void stop() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
            SERVER_PORT = -1;
            LOG.info("Terrain explorer stopped.");
        }
    }

    public static boolean isRunning() {
        return SERVER != null;
    }

    public static int getPort() {
        return SERVER_PORT;
    }

    // =========================================================================
    // Handlers — direct port of server.py routes
    // =========================================================================

    /** GET / → serve index.html */
    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try (InputStream in = ExplorerServer.class.getResourceAsStream(
                "/assets/terrain-diffusion-mc/explorer/index.html")) {
            if (in == null) {
                sendError(ex, 404, "index.html not found");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        } finally {
            ex.close();
        }
    }

    /** GET /api/status → {seed, channels, native_resolution, scale} */
    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            resp.put("channels", Arrays.asList(CHANNEL_NAMES));
            resp.put("native_resolution", NATIVE_RESOLUTION);
            resp.put("scale", WorldScaleManager.getCurrentScale());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/seed body={seed:int-or-string} → {seed} */
    private static void handleSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            String body = readBody(ex, 1024);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = GSON.fromJson(body, Map.class);
            if (!data.containsKey("seed")) { sendError(ex, 400, "seed required"); return; }
            Object seedVal = data.get("seed");
            long newSeed;
            if (seedVal instanceof Number) {
                newSeed = ((Number) seedVal).longValue();
            } else {
                newSeed = Long.parseLong(String.valueOf(seedVal).trim());
            }
            LocalTerrainProvider.changeSeedFromExplorer(newSeed);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** GET /api/spawn → {x, y, z} (current world spawn; cached, recomputed on seed change). */
    private static void handleSpawn(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            net.minecraft.core.BlockPos spawn = SpawnSelector.getSpawnBlockPos();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("x", spawn.getX());
            resp.put("y", spawn.getY());
            resp.put("z", spawn.getZ());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/config body={biome_noise_strength:number|null} → runtime A/B override for the session. */
    private static void handleConfig(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            String body = readBody(ex, 1024);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = GSON.fromJson(body, Map.class);
            if (data.containsKey("biome_noise_strength")) {
                Object v = data.get("biome_noise_strength");
                Float ns = null;
                if (v != null) {
                    if (!(v instanceof Number)) throw new IllegalArgumentException("biome_noise_strength must be a number");
                    float f = ((Number) v).floatValue();
                    if (!Float.isFinite(f)) throw new IllegalArgumentException("biome_noise_strength must be finite");
                    ns = Math.max(0f, Math.min(4f, f));
                }
                TerrainDiffusionConfig.setBiomeNoiseStrengthOverride(ns);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("biome_noise_strength", TerrainDiffusionConfig.biomeNoiseStrength());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * GET /api/coarse.png — port of coarse_png() + _coarse_channel().
     * Query params: channel, ci0, ci1, cj0, cj1, ch{0,2,3,4,5}_min/max
     * Response headers: X-Vmin, X-Vmax
     */
    private static void handleCoarsePng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int channel = getInt(q, "channel", 0);
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            float[] data = coarseChannel(ci0, ci1, cj0, cj1, channel);
            int H = ci1 - ci0, W = cj1 - cj0;

            // Precipitation: log1p(max(v,0)) before normalizing (matches Python)
            float[] display = data.clone();
            if (channel == 4) {
                for (int i = 0; i < display.length; i++)
                    display[i] = (float) Math.log1p(Math.max(0f, display[i]));
            }
            float vmin = nanMin(display), vmax = nanMax(display);
            if (vmax == vmin) vmax = vmin + 1f;

            // Viridis colormap
            float[][] rgba = new float[4][H * W];
            for (int i = 0; i < H * W; i++) {
                float t = (display[i] - vmin) / (vmax - vmin);
                float[] rgb = Colormaps.viridis(clamp01(t));
                rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
            }

            // Optional filter: dim non-matching pixels to 30% (matches Python rgba[~mask, :3] *= 0.3)
            int[] filterChs = {0, 2, 3, 4, 5};
            boolean filterActive = false;
            for (int ch : filterChs) {
                if (q.containsKey("ch" + ch + "_min") || q.containsKey("ch" + ch + "_max")) {
                    filterActive = true; break;
                }
            }
            if (filterActive) {
                boolean[] mask = new boolean[H * W];
                Arrays.fill(mask, true);
                for (int ch : filterChs) {
                    Float lo = getFloat(q, "ch" + ch + "_min");
                    Float hi = getFloat(q, "ch" + ch + "_max");
                    if (lo == null && hi == null) continue;
                    float[] chData = coarseChannel(ci0, ci1, cj0, cj1, ch);
                    for (int i = 0; i < H * W; i++) {
                        if (lo != null && chData[i] < lo) mask[i] = false;
                        if (hi != null && chData[i] > hi) mask[i] = false;
                    }
                }
                for (int i = 0; i < H * W; i++) {
                    if (!mask[i]) {
                        rgba[0][i] *= 0.3f; rgba[1][i] *= 0.3f; rgba[2][i] *= 0.3f;
                    }
                }
            }

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Vmin", String.format("%.3f", vmin));
            ex.getResponseHeaders().set("X-Vmax", String.format("%.3f", vmax));
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Vmin, X-Vmax");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("coarse.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/coarse_data.json — port of coarse_data().
     * Returns all 6 channel values as 2D arrays for client-side hover.
     */
    private static void handleCoarseData(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);
            int H = ci1 - ci0, W = cj1 - cj0;

            Map<String, Object> channels = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] flat = coarseChannel(ci0, ci1, cj0, cj1, ch);
                channels.put(CHANNEL_NAMES[ch], roundedGrid(flat, H, W, 2));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ci0", ci0); resp.put("ci1", ci1);
            resp.put("cj0", cj0); resp.put("cj1", cj1);
            resp.put("channels", channels);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** GET /api/coarse_stats — port of coarse_stats(). */
    private static void handleCoarseStats(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            Map<String, Object> stats = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] data = coarseChannel(ci0, ci1, cj0, cj1, ch);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", CHANNEL_NAMES[ch]);
                entry.put("min", round3(nanMin(data)));
                entry.put("max", round3(nanMax(data)));
                stats.put(String.valueOf(ch), entry);
            }
            sendJson(ex, 200, stats);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * GET /api/detail.png — port of detail_png().
     * Query params: ci, cj, detail_size, pan_i, pan_j, mode
     */
    private static void handleDetailPng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);
            String mode    = q.getOrDefault("mode", "relief");

            int centerI = ci * 256 + panI;
            int centerJ = cj * 256 + panJ;
            int half    = detailSize / 2;

            // Climate is needed for temperature/biomes rendering and for the wonders
            // overlay (wonder placement is biome-gated), so fetch it whenever requested.
            boolean wantClimate = mode.equals("temperature") || mode.equals("biomes") || "1".equals(q.get("wonders"));
            LocalTerrainProvider.PipelineData pd = LocalTerrainProvider.getPipelineData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half, wantClimate);
            float[] elevFlat  = pd.elev;
            float[] climate   = pd.climate;
            int H = detailSize, W = detailSize;

            float[][] rgba;
            if (mode.equals("elevation")) {
                float vmin = nanMin(elevFlat), vmax = nanMax(elevFlat);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(elevFlat, H, W, vmin, vmax, "terrain");
            } else if (mode.equals("temperature") && climate != null) {
                // climate[0] = temperature channel (H*W floats)
                float[] temp = Arrays.copyOfRange(climate, 0, H * W);
                float vmin = nanMin(temp), vmax = nanMax(temp);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(temp, H, W, vmin, vmax, "rdbu_r");
            } else if (mode.equals("biomes") && pd.biomeIds != null) {
                rgba = applyBiomeColormap(pd.biomeIds, H, W);
            } else {
                // relief mode (default)
                float[][] reliefRgb = ReliefMap.getReliefMap(elevFlat, H, W, 90.0);
                rgba = new float[4][H * W];
                for (int i = 0; i < H * W; i++) {
                    rgba[0][i] = reliefRgb[0][i];
                    rgba[1][i] = reliefRgb[1][i];
                    rgba[2][i] = reliefRgb[2][i];
                    rgba[3][i] = 1f;
                }
            }

            applyOverlays(rgba, pd, H, W, q);

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("detail.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/detail_raw — port of detail_raw().
     * Binary: int16-LE elevation (H*W*2 bytes) + float32-LE temperature (H*W*4 bytes).
     * Headers: X-Height, X-Width, X-Has-Temp.
     */
    private static void handleDetailRaw(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);

            int centerI = ci * 256 + panI;
            int centerJ = cj * 256 + panJ;
            int half    = detailSize / 2;
            int H = detailSize, W = detailSize;

            LocalTerrainProvider.PipelineData pd = LocalTerrainProvider.getPipelineData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half, true);
            float[] elevFlat = pd.elev;
            float[] climate  = pd.climate;

            // Elevation → int16 LE (matching Python: clip(floor(elev), -32768, 32767).astype('<i2'))
            ByteBuffer elevBuf = ByteBuffer.allocate(H * W * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (float e : elevFlat) {
                short s = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                elevBuf.putShort(s);
            }

            boolean hasTemp = climate != null;
            byte[] payload;
            if (hasTemp) {
                // Temperature = climate[0..H*W] as float32 LE
                ByteBuffer tempBuf = ByteBuffer.allocate(H * W * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < H * W; i++) tempBuf.putFloat(climate[i]);
                payload = new byte[elevBuf.capacity() + tempBuf.capacity()];
                System.arraycopy(elevBuf.array(), 0, payload, 0, elevBuf.capacity());
                System.arraycopy(tempBuf.array(), 0, payload, elevBuf.capacity(), tempBuf.capacity());
            } else {
                payload = elevBuf.array();
            }

            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("X-Height", String.valueOf(H));
            ex.getResponseHeaders().set("X-Width", String.valueOf(W));
            ex.getResponseHeaders().set("X-Has-Temp", hasTemp ? "1" : "0");
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Height, X-Width, X-Has-Temp");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
        } catch (Exception e) {
            LOG.error("detail_raw error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    // =========================================================================
    // Coarse channel helper — port of _coarse_channel() in server.py
    // =========================================================================

    /**
     * Return the given channel of the coarse map in real units.
     * Channels 0 and 1: undo signed-sqrt (sign(v) * v^2).
     */
    private static float[] coarseChannel(int ci0, int ci1, int cj0, int cj1, int channel) throws Exception {
        FloatTensor slice = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
        int H = ci1 - ci0, W = cj1 - cj0;
        float[] result = new float[H * W];
        for (int i = 0; i < H * W; i++) {
            float w   = slice.data[6 * H * W + i];
            float raw = (w > 1e-8f) ? slice.data[channel * H * W + i] / w : 0f;
            // Channels 0 (elev) and 1 (p5): signed-sqrt → real units via sign(v)*v^2
            result[i] = (channel <= 1) ? (float) (Math.signum(raw) * raw * raw) : raw;
        }
        return result;
    }

    // =========================================================================
    // PNG rendering
    // =========================================================================

    /** Encode RGBA channels (float[4][H*W]) to a PNG byte array. */
    private static byte[] toPng(float[][] rgba, int H, int W) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                int ri = (int) (clamp01(rgba[0][idx]) * 255f + 0.5f);
                int gi = (int) (clamp01(rgba[1][idx]) * 255f + 0.5f);
                int bi = (int) (clamp01(rgba[2][idx]) * 255f + 0.5f);
                int ai = (int) (clamp01(rgba[3][idx]) * 255f + 0.5f);
                img.setRGB(c, r, (ai << 24) | (ri << 16) | (gi << 8) | bi);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static float[][] applyColormap1D(float[] data, int H, int W, float vmin, float vmax, String cmap) {
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            float t = (data[i] - vmin) / (vmax - vmin);
            float[] rgb;
            switch (cmap) {
                case "terrain": rgb = Colormaps.terrain(clamp01(t)); break;
                case "rdbu_r":  rgb = Colormaps.rdBuR(clamp01(t));   break;
                default:        rgb = Colormaps.viridis(clamp01(t)); break;
            }
            rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
        }
        return rgba;
    }

    /**
     * Tint river (blue) and wonder (magenta) pixels over an already-rendered RGBA grid.
     * Query params rivers=1 / wonders=1 enable the corresponding overlay.
     */
    private static void applyOverlays(float[][] rgba, LocalTerrainProvider.PipelineData pd, int H, int W, Map<String, String> q) {
        boolean showRivers  = "1".equals(q.get("rivers"));
        boolean showWonders = "1".equals(q.get("wonders"));
        if (!showRivers && !showWonders) return;
        if (pd == null) return;
        for (int i = 0; i < H * W; i++) {
            if (showRivers && pd.riverMask != null && pd.riverMask[i]) {
                rgba[0][i] = 0.10f; rgba[1][i] = 0.45f; rgba[2][i] = 1.00f; rgba[3][i] = 1f;
            } else if (showWonders && pd.wonderMask != null && pd.wonderMask[i]) {
                rgba[0][i] = 1.00f; rgba[1][i] = 0.10f; rgba[2][i] = 0.90f; rgba[3][i] = 1f;
            }
        }
    }

    /** Render classified biome ids with a small fixed colour palette (gray = unclassified). */
    private static float[][] applyBiomeColormap(short[] biomeIds, int H, int W) {
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            float[] c = biomeColor(biomeIds[i]);
            rgba[0][i] = c[0]; rgba[1][i] = c[1]; rgba[2][i] = c[2]; rgba[3][i] = 1f;
        }
        return rgba;
    }

    private static float[] biomeColor(short id) {
        switch (id) {
            case 41: return rgb(0.00f, 0.25f, 0.55f); // warm ocean
            case 44: return rgb(0.10f, 0.30f, 0.65f); // ocean
            case 46: return rgb(0.20f, 0.40f, 0.75f); // cold ocean
            case 48: return rgb(0.55f, 0.75f, 0.95f); // frozen ocean
            case 9:  case 11: return rgb(0.20f, 0.45f, 0.85f); // river / frozen river
            case 2:  case 4:  case 7: return rgb(0.90f, 0.85f, 0.65f); // beaches / stony shore
            case 1:  return rgb(0.35f, 0.70f, 0.30f); // plains
            case 3:  return rgb(0.85f, 0.90f, 0.90f); // snowy plains
            case 5:  return rgb(0.95f, 0.85f, 0.45f); // desert
            case 6:  return rgb(0.25f, 0.45f, 0.30f); // swamp
            case 38: return rgb(0.30f, 0.55f, 0.50f); // mangrove swamp
            case 8:  return rgb(0.20f, 0.50f, 0.20f); // forest
            case 28: return rgb(0.10f, 0.30f, 0.15f); // dark forest
            case 30: return rgb(0.55f, 0.70f, 0.40f); // birch forest
            case 108:return rgb(0.45f, 0.65f, 0.35f); // forest sparse
            case 15: return rgb(0.25f, 0.45f, 0.30f); // taiga
            case 115:return rgb(0.35f, 0.55f, 0.40f); // taiga sparse
            case 16: return rgb(0.65f, 0.80f, 0.75f); // snowy taiga
            case 116:return rgb(0.70f, 0.82f, 0.78f); // snowy taiga sparse
            case 17: return rgb(0.75f, 0.75f, 0.35f); // savanna
            case 18: return rgb(0.85f, 0.65f, 0.30f); // savanna plateau
            case 23: return rgb(0.15f, 0.45f, 0.20f); // jungle
            case 26: return rgb(0.80f, 0.45f, 0.25f); // badlands
            case 29: return rgb(0.55f, 0.75f, 0.50f); // meadow
            case 31: return rgb(0.60f, 0.75f, 0.70f); // grove
            case 120:return rgb(0.50f, 0.70f, 0.55f); // custom grove
            case 19: return rgb(0.55f, 0.60f, 0.55f); // windswept hills
            case 32: return rgb(0.85f, 0.88f, 0.92f); // snowy slopes
            case 33: return rgb(0.95f, 0.97f, 1.00f); // frozen peaks
            case 35: return rgb(0.60f, 0.60f, 0.65f); // stony peaks
            default: return rgb(0.55f, 0.55f, 0.55f);
        }
    }

    private static float[] rgb(float r, float g, float b) { return new float[]{r, g, b}; }

    // =========================================================================
    // HTTP utilities
    // =========================================================================

    private static void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    private static void sendError(HttpExchange ex, int status, String msg) throws IOException {
        Map<String, String> err = new HashMap<>();
        err.put("error", msg != null ? msg : "unknown error");
        sendJson(ex, status, err);
    }

    private static void send405(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method Not Allowed");
    }

    private static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                map.put(pair, "");
            }
        }
        return map;
    }

    // =========================================================================
    // Math utilities
    // =========================================================================

    private static float nanMin(float[] arr) {
        float min = Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v < min) min = v;
        return min == Float.MAX_VALUE ? 0f : min;
    }

    private static float nanMax(float[] arr) {
        float max = -Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v > max) max = v;
        return max == -Float.MAX_VALUE ? 0f : max;
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Rounded 2-D list for coarse_data JSON (np.round equivalent). */
    private static List<List<Double>> roundedGrid(float[] flat, int H, int W, int decimals) {
        double factor = Math.pow(10, decimals);
        List<List<Double>> grid = new ArrayList<>(H);
        for (int r = 0; r < H; r++) {
            List<Double> row = new ArrayList<>(W);
            for (int c = 0; c < W; c++) {
                row.add(Math.round(flat[r * W + c] * factor) / factor);
            }
            grid.add(row);
        }
        return grid;
    }

    private static int getInt(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static Float getFloat(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null) return null;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return null; }
    }
}
