# Brazil MR Developer · API 0.2

Lua 5.2 via LuaJ 3.0.1, com uma superfície restrita. Os exemplos em `sdk/examples` são incorporados ao app. Edite cópias no menu Developer; salvar mantém um rascunho privado entre reinícios, mas não executa nem concede capabilities.

## Modelo de execução

Uma instância por principal (id do app + SHA-256 do código). GAME e WINDOW usam o mesmo runtime e WindowManagerXR. Máximo de três runtimes simultâneos, quatro janelas por runtime, doze janelas no workspace. Código executa em worker serial com fila limitada; nenhuma função Lua roda na thread da UI. Operações do host são encaminhadas à main thread com timeout e cancelamento de tarefas ainda não executadas.

```lua
assert(zxr.app.type == "WINDOW") -- ou GAME; somente leitura
print(zxr.app.id, zxr.app.windowId)
```

Um manifesto declara `id`, `title`, `type`, `entry` e `permissions`. Declarar não concede. O editor cria `developer.local`; alterar o código muda a identidade e não herda grants de outra versão.

## Coordenadas e propriedade

- UI: `x`, `y`, `width`, `height` entre 0 e 1, relativos à área de conteúdo da superfície própria (não à tela). Desenho e hit testing são recortados por essa área.
- Janelas: frações do workspace, não pixels Android; resize limitado pelo WindowManager.
- Landmarks: câmera orientada, normalizada; z é relativo ao modelo, **não uma distância física**. O cursor faz uma transformação adicional de crop/mirror/projeção, portanto não é correto tratar um landmark bruto como coordenada global da UI.
- Cenário: metros relativos à origem da sessão. Sem ARCore é um espaço 3DoF, não um mapa físico persistente.
- IDs são números atribuídos pelo host. IDs de outra identidade são recusados, inclusive quando `unsafe_execution` está concedida.

## zxr.ui

```lua
local title = zxr.ui.text {text="Olá", x=.05, y=.1, width=.9, height=.2}
local button = zxr.ui.button {
  text="Selecionar", x=.1, y=.4, width=.7, height=.25,
  color="#FFFFFF", background="#794DDA", fontSize=22
}
zxr.ui.on(button, "click", function()
  zxr.ui.set(title, {text="Selecionado"})
end)
```

| Função | Comportamento |
|---|---|
| `create(kind, properties)` | Cria `button`, `text` ou `panel`; retorna ID. |
| `button(properties)`, `text(properties)`, `panel(properties)` | Atalhos de criação. |
| `set(id, properties)` | Modifica propriedades do elemento próprio. |
| `move(id, x, y)` | Move dentro do conteúdo. |
| `resize(id, width, height)` | Redimensiona; mínimo .02 por dimensão. |
| `remove(id)` | Remove e cancela subscriptions desse elemento. |
| `on(id, "click", function)` | Callback de ativação, incluindo touch, pinça e acessibilidade da UI. |
| `setGlobalVisible(boolean)` | **unsafe_execution**. Oculta/mostra a UI global. |
| `recenterGlobal(x, y)` | **unsafe_execution**. Reposiciona o plano global; não injeta input Android. |

Propriedades: `text` (até 2048 caracteres), `x`, `y`, `width`, `height`, `visible`, `color`, `background` (`#RRGGBB`), `fontSize` (12–64) e `windowId` (na criação; padrão: janela principal). Até 128 elementos por principal e 1024 no host.

## zxr.window

`zxr.window.id` é a janela principal. Nas funções com ID opcional, sua omissão usa essa janela.

| Função | Resultado |
|---|---|
| `create(title, type?)` | Cria outra janela própria no mesmo runtime; retorna ID. |
| `get(id?)` | Snapshot `{id, title, type, x, y, width, height, focused, minimized, pose}`; `pose={x,y,z,width,height,yaw}` está em metros/graus. |
| `setTitle(title, id?)` | Título de 1–120 caracteres. |
| `move(x, y, id?)` | Move e limita às bordas do workspace. |
| `resize(width, height, id?)` | Tamanho normalizado, limitado pelo host. |
| `place(x, y, z, yaw?, id?)` | Posição em metros e inclinação em graus da superfície própria. |
| `distance(metres, id?)`, `scale(factor, id?)` | Aproximar/afastar e redimensionar sem deformar o conteúdo. |
| `focus(id?)`, `minimize(id?)`, `close(id?)` | Ciclo de vida de janela, sem outro executor. |
| `on("focus", callback)` | Boolean: alguma janela deste principal recebeu/perdeu foco. |
| `on("minimized", callback)` | Boolean: a janela principal foi minimizada/restaurada. |

Fechar a janela principal encerra o runtime e suas janelas/elementos/objetos. Fechar uma filha não cria outro contexto. Não há API Lua para controlar janelas de outros apps.

## zxr.hand — capability hand_tracking

```lua
local right = zxr.hand.get("right") -- "left" também
if right.present then
  -- Array Lua é 1-based: landmark MediaPipe 8 = landmarks[9].
  local tip = right.landmarks[9]
  print(tip.x, tip.y, tip.z)
end
zxr.hand.on("update", function() end)
```

Retorna cópia: `{present, confidence?, landmarks?, normal?}`. Há 21 landmarks `{x,y,z}` e normal de palma `{x,y,z}`. `confidence` corresponde à confiança da classificação de lateralidade fornecida pelo modelo, não a uma medição de precisão absoluta. Quando a mão está ausente, as posições antigas não são expostas como atuais. Mudar a cópia não altera o tracking do sistema. A leitura e a entrega de eventos verificam a capability novamente após revogação.

## zxr.input — capability input

```lua
zxr.input.on("pointer", function(event)
  -- Dentro do próprio conteúdo, não coordenadas globais de outros apps.
  print(event.x, event.y, event.action, event.source)
end)
zxr.input.on("click", function() print("ativação de botão próprio") end)
```

`action`: `move`, `down`, `up`, `cancel`. `source`: `touch`, `hand` ou `gaze`; a ativação por nós de acessibilidade também chama o evento de clique do botão. Moves podem ser reduzidos por backpressure; eventos não são um loop de renderização. Saturação de eventos críticos interrompe o runtime em vez de deixar uma interação pressionada indefinidamente. Não existe função para sintetizar cliques arbitrários em outros apps a partir de Lua.

## zxr.vr / zxr.mr

- `isActive()` → boolean, sem capability.
- `enter()` → **unsafe_execution**, altera o modo global pelo host.
- Observe transições com `zxr.system.on("mode", callback)`; o callback recebe `"MR"` ou `"VR"`.

SBS e modo VR são relacionados, mas não idênticos: MR também pode usar SBS; VR pode ser visualizado em mono pelos ajustes. Entrar em VR pelo fluxo principal ativa SBS por padrão. FOV é virtual, não uma alteração física da câmera/lente.

## zxr.game

- `isGame()` → boolean.
- `hud(properties)` → painel de UI, permitido somente em GAME.
- `on("update", callback)` → tempo monotônico em segundos, atualmente até 10 Hz no controlador Android, apenas com UI/janela visível. Não é um motor de física, áudio ou renderização de jogos AAA.

## zxr.scenario — capability scenario

O namespace é `nil` sem permissão. Ao conceder, o host atualiza a tabela; o evento `system.permissions` permite adaptar a cena. Orbit demonstra esse fluxo; scripts que só verificam permissões na inicialização devem ser reiniciados. Uma referência antiga continua sendo validada em cada chamada depois de revogação.

```lua
if zxr.scenario then
  local id = zxr.scenario.spawn {x=0, y=.6, z=-2, size=.12, color="#B18AFF"}
  zxr.scenario.move(id, .2, .6, -2)
  zxr.scenario.on("tracking", function(status) print(status) end)
  zxr.scenario.remove(id)
end
```

`spawn` exige GAME. Objetos são cubos wireframe simples: x/y entre -5 e 5 m, z de -10 a -.3 m, tamanho de .01 a 1 m. Até 32 por principal e 64 no host. Sem oclusão por profundidade, física, reconhecimento de objetos ou âncoras persistentes nesta versão. Revogar `scenario` remove os objetos desse principal.

## zxr.system

- `version` → `"0.2.0"`.
- `mode()` → `"MR"` / `"VR"`.
- `time()` → relógio monotônico do host, em milissegundos; não é data civil.
- `spatialStatus()` → descrição de disponibilidade/relocalização/3DoF/6DoF.
- `hasPermission(name)` → boolean, não concede permissões.
- `on("mode", callback)`, `on("spatial", callback)` → eventos de ambiente.
- `on("permissions", callback)` → concessões/revogações deste principal foram atualizadas; consulte `hasPermission`.

## Segurança e quotas

Não disponíveis: `io`, `os`, `luajava`, `require`, `package`, `load`, `loadfile`, `dofile`, `debug`, `collectgarbage`, `coroutine`, `getmetatable`, `setmetatable`, `rawset`, bytecode e recursos de classes Java. `print` vai ao console local, truncado e limitado por um buffer circular.

- Somente código texto até 64 KiB; identidade conferida contra o conteúdo antes da execução.
- Até 100 mil instruções por chamada. Deadline de 500 ms na inicialização (inclui espera pelo host) e 50 ms por evento.
- Erros de quota não podem ser engolidos por `pcall`/`xpcall`.
- Profundidade de chamadas 64; até 64 subscriptions; strings intermediárias limitadas a 16 KiB; tabelas diretamente observadas têm teto de 4096 entradas.
- `string.rep` e `table.concat` são limitados; `string.find` é literal. Não disponíveis: `string.dump`, `format`, `match`, `gmatch`, `gsub`.
- Não há isolamento rígido de heap: grafos persistentes de tabelas/closures ainda podem consumir memória. **Não use esta versão para executar código remoto hostil.** Distribuição comunitária exige processo isolado, quotas de heap e auditoria adicional.
- `unsafe_execution` **não** desliga o sandbox; concede apenas as operações globais descritas aqui. Permissões Android nunca são concedidas por Lua.

## Plugins

`core/plugin/PluginSystem` aceita plugins Kotlin compilados e revisados junto com o host. Manifesto: id, versão, tipo (`TRACKING`, `FILTER`, `RENDERER`, `LAYOUT`, `API`, `DEVELOPER_TOOL`) e capabilities. Grants são próprios, não herdados de scripts. Antes de iniciar, todas as capabilities devem estar concedidas; revogação chama stop. Não existe loader externo de DEX/.so/.jar. Um plugin nativo compilado no app é código confiável, não uma sandbox para terceiros.
