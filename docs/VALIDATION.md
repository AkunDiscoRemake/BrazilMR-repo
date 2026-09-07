# Estado de validação · Brazil MR Foundation 0.1

## Build Android

A verificação usa GitHub Actions com JDK Temurin 17, Gradle 8.9, Kotlin 2.0.21 e SDK Android 35. O modelo Hand Landmarker oficial é baixado no build e verificado pelo SHA-256 documentado em `THIRD_PARTY.md`.

- **Baseline aprovado:** commit `8f25ba055f71d562fcdea27163f881ed7df8eda3`, execução [34085307873](https://github.com/AkunDiscoRemake/BrazilMR-repo/actions/runs/34085307873).
- Nessa execução passaram 27 testes Core, 18 testes Lua e 5 testes JVM de UI; build debug e lint também concluíram com sucesso.
- **Validação final aprovada:** commit `b65f52fadd7fdc4785cb3c8d7b9bee3ed27b31de`, execução [34126069326](https://github.com/AkunDiscoRemake/BrazilMR-repo/actions/runs/34126069326), concluída em 4 min 32 s.

### Resultado final observado

| Verificação | Resultado |
|---|---|
| Core JVM | 29 aprovados, 0 falhas |
| Lua JVM | 18 aprovados, 0 falhas |
| UI/estado Android em Robolectric | 7 aprovados, 0 falhas, 0 erros, 0 ignorados |
| Total | **54 testes aprovados** |
| `assembleDebug` | APK gerado |
| `lintDebug` | Concluído sem erros bloqueantes; não implica ausência de todos os warnings |
| Modelo MediaPipe | Download oficial e SHA-256 verificados pelo build |

[Baixar BrazilMR-debug](https://github.com/AkunDiscoRemake/BrazilMR-repo/actions/runs/34126069326/artifacts/10020265297) · [Relatórios e imagens da UI](https://github.com/AkunDiscoRemake/BrazilMR-repo/actions/runs/34126069326/artifacts/10020263329)

O artefato do APK tem aproximadamente 30,7 MB. Os commits de documentação posteriores não alteram o código Android validado. A execução também emitiu um aviso sobre migração de runtime Node de algumas actions; o job terminou com sucesso.

Comandos da CI:

```sh
./gradlew --no-daemon :core:test :lua:test \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

A execução publica:

- `BrazilMR-debug`: APK debug instalável para testes, não release de produção assinada pelo mantenedor.
- `verification-reports`: log de build, lint, resultados JUnit e imagens das páginas renderizadas em testes JVM.

Artefatos têm retenção de sete dias. Abra a execução no GitHub, role até **Artifacts** e baixe `BrazilMR-debug`. O sandbox não consegue baixar os binários do servidor de artefatos; isso não impede o download pela interface do GitHub no navegador do usuário.

## O que esses testes comprovam

Regras e geometria do núcleo, inversa de projeção, deadlines/cadência, arbitragem de input, ownership de janelas, default deny e revogação, isolamento das APIs Lua, quotas e limpeza de recursos próprios. Os testes de UI usam Canvas/Skia nativo no Robolectric; verificam layout, máscaras de composição, recuperação, modal, permissões de sessão e persistência de settings/rascunho.

A CI compila os adapters Android reais contra as dependências declaradas. Não substitui suas dependências por classes fictícias de MediaPipe, CameraX ou ARCore.

## O que ainda NÃO foi validado

- Execução em aparelho físico ou emulador Android. O smoke test instrumentado foi escrito, mas não executado nesta sessão.
- Qualidade/latência de MediaPipe em imagens reais, crop/mirror/rotação da câmera, gestos sob oclusão e diferentes condições de luz.
- Pose/relocalização ARCore, sensores e alinhamento óptico de headsets reais.
- Matriz de apps/OEMs em displays secundários, input Accessibility, foco, letterboxing e MediaProjection em hardware.
- Temperatura, consumo de energia, PSS, GC e FPS sustentado em celulares intermediários.
- Release assinada, R8/release, aprovação de loja ou isolamento rígido de heap para scripts hostis.

Os testes de performance do núcleo verificam **política de adaptação**, não métricas físicas de um celular. Screenshots de UI não são evidência de hand tracking ou passthrough funcionando.

## Limites de produto

A entrega é uma **foundation nativa experimental**, não um XR OS de produção ou um mecanismo universal de virtualização de apps. Consulte `ANDROID_LIMITATIONS.md`, `TESTING.md` e `ROADMAP.md` antes de distribuir ou ampliar permissões/recursos.
