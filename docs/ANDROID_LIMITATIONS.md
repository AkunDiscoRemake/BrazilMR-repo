# Android App Bridge: capacidades reais e limites

## 1. Executar um app compatível numa janela XR

O launcher consulta somente Activities `MAIN/LAUNCHER` exportadas e habilitadas. Não pede `QUERY_ALL_PACKAGES`. Ao tocar no app, o usuário pode tentar uma janela WINDOW/GAME ou abrir normalmente no Android.

Fluxo:

```text
Activity Android exportada
 → DisplayManager.createVirtualDisplay(OWN_CONTENT_ONLY | PRESENTATION)
 → ActivityOptions.setLaunchDisplayId
 → Surface / SurfaceTexture OES
 → região da janela XR
```

Em Android 10+, `ActivityManager.isActivityStartAllowedOnDisplay` é consultado. Em versões anteriores, o sistema valida a tentativa e exceções são tratadas. Requisitos do app, políticas de displays privados, resize, embedding, foco, orientação e customizações do fabricante podem recusar a execução ou deixar uma tela sem conteúdo. Um start aceito não é garantia de que todo app produzirá frames ou aceitará input.

- Não há root, Shizuku, shell/ADB, API oculta, virtualização de processos, clonagem de APK, `INJECT_EVENTS` ou bypass de sandbox.
- No máximo três apps externos simultâneos. Minimizar desanexa a Surface para reduzir pressão de render; não força kill do processo de outro app.
- Fechar libera o display e a autorização de input. Isso não concede poder de encerrar arbitrariamente todos os processos do pacote.
- Se o app for incompatível, a alternativa é **abrir fora do XR**. A plataforma não afirma manter um app foreground na tela física e, ao mesmo tempo, embuti-lo magicamente em outra janela.

## 2. Accessibility Bridge

É opcional, `isAccessibilityTool=false`, habilitado manualmente nos ajustes Android e com autorização explícita por sessão no Brazil MR. Só existem alvos para apps que o usuário abriu pela ponte. O serviço verifica display, packageName do root focado e bounds da janela. Não percorre/coleciona textos ou senhas e não faz automação autônoma.

- Android 11+: `GestureDescription.Builder.setDisplayId` pode direcionar input a um display secundário, quando acessível pelo serviço.
- Android 8–10: a plataforma não promete input em displays secundários.
- Android pode negar ou cancelar o gesto. App incorreto, sem foco, janela ausente, coordenadas inválidas e ausência de autorização resultam em recusa, nunca numa tentativa cega na tela padrão.
- A implementação envia clique/arrasto linear **ao soltar** o ponteiro, via dispatchGesture. Não é streaming de MotionEvent ou arrasto privilegiado sem latência. Press-and-hold é reproduzido com duração limitada; jogos que exigem multitouch/controles contínuos devem usar o SDK integrado ou controles próprios.
- Barras/letterboxing não são tratados como conteúdo clicável. Mudanças incompatíveis de orientação/resolução são recusadas até resize/reabertura. Diálogos de permissão pertencentes ao sistema não recebem input dirigido ao pacote do app; use os ajustes oficiais Android.
- Não se encaminham eventos do espelho MediaProjection a um display desconhecido.

Publicação na Play Store exige declaração/revisão do uso de Accessibility conforme a política vigente. Este projeto não implica aprovação de loja.

## 3. MediaProjection: compartilhamento, não execução

O usuário escolhe o conteúdo no diálogo oficial. Um foreground service do tipo `mediaProjection` mantém notificação de parada. Cada sessão usa consentimento novo; a callback de revogação libera o display e a Surface. Não há reinício automático após morte de processo.

Android 14+ permite seleção de um app pelo diálogo do sistema, quando disponível. Compartilhar a tela inteira pode capturar a própria interface; o Brazil MR marca sua janela como `FLAG_SECURE` durante compartilhamento para impedir o efeito recursivo. Por isso a captura de tela inteira pode ficar preta quando Brazil MR está à frente. Conteúdo DRM, janelas seguras e políticas de empresa continuam protegidos.

MediaProjection não garante render de app em background, múltiplos apps independentes, áudio capturado ou capacidade de controlar o conteúdo. Não há captura de áudio nesta versão.

## 4. MR, VR e hardware

- A câmera é monocular: duplicá-la para SBS não cria informação estéreo/depth real.
- ARCore requer aparelho/serviço suportados e acesso exclusivo à câmera. Sem ele há fallback para sensores 3DoF, ou touch se não houver sensor adequado.
- O renderer oferece IPD/FOV virtuais, resolução, FPS e escala. Não possui perfil óptico de todas as lentes, distorção Cardboard calibrada ou compatibilidade certificada com todos os headsets.
- Oclusão por depth, passthrough estereoscópico, ancoragem persistente e 6DoF computacional sem ARCore são próximos estágios, não recursos simulados.
- Diálogos de permissões, editor/teclado e documentação utilizam janelas nativas Android em apresentação mono. Use o telefone fora do headset para essas operações.

## 5. Não incluído nesta foundation

Marketplace, instalação silenciosa de APK, navegador/incógnito/WebXR, DRM bypass, streaming de áudio espacial, engine de física, importador de games, loader de plugins externos e execução segura de código remoto hostil. A visão histórica que menciona esses recursos foi preservada como visão, não como lista de recursos entregues.
