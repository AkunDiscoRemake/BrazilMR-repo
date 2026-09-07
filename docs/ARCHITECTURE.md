# Arquitetura e decisões

## Auditoria antes da reconstrução

`docs/AUDIT.md` registra todos os arquivos e commits existentes. Havia README e três configurações Gradle removidas no histórico; nenhum componente XR funcional a reaproveitar. A visão antiga permanece em `docs/VISION.md`.

## Dependências

```text
app (Android / composição)
 ├── core (Kotlin JVM, sem Android)
 └── lua (Kotlin JVM + LuaJ)
      └── core
```

| Responsabilidade | Implementação |
|---|---|
| Core / ciclo MR–VR / UI visível | `core/session/XrSession` |
| HandTrackingManager / snapshots | `core/tracking`, `app/tracking/CameraHandTrackingManager` |
| MediaPipe | `app/tracking/MediaPipeBackend`, único local que conhece a task de mãos |
| OneEuroFilter | `core/filter` |
| Geometria e gestos | `core/gesture` |
| Input unificado / cursor / debounce | `core/input`, `WorkspaceInputView`, `XrUiScene` |
| MR / câmera normal | `app/tracking/CameraController` (CameraX) |
| MR espacial opcional | `app/spatial/ArCoreEnvironment` |
| Head tracking fallback | `app/spatial/HeadTracker` |
| VR / SBS / projeção reversível | `core/spatial/SpatialProjection`, `app/render/SBSRenderer` |
| Multi Window | `core/window/WindowManagerXR` |
| Apps Android / Accessibility / compartilhamento | `app/bridge` |
| LuaRuntime / API | `lua`, composição em `AndroidLuaController` |
| Capabilities / plugins | `core/permission`, `core/plugin` |
| Developer SDK | `sdk`, editor e console no app |

## Threads e propriedade de memória

1. **Main / Choreographer**: sessão, janelas, UI, permissões, gestos/input, snapshots do renderer. CameraX bind/unbind ocorre aqui.
2. **Inference executor serial**: modelo, frame raw, filtros, buffers RGBA/YUV. CameraX usa `KEEP_ONLY_LATEST`. ARCore entrega no máximo uma imagem CPU pendente. Todo ImageProxy/Image é fechado em `finally`, mesmo ao reduzir FPS ou falhar.
3. **GL**: texturas OES, SurfaceTextures, uploads da UI, framebuffers, câmeras ARCore e os dois viewports. Não lê coleções mutáveis da UI. `RenderFrame` e a projeção usada no hit testing são copiados sob locks curtos.
4. **Lua worker serial com fila de 32**: código de usuário, quotas e callbacks. Comandos de host atravessam um FutureTask com timeout; tarefas ainda não executadas são canceladas. Lua não bloqueia a main thread com interpretação de código.

A mailbox de mãos contém duas cópias prealocadas de 21 × 3 coordenadas por mão, geometria e metadados. O pipeline filtra landmarks de UI; a geometria de gesto usa world landmarks, ou coordenadas normalizadas com correção de aspecto como fallback. Sem mão, dados antigos não são usados como detecção atual.

Defaults do filtro: minimum cutoff 2 Hz, beta 8 (coordenadas normalizadas) e derivative cutoff 1 Hz. Cutoff/beta são ajustáveis; não se usa um beta pensado para unidades em pixels.

CameraX RGBA sem padding passa diretamente à inferência síncrona. Padding é compactado em buffer direto reutilizável. ARCore YUV é convertido uma vez em outro uso desse buffer. **MPImage ByteBuffer wrappers são fechados antes da reutilização.** Não se usa BitmapImageBuilder porque seu `close()` recicla o Bitmap; reutilizar esse Bitmap no frame seguinte seria incorreto.

MediaPipe, CameraX, ARCore, drivers e seus resultados **ainda podem alocar/copiar internamente**. A implementação não promete zero alocações end-to-end.

## Gestos

- Extensão/curl: ângulos articulares e distâncias relativas em 3D. Não depende de mão apontando para cima na imagem.
- Arma: thumb estendido, médio/anelar/mínimo curvados, indicador inicialmente estendido.
- `IDLE → PRIMING (140 ms) → ARMED → TRIGGERING (70 ms) → COOLDOWN (1 s)`.
- É necessário rearmar; um indicador mantido curvado não dispara repetidamente. Perda de mão desarma. Pequenas variações da base têm tolerância limitada.
- Direita alterna MR/VR. Esquerda oculta UI. Punho esquerdo por ~5 s reabre/reancora; falhas breves pausam o tempo, não o somam. Perda prolongada reinicia o hold.
- Pinça usa histerese (.28/.43 da largura da palma), down após 65 ms, up após 55 ms e intervalo de 250 ms entre cliques. Gesto de arma suprime pinça. Perda da mão e touch geram cancel.
- Touch mantém a posse até soltar/cancelar, mais uma janela de 750 ms; duas fontes não disputam um drag.

Esses limiares são pontos iniciais testados sinteticamente. Capturas reais em diferentes câmeras, luz, mãos e oclusões são indispensáveis antes de afirmar robustez em produção.

## Renderização

GLES 2, sem Compose/WebView/Unity, blur ou pós-processamento pesado. Um bitmap 1600×900 representa a UI e só é redesenhado quando há invalidação. Duas slots separam ownership Canvas/GL. Cursor é um ponto GL independente. Um FBO permite escala dinâmica; a imagem final é composta na resolução da tela.

SBS usa dois viewports com IPD e projeção por olho. Ray-plane intersection é o inverso da mesma projeção, usado por input e nós de acessibilidade. UI fica em um plano espacial; janelas têm posições 2D independentes nesse workspace. Não é um compositor de janelas em profundidades arbitrárias.

Apps externos usam até três OES surfaces de displays próprios; uma quarta superfície é reservada para compartilhamento. Vídeo é composto **antes** da UI. A UI abre uma área transparente para cada superfície; janelas posteriores a cobrem, preservando a ordem entre apps externos, Lua e janelas nativas. Não há cópia CPU de cada tela de app por frame.

## Câmera espacial

CameraX e ARCore nunca são deliberadamente donos simultâneos da câmera. O host faz unbind do primeiro antes de tentar `Session.resume()` do segundo. ARCore usa `LATEST_CAMERA_IMAGE`, foco automático, pose real e plane finding; depth automático permanece desligado para poupar recursos. Suporte/instalação, câmera ocupada e relocalização são tratados com status e fallback. Sem ARCore não se inventa 6DoF.

Pausar a Activity interrompe câmera/inferência/render e sensores. Perda do contexto EGL invalida displays externos e pede reabertura/novo consentimento. Não se tenta reutilizar token de MediaProjection.

## Performance e térmico

- Tracking configurável 5–60 FPS, 320/640/960 px solicitados; formato efetivo depende de CameraX/ARCore.
- Sem mãos por 1,5 s: busca a 5 FPS. Perfis limitam o teto.
- Cadência por deadlines acumulados: 24 FPS sobre câmera de 30 FPS não cai acidentalmente para 15 por arredondamento; a busca acelera imediatamente quando o teto muda.
- Render alvo limitado pelo perfil; Choreographer não faz busy-wait. A taxa real é contada no renderer, não preenchida com o alvo.
- Escala dinâmica reage ao tempo de trabalho observado com ajustes no máximo a cada segundo e passos assimétricos. Não é um medidor de tempo GPU via queries.
- Severo: render ≤30 FPS, escala ≤.65, tracking ≤5 FPS e fallback de câmera espacial. Crítico: inferência é pausada, análise RGBA é desanexada e a câmera só permanece se necessária para passthrough.
- FPS, latência, consumo e temperatura **não foram certificados em celulares intermediários**; há um protocolo em `TESTING.md`.

## Fronteiras de segurança

Capabilities são registradas por manifesto e concedidas por ação explícita da UI. O código/hashes não recebe objetos de Context, Activity, JVM reflection ou serviços Android. Cada chamada privilegiada checa o grant atual e cada ID checa ownership.

O registro de plugins é infraestrutura para extensões estaticamente compiladas. Não isola código Kotlin confiável dentro do mesmo processo. O Lua restringe APIs e execução, mas não implementa isolamento rígido de heap. Não há marketplace, atualização remota de scripts ou loader externo nesta etapa.
