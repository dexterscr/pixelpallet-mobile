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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

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

    /** Chave (UUID fixo) do perfil no launcher_profiles — evita duplicatas e sobrevive à
     *  normalização de IDs do Pojav (que converteria uma chave não-UUID). */
    public static final String PROFILE_KEY = "b1a1b1a1-c0de-4a11-9e77-000000000001";

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

        String serverName = "PixelPallet";
        String serverAddress = null;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray servers = root.getJSONArray("servers");
            JSONObject server = servers.getJSONObject(0);
            serverName = server.optString("name", serverName);
            serverAddress = server.optString("address", null);
            JSONArray modules = server.getJSONArray("modules");
            syncForgeMods(listener, modules, modsDir);
            syncFileModules(listener, modules, instanceDir);
        } catch (JSONException e) {
            throw new IOException("distribution.json invalida", e);
        }

        log(listener, "Aplicando otimizacoes...");
        writeOptimizedOptions(instanceDir);
        if (serverAddress != null && !serverAddress.isEmpty()) {
            log(listener, "Adicionando servidor a lista...");
            writeServersDat(instanceDir, serverName, serverAddress);
        }
        applyGlobalOptimizedPrefs();
        createOrUpdateProfile();
        log(listener, "Concluido!");
    }

    /**
     * Sincroniza a pasta de mods com a distribution (roda a cada JOGAR):
     *  - baixa mods faltando ou que mudaram (compara nome + tamanho esperado);
     *  - remove versões antigas / mods que saíram da distribuição.
     * Assim o cliente sempre fica na versão mais atual sem re-baixar tudo à toa.
     */
    private static void syncForgeMods(ProgressListener listener, JSONArray modules, File modsDir)
            throws IOException {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            if (module == null) continue;
            if (!"ForgeMod".equals(module.optString("type", ""))) continue;

            JSONObject artifact = module.optJSONObject("artifact");
            if (artifact == null) continue;
            String url = artifact.optString("url", null);
            if (url == null || url.isEmpty()) continue;

            long expectedSize = artifact.optLong("size", -1);
            String name = module.optString("name", "mod-" + i);
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            expected.add(fileName);
            File dest = new File(modsDir, fileName);

            // Já está atualizado? (nome bate e tamanho confere)
            if (dest.exists() && (expectedSize < 0 || dest.length() == expectedSize)) {
                log(listener, name + " (atualizado)");
                continue;
            }
            log(listener, "Atualizando " + name + "...");
            DownloadUtils.downloadFile(url, dest);
        }

        // Remove versões antigas / mods removidos da distribuição.
        File[] existing = modsDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.isFile() && f.getName().endsWith(".jar") && !expected.contains(f.getName())) {
                    log(listener, "Removendo mod antigo: " + f.getName());
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    /**
     * Processa os módulos do tipo "File" da distribution: baixa cada arquivo para o caminho
     * relativo à instância indicado em artifact.path (ex.: o mainmenu.json em
     * config/CustomMainMenu/ e o fundo da tela em resources/pixelpallet/...). Assim os assets da
     * tela inicial custom chegam pelos dois launchers (o Helios já trata File nativamente).
     * Baixa só o que mudou (compara tamanho). Falha em silêncio por arquivo.
     */
    private static void syncFileModules(ProgressListener listener, JSONArray modules, File instanceDir) {
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.optJSONObject(i);
            if (module == null || !"File".equals(module.optString("type", ""))) continue;
            JSONObject artifact = module.optJSONObject("artifact");
            if (artifact == null) continue;
            String url = artifact.optString("url", null);
            String relPath = artifact.optString("path", null);
            if (url == null || url.isEmpty() || relPath == null || relPath.isEmpty()) continue;
            try {
                File dest = new File(instanceDir, relPath);
                long expectedSize = artifact.optLong("size", -1);
                if (dest.exists() && expectedSize >= 0 && dest.length() == expectedSize) {
                    continue; // já atualizado
                }
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
                DownloadUtils.downloadFile(url, dest);
                log(listener, module.optString("name", "arquivo") + " (ok)");
            } catch (Exception e) {
                Log.w(TAG, "Falha ao baixar File module " + relPath + ": " + e);
            }
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

    /**
     * Escreve o servers.dat (NBT nao-comprimido) com o servidor do PixelPallet, para o
     * jogador ja encontrar o servidor na lista de multiplayer. Nao sobrescreve um existente.
     * Formato NBT: writeUTF do DataOutputStream ja usa o mesmo modified-UTF8 que o NBT.
     */
    private static void writeServersDat(File instanceDir, String serverName, String address) {
        File serversDat = new File(instanceDir, "servers.dat");
        if (serversDat.exists()) return;
        if (!instanceDir.exists() && !instanceDir.mkdirs()) return;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(serversDat))) {
            out.writeByte(10);            // TAG_Compound (raiz)
            out.writeUTF("");             // nome da raiz
            out.writeByte(9);             // TAG_List
            out.writeUTF("servers");      // nome da lista
            out.writeByte(10);            // tipo dos elementos = TAG_Compound
            out.writeInt(1);              // 1 servidor
            // elemento: compound com name/ip
            out.writeByte(8);             // TAG_String
            out.writeUTF("name");
            out.writeUTF(serverName);
            out.writeByte(8);             // TAG_String
            out.writeUTF("ip");
            out.writeUTF(address);
            out.writeByte(0);             // TAG_End (fim do compound do servidor)
            out.writeByte(0);             // TAG_End (fim da raiz)
        } catch (IOException e) {
            Log.w(TAG, "Falha ao escrever servers.dat", e);
        }
    }

    /** Aplica renderer e escala de resolucao globais (o launcher e single-purpose PixelPallet). */
    private static void applyGlobalOptimizedPrefs() {
        SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
        if (prefs == null) return;
        prefs.edit()
                .putString("renderer", RENDERER)
                .putInt("resolutionRatio", RESOLUTION_RATIO)
                // Seleciona o perfil PixelPallet como atual (para o botao JOGAR lancar ele).
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, PROFILE_KEY)
                .apply();
    }

    /**
     * Garante que o perfil PixelPallet exista e esteja selecionado, já na abertura do app
     * (sem rede). Assim o seletor mostra "PixelPallet" desde a primeira execução, em vez do
     * perfil padrão do Pojav. É idempotente e barato — pode ser chamado sempre.
     */
    public static void ensureProfileReady() {
        try {
            applyGlobalOptimizedPrefs();
            createOrUpdateProfile();
        } catch (Throwable t) {
            Log.w(TAG, "ensureProfileReady falhou: " + t);
        }
    }

    private static void createOrUpdateProfile() {
        LauncherProfiles.load();
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = "PixelPallet";
        profile.lastVersionId = FORGE_VERSION_ID;
        profile.gameDir = "./custom_instances/" + INSTANCE_NAME;
        profile.pojavRendererName = RENDERER;
        profile.javaArgs = JVM_ARGS;
        LauncherProfiles.mainProfileJson.profiles.put(PROFILE_KEY, profile);
        LauncherProfiles.write();
    }

    private static void log(ProgressListener listener, String message) {
        Log.i(TAG, message);
        if (listener != null) listener.onProgress(message);
    }
}
