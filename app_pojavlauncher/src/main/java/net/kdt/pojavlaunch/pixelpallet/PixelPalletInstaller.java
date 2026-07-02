package net.kdt.pojavlaunch.pixelpallet;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Instalador "um clique" do PixelPallet.
 *
 * Reaproveita a MESMA distribution.json do launcher desktop (pixelpallet-distribution):
 * baixa os mods (Pixelmon, CustomNPCs, PalletCoins) para a pasta de mods da instância
 * e cria o perfil Forge 1.12.2.
 *
 * ESTADO: primeira fatia — compila e baixa os mods, mas ainda PRECISA DE:
 *   1. Instalar o Forge 1.12.2 via o instalador de Forge do Pojav (ForgeDownloadTask)
 *      antes de lançar — o perfil aponta para {@link #FORGE_VERSION_ID}.
 *   2. Injetar o servidor PixelPallet no servers.dat da instância.
 *   3. Fluxo de UI (botão "JOGAR PixelPallet" + progresso).
 * Ver PIXELPALLET_AUTOINSTALL.md. Precisa de validação em aparelho real.
 */
public final class PixelPalletInstaller {

    private static final String TAG = "PixelPalletInstaller";

    /** Fonte de verdade: a mesma distribution do launcher desktop. */
    public static final String DISTRIBUTION_URL =
            "https://dexterlaboratorio.github.io/pixelpallet-distribution/distribution.json";

    public static final String INSTANCE_NAME = "pixelpallet";

    /** Versão do Forge que o perfil vai lançar (precisa estar instalada no Pojav). */
    public static final String FORGE_VERSION_ID = "1.12.2-forge-14.23.5.2860";

    private PixelPalletInstaller() {}

    public interface ProgressListener {
        void onProgress(String message);
    }

    /**
     * Baixa a distribuição, os mods e cria/atualiza o perfil.
     * Deve rodar FORA da main thread (faz I/O de rede).
     */
    public static void install(ProgressListener listener) throws IOException {
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

    private static void createOrUpdateProfile() {
        LauncherProfiles.load();
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = "PixelPallet";
        profile.lastVersionId = FORGE_VERSION_ID;
        profile.gameDir = "./custom_instances/" + INSTANCE_NAME;
        LauncherProfiles.mainProfileJson.profiles.put(INSTANCE_NAME, profile);
        LauncherProfiles.write();
    }

    private static void log(ProgressListener listener, String message) {
        Log.i(TAG, message);
        if (listener != null) listener.onProgress(message);
    }
}
