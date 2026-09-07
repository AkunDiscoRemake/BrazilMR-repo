# Auditoria de compatibilidade · 7 de setembro de 2026

Base: `7a6d6aa558827d6a2a2c700fb6e31a590beffd29`.

- Inventário completo de HEAD: somente `readme.md`.
- Histórico recuperado e inspecionado: cinco commits. O commit `ced91cf` continha apenas `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` e README.
- Não há Activity, módulo app, câmera, MediaPipe, OpenCV, renderer, VR, MR, input, Lua, assets ou testes antigos. A menção a OpenCV era um comentário de configuração, não uma implementação.
- Preservada a visão original em `docs/VISION.md`. Reutilizadas as convenções Kotlin DSL, AndroidX, repositórios oficiais e módulos Gradle. Versões atualizadas conjuntamente para AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 / JDK 17.
- Não se substituiu um componente funcional. A implementação começa onde o repositório estava: especificação sem código executável.
