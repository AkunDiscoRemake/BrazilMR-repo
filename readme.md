# Brazil MR

**Um smartphone. Um espaço XR.** Plataforma Android nativa experimental, preta e roxa, construída com desempenho, baixa latência e limites de segurança explícitos.

> **Foundation 0.1 — não é uma release de produção.** Esta reconstrução parte de um repositório que continha apenas uma especificação. Há código nativo, núcleo testável, janelas, MediaPipe, renderização GLES/SBS e SDK Lua; compatibilidade de câmera, ARCore, headsets e apps externos requer validação em dispositivos reais. Não há promessa de executar qualquer app Android dentro de VR.

## Compilar

Android Studio Ladybug ou mais recente, **JDK 17**, SDK Android **35**, acesso a Google Maven e Maven Central.

```sh
./gradlew :core:test :lua:test
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

O build Android baixa o modelo Hand Landmarker oficial (7,8 MB), verifica SHA-256 e o mantém fora do Git. Para preparar manualmente: `python3 tools/fetch_hand_model.py`. Para desenvolver a UI sem baixar o modelo: `./gradlew -PskipHandModel=true :app:assembleDebug`; nesse caso, sem um modelo local válido o tracking informa **NO_MODEL**, não dados simulados.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest  # aparelho/emulador conectado
```

## Experimentar

1. Abra o app em paisagem. Notas e Relógio demonstram o workspace sem pedir acesso à câmera.
2. Permita a câmera na Central de permissões. Imagens são processadas localmente; o app não solicita acesso à internet.
3. Mão direita: indicador aponta; pinça pressiona/solta e arrasta. Touch utiliza o mesmo sistema de input.
4. Gesto de arma direito: estabilize a pose e dobre o indicador para alternar MR/VR. Há cooldown e rearme obrigatório.
5. Gesto de arma esquerdo: oculta a UI. Punho esquerdo fechado por cerca de cinco segundos a reposiciona e reabre. **Toque na tela também recupera uma UI oculta.**
6. Em Apps, abra janelas internas, exemplos Lua ou tente um app Android compatível. Arraste o título, redimensione pelo canto, minimize, foque, organize e feche.
7. Em Developer, execute os exemplos, edite um rascunho e consulte a API. Capabilities são concedidas por app/hash na Central, nunca pelo próprio script.

Use VR sentado, em uma área segura. Passthrough monocular tem latência e não substitui visão direta. Interrompa ao sentir desconforto ou aquecimento.

## Arquitetura

- **`:core`** — landmarks, mailbox, One Euro, geometria 3D, máquinas de gestos, input, WindowManagerXR, sessão MR/VR, projeção, capabilities, plugins e política térmica. Sem dependência Android ou MediaPipe.
- **`:lua`** — LuaJ com superfície de API restrita, ownership, permissões revogáveis e quotas. UI de cada app permanece dentro de suas janelas.
- **`:app`** — CameraX/MediaPipe, ARCore opcional, sensores, GLES 2/SBS, UI Canvas com nós de acessibilidade, displays virtuais, Accessibility Bridge, MediaProjection e ferramentas de desenvolvimento.
- **`sdk/`** — exemplos e referência de API incorporados ao aplicativo.

O histórico anterior e as decisões de reaproveitamento estão em [docs/AUDIT.md](docs/AUDIT.md). A visão original foi preservada em [docs/VISION.md](docs/VISION.md).

## Limites essenciais

- Displays virtuais e execução de Activities dependem das políticas do Android/OEM e do app. Falhas são reportadas; abrir fora do XR é uma alternativa explícita.
- Accessibility não é injeção privilegiada de input: exige habilitação no Android, autorização de sessão, app correto e foco no display correto. Input em displays secundários requer Android 11+.
- MediaProjection exige consentimento novo por sessão. É **compartilhamento**, não execução independente. `FLAG_SECURE` é respeitado.
- SBS duplica a câmera monocular, mas projeta a UI com separação geométrica de olhos. Não produz profundidade estéreo real da câmera nem calibração óptica universal de headsets.
- ARCore é opcional e usa a câmera de forma exclusiva. Sem ele há orientação 3DoF por sensores, não 6DoF inventado.
- O runtime Lua é para desenvolvimento local. Não há carregamento remoto de código, DEX, bibliotecas nativas, `io`, `os`, `luajava`, `require`, shell ou rede. `unsafe_execution` concede somente operações globais nomeadas da plataforma, não privilégios Android.
- Quotas de instruções, tempo, strings, widgets e tabelas não são isolamento rígido de heap/processo. Uma comunidade com scripts hostis exige processo isolado e auditoria adicionais antes de distribuição.

## Verificação e documentação

Consulte [docs/TESTING.md](docs/TESTING.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/ANDROID_LIMITATIONS.md](docs/ANDROID_LIMITATIONS.md) e [sdk/API_REFERENCE.md](sdk/API_REFERENCE.md).

A CI em `.github/workflows/android.yml` executa testes do núcleo/Lua, build debug e lint. Não substitui testes em hardware. APKs, modelos e caches não são versionados.

A licença do projeto ainda não foi definida pelo mantenedor; dependências e modelos mantêm suas próprias licenças e termos.
