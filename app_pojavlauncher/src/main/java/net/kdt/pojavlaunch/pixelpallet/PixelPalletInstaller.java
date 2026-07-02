package net.kdt.pojavlaunch.pixelpallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Instalador "um clique" do PixelPallet, otimizado para mobile.
 *
 * Reaproveita a MESMA distribution.json do launcher desktop (pixelpallet-distribution):
 * baixa os mods para a instância, cria o perfil Forge 1.12.2 e aplica um conjunto de
 * otimizações balanceadas (bom FPS x visual) para o jogador abrir e jogar sem configurar.
 *
 * Otimizações aplicadas (perfil "Balanceado"):
 *   - Renderer Holy GL4ES (opengles2) — melhor compatibilidade/desempenho em 1.12.2
 *   - Escala de resolução 90% (renderiza um pouco menor => mais FPS)
 *   - JVM args afinados (G1GC de baixa pausa, flags do Forge)
 *   - options.txt pré-configurado (render distance 8, gráficos "fast", VBO, sem nuvens, etc.)
 *   - RAM: mantida no auto-detect do Pojav (device-aware, evita OOM em aparelho fraco)
 *
 * AINDA FALTA (ver PIXELPALLET_AUTOINSTALL.md): instalar Forge via ForgeDownloadTask,
 * injetar servidor no servers.dat, OptiFine via scraper do Pojav e o fluxo de UI.
 * Precisa de validação em aparelho real.
 */
public final class PixelPalletInstaller {

    private static final String TAG = "PixelPalletInstaller";

    /** Fonte de verdade: a mesma distribution do launcher desktop. */
    public static final String DISTRIBUTION_URL =
            "https://dexterlaboratorio.github.io/pixelpallet-distribution/distribution.json";

    public static final String INSTANCE_NAME = "pixelpallet";

    /** Versão do Forge que o perfil vai lançar (precisa estar instalada no Pojav). */
    public static final String FORGE_VERSION_ID = "1.12.2-forge-14.23.5.2860";

    /** Renderer recomendado para 1.12.2. */
    private static final String RENDERER = "opengles2"; // Holy GL4ES 1.1.4

    /** Escala de resolução (perfil balanceado). */
    private static final int RESOLUTION_RATIO = 90;

    /** JVM args balanceados para Forge 1.12.2 em mobile (o -Xmx é definido pelo Pojav). */
    private static final String JVM_ARGS = String.join(" ",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1HeapRegionSize=16M",
            "-XX:+DisableExplicitGC",
            "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Dfml.ignorePatchDiscrepancies=true");

    /** options.txt balanceado (chaves relevantes de performance para 1.12.2). */
    private static final String OPTIMIZED_OPTIONS = String.join("\n",
            "renderDistance:8",
            "particles:1",        // 0=todas, 1=reduzidas, 2=mínimas
            "fancyGraphics:false", // gráficos "fast"
            "ao:1",               // ambient occlusion mínimo
            "renderClouds:false",
            "useVbo:true",
            "mipmapLevels:2",
            "maxFps:120",
            "fboEnable:true",
            "entityShadows:false",
            "enableVsync:false",
            "guiScale:0") + "\n";

    private PixelPalletInstaller() {}

    public interface ProgressListener {
        void onProgress(String message);
    }

    /**
     * Baixa a distribuição, os mods, cria/atualiza o perfil e aplica as otimizações.
     * Deve rodar FORA da main thread (faz I/O de rede).
     */
    public static void install(Context context, ProgressListener listener) throws IOException {
        log(listener, "Buscando distribuicao...");
        String json = DownloadUtils.downloadString(DISTRIBUTION_URL);

        File instanceDir = new File(Tools.DIR_GAME_HOME, "custom_instances/" + INSTANCE_NAME);
        File modsDir = new File(instanceDir, "mods");
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw new IOException("Nao foi possivel criar a pasta de mods: " + modsDir);
        }

        try {
            JSONObject root = new JSONObject(json);
            JSONArray servers = root.getJSONArray("servers");
            JSONObject server = servers.getJSONObject(0);
            JSONArray modules = server.getJSONArray("modules");
            downloadForgeMods(listener, modules, modsDir);
        } catch (JSONException e) {
            throw new IOException("distribution.json invalida", e);
        }

        log(listener, "Aplicando otimizacoes...");
        writeOptimizedOptions(instanceDir);
        applyGlobalOptimizedPrefs();
        createOrUpdateProfile();
        log(listener, "Concluido!");
    }

    /** Baixa apenas os modulos do tipo ForgeMod (o Forge em si e instalado a parte). */
    private static void downloadForgeMods(ProgressListener listener, JSONArray modules, File modsDir)
            throws IOException {
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            if (module == null) continue;
            if (!"ForgeMod".equals(module.optString("type", ""))) continue;

            JSONObject artifact = module.optJSONObject("artifact");
            if (artifact == null) continue;
            String url = artifact.optString("url", null);
            if (url == null || url.isEmpty()) continue;

            String name = module.optString("name", "mod-" + i);
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            File dest = new File(modsDir, fileName);

            if (dest.exists() && dest.length() > 0) {
                log(listener, name + " (ja existe)");
                continue;
            }
            log(listener, "Baixando " + name + "...");
            DownloadUtils.downloadFile(url, dest);
        }
    }

    /** Escreve o options.txt otimizado no diretorio da instancia (se ainda nao existir). */
    private static void writeOptimizedOptions(File instanceDir) {
        File optionsFile = new File(instanceDir, "options.txt");
        if (optionsFile.exists()) return; // nao sobrescreve escolhas do jogador
        if (!instanceDir.exists() && !instanceDir.mkdirs()) return;
        try (FileWriter writer = new FileWriter(optionsFile)) {
            writer.write(OPTIMIZED_OPTIONS);
        } catch (IOException e) {
            Log.w(TAG, "Falha ao escrever options.txt otimizado", e);
        }
    }

    /** Aplica renderer e escala de resolucao globais (o launcher e single-purpose PixelPallet). */
    private static void applyGlobalOptimizedPrefs() {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return;
        prefs.edit()
                .putString("renderer", RENDERER)
                .putInt("resolutionRatio", RESOLUTION_RATIO)
                .apply();
    }

    private static void createOrUpdateProfile() {
        LauncherProfiles.load();
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = "PixelPallet";
        profile.lastVersionId = FORGE_VERSION_ID;
        profile.gameDir = "./custom_instances/" + INSTANCE_NAME;
        profile.pojavRendererName = RENDERER;
        profile.javaArgs = JVM_ARGS;
        LauncherProfiles.mainProfileJson.profiles.put(INSTANCE_NAME, profile);
        LauncherProfiles.write();
    }

    private static void log(ProgressListener listener, String message) {
        Log.i(TAG, message);
        if (listener != null) listener.onProgress(message);
    }
}
