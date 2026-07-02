package net.kdt.pojavlaunch.pixelpallet;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import net.kdt.pojavlaunch.JavaGUILauncherActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.ForgeDownloadTask;
import net.kdt.pojavlaunch.modloaders.ForgeUtils;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.ModloaderListenerProxy;

import java.io.File;

/**
 * Orquestrador do "um clique" do PixelPallet.
 *
 * Passos (em background):
 *   1. {@link PixelPalletInstaller#install} — baixa mods, escreve options.txt/servers.dat,
 *      aplica otimizações e cria o perfil "PixelPallet" (Forge 1.12.2).
 *   2. Se o Forge 1.12.2 ainda não está instalado, baixa o instalador do Forge
 *      ({@link ForgeDownloadTask}) e abre a {@link JavaGUILauncherActivity} que o instala
 *      automaticamente (cria a versão). Reutiliza o mesmo caminho testado do Pojav.
 *   3. Depois do Forge instalado, o perfil "PixelPallet" fica pronto para o botão JOGAR.
 *
 * FALTA: fluxo de UI que chama {@link #start} (botão "JOGAR PixelPallet") e, opcionalmente,
 * detectar o retorno da activity do instalador para lançar automaticamente. Precisa de
 * validação em aparelho.
 */
public final class PixelPalletSetup {

    private static final String TAG = "PixelPalletSetup";

    /** Versão completa do Forge para o download (formato do ForgeDownloadTask). */
    public static final String FORGE_FULL_VERSION = "1.12.2-14.23.5.2860";

    private PixelPalletSetup() {}

    public interface SetupListener {
        void onProgress(String message);
        /** Tudo pronto (Forge instalado + mods + perfil) — pode lançar. */
        void onReady();
        void onError(String message);
    }

    /** Inicia a preparação em background. Chamar da UI thread (usa activity.runOnUiThread). */
    public static void start(Activity activity, SetupListener listener) {
        new Thread(() -> {
            try {
                PixelPalletInstaller.install(activity, listener::onProgress);

                if (isForgeInstalled()) {
                    listener.onProgress("Pronto para jogar!");
                    activity.runOnUiThread(listener::onReady);
                    return;
                }

                listener.onProgress("Instalando Forge 1.12.2...");
                ModloaderListenerProxy proxy = new ModloaderListenerProxy();
                proxy.attachListener(new ModloaderDownloadListener() {
                    @Override
                    public void onDownloadFinished(File installerJar) {
                        // Abre o instalador do Forge (auto-install) na UI thread.
                        activity.runOnUiThread(() -> {
                            Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
                            ForgeUtils.addAutoInstallArgs(intent, installerJar, true);
                            activity.startActivity(intent);
                        });
                    }

                    @Override
                    public void onDataNotAvailable() {
                        listener.onError("Instalador do Forge indisponível.");
                    }

                    @Override
                    public void onDownloadError(Exception e) {
                        Log.e(TAG, "Erro ao baixar Forge", e);
                        listener.onError("Erro ao baixar o Forge: " + e.getMessage());
                    }
                });
                // Baixa o instalador do Forge; ao terminar, o listener acima abre a activity.
                new ForgeDownloadTask(proxy, FORGE_FULL_VERSION).run();

            } catch (Exception e) {
                Log.e(TAG, "Falha na preparação do PixelPallet", e);
                listener.onError("Falha: " + e.getMessage());
            }
        }, "PixelPalletSetup").start();
    }

    /** O Forge 1.12.2 já está instalado (existe o json da versão)? */
    public static boolean isForgeInstalled() {
        File versionJson = new File(Tools.DIR_HOME_VERSION,
                PixelPalletInstaller.FORGE_VERSION_ID + "/" + PixelPalletInstaller.FORGE_VERSION_ID + ".json");
        return versionJson.isFile();
    }
}
