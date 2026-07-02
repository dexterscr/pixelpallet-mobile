# PixelPallet — Auto-instalação "JOGAR" (igual launcher do PC)

Objetivo: um botão **JOGAR** que baixa Forge 1.12.2 + Pixelmon + mods e entra no servidor,
sem o usuário configurar nada — replicando o launcher desktop (Helios) no mobile.

## Estratégia: reusar a infraestrutura do Pojav

Não reinventar. Orquestrar as classes que já existem:

| Peça | Classe do Pojav | Papel |
|---|---|---|
| Instalar Forge | `modloaders/ForgeDownloadTask` + `ForgeUtils.addAutoInstallArgs` | Baixa `forge-<ver>-installer.jar` e roda o instalador (cria o perfil Forge). Roda via `JavaGUILauncherActivity` com um agente que auto-clica o instalador. |
| Baixar mods | `modloaders/modpacks/api/ModDownloader` | `submitDownload(size, relativePath, hash, url)` → `awaitFinish(feedback)`. Baixa pra `<gamedir>/mods/` com validação. |
| Servidor | escrever `servers.dat` (NBT) no gamedir | Deixa o PixelPallet já na lista de servidores. |
| Lançar | fluxo de launch do `LauncherActivity`/`MainMenuFragment` | Abre o Minecraft no perfil Forge. |

Fonte de verdade dos mods: a **mesma `distribution.json`** de `pixelpallet-distribution`
(Forge 1.12.2-14.23.5.2860 + Pixelmon 8.3.8 + CustomNPCs + PalletCoins), já com URLs e hashes.

## Fluxo do botão JOGAR (estado)

1. Perfil Forge 1.12.2 já existe? Se não → `ForgeDownloadTask` (instala) → aguarda resultado.
2. Baixar/validar mods via `ModDownloader` pra pasta de mods do perfil.
3. Garantir `servers.dat` com o PixelPallet.
4. Lançar o perfil (auto-connect opcional).
Estados persistidos pra não repetir download (checagem de hash/versão).

## Riscos honestos (precisam de teste em aparelho)
- **Forge 1.12.2 + Pixelmon é pesado**: exige aparelho forte (6GB+ RAM). Pode não rodar em todos.
- O instalador do Forge roda como uma "activity separada" — orquestrar *instala Forge → depois baixa mods → depois lança* exige lidar com resultados de activity. É a parte mais delicada.
- Só validável via **CI (build) + seu celular**. Iterativo.

## Otimização mobile (perfil "Balanceado")
Aplicada pelo `PixelPalletInstaller`:
- [x] Renderer Holy GL4ES (`opengles2`) — perfil + global
- [x] Escala de resolução 90% (`resolutionRatio`)
- [x] JVM args G1GC de baixa pausa + flags do Forge
- [x] `options.txt` pré-configurado (render distance 8, gráficos fast, VBO, sem nuvens, entity shadows off, vsync off)
- [x] RAM no auto-detect device-aware do Pojav
- [ ] Mods de otimização (FoamFix, Phosphor, VanillaFix, BetterFPS) — adicionar na distribution.json (beneficia desktop também)
- [ ] OptiFine 1.12.2 — baixar em runtime via o scraper do Pojav (OptiFineUtils/OptiFineDownloadTask)
- [ ] Pixelmon config pré-tunada

## Progresso do instalador
- [x] `PixelPalletInstaller`: baixa mods + cria perfil + otimizações (compila via CI)
- [ ] Instalar Forge 1.12.2 automaticamente (ForgeDownloadTask)
- [ ] UI: botão/tela "JOGAR PixelPallet" + progresso
- [ ] Injeção do `servers.dat`
- [ ] Teste ponta a ponta no aparelho

## Validado (emulador oficial no PC)
- [x] App roda (ARM via emulador x86), login offline, **download do Minecraft com conta local** (removido o gate anti-pirataria em `MinecraftDownloader`), jogo inicia e renderiza. Lentidão só pela tradução ARM→x86 do emulador; ARM nativo roda normal.
