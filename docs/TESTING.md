# Verificação

## Testes automatizados

```sh
./gradlew :core:test :lua:test
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

As suites Core/Lua são programas Kotlin determinísticos executados pelas tarefas `verifyCore`/`verifyLua`, finalizadoras de `test`. Uma falha lança exceção e faz Gradle/CI falhar; nenhuma depende de JVM assertions estarem ativadas. `tools/test-core.sh` permite executar o núcleo com kotlinc/JDK sem SDK Android.

Cobrem filtros, timestamps, perda/reacquisição, buffers, geometria sob rotação/translação/escala, debounce/cooldown, alternância MR/VR, recuperação da UI, touch versus hand, lifecycle e limites de janelas, ownership, identidade por código, default deny/revogação, plugins, térmico, projeção e inversa SBS, Lua/UI/eventos, namespaces/capabilities, referências retidas após revogação, quotas, código inválido, laços infinitos e isolamento da metatable de strings entre scripts.

Os testes de UI usam Robolectric com Canvas/Skia nativo: renderizam todas as páginas, validam recovery, bloqueio modal, transparência/oclusão entre janelas e persistência de settings. Screenshots são gerados em `app/build/reports/ui` e publicados como artefatos de CI; não são screenshots de hand tracking real. Há também um smoke test instrumentado da Activity, executável em aparelho/emulador.

Fixtures geométricas e timestamps sintéticos não comprovam precisão de MediaPipe em imagens reais.

## Checklist em aparelho (ainda necessário)

Registre fabricante/modelo, SoC/GPU, RAM, versão Android, serviço ARCore, câmera/lente selecionada, build e modelo SHA-256. Repita em pelo menos um aparelho intermediário ARM64, um sem ARCore, Android 11 e Android 14+.

| Área | Procedimento | Resultado esperado |
|---|---|---|
| Startup / permissão negada | Abrir sem grants, negar câmera | Home/Notas/Relógio/touch funcionam; câmera/tracking não inventam dados |
| MR | Autorizar câmera; trocar frontal/traseira; mudar orientação | Passthrough sem esticar/crop inconsistente; landmarks/cursor acompanham o mesmo ponto |
| Modelo ausente | Build com skip e sem asset | NO_MODEL explícito; nenhuma inferência fictícia; UI/câmera continuam |
| Tracking | 0, 1 e 2 mãos, luz baixa, rotações e oclusão | Lateralidade/orientação plausíveis; perda oculta dados antigos; calibrar swap se necessário |
| One Euro | Parado, lento, rápido; variar cutoff/beta | Menos jitter sem arrasto perceptível excessivo; medir, não apenas observar |
| Cursor/pinça | Apontar, hover, pinçar, arrastar, perder a mão | Um down/up por pinça; cancel na perda; sem input preso |
| Arma direita | Pose estável → dobrar → manter → rearmar | Uma alternância MR/VR; manter dobrado não repete |
| Esquerda | Arma esconde; fist 4 s, 5 s; oclusões curtas/longas | 4 s não dispara; 5 s reabre; gaps pausam/resetam, nunca avançam o hold |
| Recuperação | UI oculta, mão fora de visão | Um toque reabre/recentra; não exige tracking funcionando |
| VR/SBS | Ajustar IPD, FOV, escala; tocar nos dois olhos | Visões distintas da UI; hit testing de ambos os olhos corresponde ao mesmo alvo |
| Head / ARCore | Recenter; ARCore indisponível, instalado, relocalizando, câmera ocupada | Status honesto, fallback; nenhum conflito intencional CameraX + ARCore |
| Multi Window | Sobrepor apps externos, notas e Lua; mover/resize/minimizar/fechar | Conteúdo, chrome, foco e input respeitam z-order; nenhum click-through em painel opaco |
| GAME/WINDOW | Abrir os exemplos e janela filha | Mesmo runtime, chrome apropriado; ownership e contexto corretos |
| Apps externos | App resizable e app que recusa display; orientação fixa; FLAG_SECURE | Compatível recebe Surface; incompatível relata motivo; nenhum bypass |
| Accessibility | Serviço off; sessão negada; app errado; sem foco; display secundário | Recusa segura; gesto apenas no pacote/display autorizado; não clica na UI do próprio host |
| MediaProjection | Negar; escolher app; parar pela notificação/sistema; tela inteira | Consentimento novo, callback fecha recursos, conteúdo seguro oculto, sem recursão |
| Lua | Exemplos, editor, loops, sintaxe inválida; grants/revogações durante execução | Erro local sem interpretar na main; UI global/scenario continuam protegidos |
| Lifecycle | Home, lock/unlock, rotação, screen off, background, perda de EGL, force-stop | Câmera/sensores param; displays perdidos pedem reabertura; captura não reutiliza token |
| Acessibilidade da UI | TalkBack e navegação pelos nós virtuais | Botões têm nomes/ações e bounds projetados; cenas não são uma imagem muda |

## Protocolo de performance

1. Aquecer por 2 min e medir 15–20 min por perfil, sem carregador. Anotar temperatura ambiente e capinha.
2. Baselines: UI touch sem câmera; MR sem tracking; MR + 1/2 mãos; VR/SBS; ARCore; 1/3 apps externos; Lua visível/minimizado.
3. Capturar Perfetto/Android Studio Profiler em execução local: frame pacing P50/P95/P99, tempo de inferência, alocações/GC, CPU/GPU, memória PSS, power/thermal status e latência câmera→cursor filmada externamente.
4. Confirmar idle ~5 FPS após perda de mãos, recuperação, redução de escala com histerese, fallback ARCore em estado severo e suspensão de inferência em crítico.
5. Não usar FPS alvo como FPS medido. O contador GL mede entregas do renderer, não motion-to-photon. `inferenceMillis` mede a chamada MediaPipe, não toda a câmera.
6. Qualquer regressão de estabilidade/latência deve bloquear novos efeitos/recursos. Não reduzir artificialmente as salvaguardas térmicas para atingir uma meta de FPS.

## Estado da validação nesta reconstrução

O resultado efetivamente observado (incluindo CI/build) é registrado em `docs/VALIDATION.md`. Testes físicos nunca devem ser marcados como aprovados por inferência a partir de unit tests ou de um APK compilado.
