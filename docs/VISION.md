🇧🇷 BrazilMR V2

Uma plataforma VR/MR para Android, feita para transformar smartphones em uma experiência espacial acessível.

BrazilMR V2 é uma plataforma experimental de Virtual Reality (VR) e Mixed Reality (MR) para Android, projetada para funcionar com smartphones comuns, incluindo dispositivos usados com headsets do tipo Cardboard/VR Box.

O projeto combina VR estereoscópico, head tracking, 6DoF computacional, hand tracking, Web Apps e um navegador próprio em uma única experiência.

«🚧 Status: Em desenvolvimento

A primeira versão é focada na infraestrutura da plataforma. Jogos serão adicionados somente depois da primeira release.»

---

✨ Recursos

🥽 VR

- Renderização estereoscópica
- Side-by-side
- Ajuste de IPD
- Ajuste de FOV
- Correção de distorção para lentes
- Head tracking
- Suporte a 3DoF
- 6DoF computacional quando possível
- Recenter de orientação
- Recuperação automática de tracking

🖐️ Hand Tracking

Sistema de hand tracking otimizado para dispositivos móveis.

Detecta:

- Mão esquerda e direita
- Palma
- Dedos
- Articulações
- Orientação das mãos
- Gestos básicos

Gestos planejados:

- ☝️ Apontar
- 🤏 Pinça
- ✋ Mão aberta
- ✊ Mão fechada
- Selecionar
- Arrastar
- Voltar
- Abrir menu

O sistema utiliza processamento adaptativo para reduzir latência, consumo de bateria e carga de CPU/GPU.

---

🌎 Mixed Reality

O BrazilMR V2 utiliza a câmera do smartphone para criar experiências de MR.

Inclui:

- Camera passthrough
- Objetos virtuais no ambiente real
- Tracking espacial
- Interação com mãos
- Calibração espacial
- Recentragem
- Recuperação de tracking

Quando determinado recurso não estiver disponível no dispositivo, o sistema deve utilizar automaticamente um fallback apropriado.

---

🌐 Navegador

O BrazilMR V2 possui um navegador integrado pensado para utilização em VR/MR.

Recursos

- Múltiplas abas
- Favoritos
- Histórico
- Downloads
- Pesquisa
- Zoom
- Navegação por gestos
- Teclado
- Gerenciamento de permissões
- Limpeza de dados

🕵️ Modo Anônimo

O navegador possui um modo anônimo que evita manter localmente:

- Histórico
- Cookies persistentes da sessão
- Formulários
- Dados temporários após o encerramento das abas anônimas

O modo anônimo não significa anonimato completo na Internet.

---

📱 Web Apps

Sites podem ser instalados como aplicativos dentro do BrazilMR V2.

Cada Web App pode possuir:

- Nome
- Ícone
- URL
- Armazenamento isolado
- Permissões
- Tela própria
- Configurações individuais

Exemplo:

Web Apps
├── YouTube
├── Google
├── Discord
├── Roblox
└── Meus aplicativos

A arquitetura também foi planejada para futuras experiências WebXR.

---

🎮 Input

O BrazilMR V2 pode trabalhar com diferentes métodos de entrada:

- 👁️ Head tracking
- 🖐️ Hand tracking
- 📱 Touch
- 🎮 Gamepads
- 📡 Controles Bluetooth

O sistema de input foi projetado para permitir que diferentes métodos sejam utilizados sem modificar o núcleo da aplicação.

---

⚡ Performance

Performance é uma das prioridades do BrazilMR V2.

O sistema possui arquitetura preparada para:

- Resolução dinâmica
- FPS configurável
- Frame pacing
- Processamento assíncrono
- Redução de latência
- Controle de carga de CPU/GPU
- Gerenciamento térmico
- Suspensão de processos inativos

Modos

Performance Mode

Prioriza:

- FPS
- Latência
- Responsividade

Battery Saver

Prioriza:

- Autonomia
- Menor temperatura
- Menor consumo

---

🧠 Tracking

O sistema pode combinar diferentes fontes de informação:

Camera
   │
   ├── Visual Tracking
   │
   └── Hand Tracking
          │
          ▼
      Tracking Engine
          ▲
          │
Sensors ──┘
   │
   ├── Gyroscope
   ├── Accelerometer
   └── Magnetometer

Quando possível, o sistema combina sensores e visão computacional para melhorar estabilidade e precisão.

Se o 6DoF não estiver disponível, o BrazilMR V2 pode utilizar 3DoF automaticamente.

---

🏗️ Arquitetura

BrazilMR V2
│
├── Core
│
├── VR Engine
│
├── MR Engine
│
├── Tracking
│   ├── Head Tracking
│   ├── 3DoF
│   ├── 6DoF
│   └── Hand Tracking
│
├── Input
│   ├── Touch
│   ├── Bluetooth
│   ├── Gamepad
│   └── Hand Input
│
├── Browser
│
├── Web Apps
│
├── Launcher
│
├── Settings
│
├── Performance
│
└── Future Games API

A arquitetura é modular para permitir que novos recursos sejam adicionados sem precisar reescrever o núcleo da plataforma.

---

🚀 Roadmap

V1 — Foundation

- [ ] Launcher VR/MR
- [ ] VR estereoscópico
- [ ] Head tracking
- [ ] 3DoF
- [ ] 6DoF computacional
- [ ] Hand tracking otimizado
- [ ] Input system
- [ ] Navegador
- [ ] Modo anônimo
- [ ] Web Apps
- [ ] Mixed Reality
- [ ] Calibração
- [ ] Sistema de performance
- [ ] Configurações
- [ ] Estabilidade e otimização

V2 — Games & XR

- [ ] Sistema de jogos
- [ ] API para jogos
- [ ] Suporte WebXR aprimorado
- [ ] Experiências interativas
- [ ] Multiplayer
- [ ] SDK para desenvolvedores

V3 — Ecosystem

- [ ] BrazilMR SDK
- [ ] Ferramentas de criação
- [ ] Repositório de aplicativos
- [ ] Tracking avançado
- [ ] Recursos sociais
- [ ] Ecossistema de desenvolvedores

---

📦 Requisitos

O BrazilMR V2 foi projetado para Android moderno e tenta utilizar os recursos disponíveis no dispositivo.

Recursos detectados automaticamente:

- Giroscópio
- Acelerômetro
- Magnetômetro
- Câmera
- GPU
- Taxa de atualização
- Resolução
- APIs disponíveis

Dispositivos sem determinados sensores continuarão funcionando com recursos reduzidos quando possível.

---

🔧 Filosofia do projeto

O BrazilMR V2 busca tornar VR/MR mais acessível, utilizando hardware que muitas pessoas já possuem.

Em vez de exigir imediatamente um headset dedicado, a plataforma busca aproveitar:

📱 Smartphone + 🥽 Headset simples + 🖐️ Tracking + 🌐 Web = XR acessível

A prioridade é construir uma base sólida primeiro.

Jogos ficam para depois. A plataforma vem primeiro.

---

🤝 Contribuindo

Contribuições são bem-vindas.

Áreas que podem receber contribuições:

- Tracking
- Hand tracking
- Renderização
- Performance
- Android
- Web Apps
- Browser
- UI/UX
- Mixed Reality
- Documentação
- Testes em diferentes dispositivos

---

📜 Licença

A licença será definida conforme o desenvolvimento do projeto.

---

🇧🇷 BrazilMR V2

VR/MR acessível. Android como plataforma. O smartphone como headset.
