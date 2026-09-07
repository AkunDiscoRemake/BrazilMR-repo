# Dependências, modelo e origem

O repositório original não definiu uma licença para o projeto; esta reconstrução não atribui uma nova licença ao código do mantenedor. Revise licenças/avisos antes de distribuir.

| Componente | Versão / origem | Observação |
|---|---|---|
| Android Gradle Plugin | 8.7.3 / Google Maven | Toolchain de build |
| Gradle | 8.9 / gradle.org | Wrapper padrão obtido do repositório oficial Gradle |
| Kotlin | 2.0.21 | Núcleo e Android |
| AndroidX CameraX | 1.4.1 | Preview/análise e lifecycle oficiais |
| MediaPipe Tasks Vision | 0.10.20 | Adapter CPU, VIDEO síncrono, RGBA ByteBuffer |
| ARCore SDK | 1.46.0 | Opcional; sujeito a suporte/instalação/termos do serviço |
| LuaJ | 3.0.1 / org.luaj:luaj-jse | API selecionada; JsePlatform/luajava/IO/OS não carregados |
| Robolectric | 4.14.1 | Testes JVM com gráficos nativos, não certificação em hardware |
| One Euro | algoritmo de Casiez, Roussel e Vogel, CHI 2012 | Implementação própria em Kotlin; parâmetros em Hz e unidades normalizadas |

## Hand Landmarker

URL versionada oficial:

`https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`

- Tamanho esperado: 7.819.105 bytes.
- SHA-256: `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`.
- Não é versionado no Git. O build verifica o hash antes de usar o arquivo.
- A identidade do modelo é independente do hash dos scripts Lua.
- Consulte model card, licença e termos do MediaPipe/Google para redistribuição. Não há treinamento, download de modelos em runtime, coleta de frames ou envio de landmarks ao servidor.

AndroidX, MediaPipe e Gradle publicam seus avisos/licenças nos respectivos artefatos e repositórios. Dependências transitivas mantêm seus termos. O SDK ARCore não torna o serviço Google Play Services for AR parte deste repositório.
