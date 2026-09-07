# Próximos marcos (não anunciar como entregues)

## Bloqueadores para uma release pública

1. Executar o protocolo de testes em celulares intermediários reais: passthrough/crop/frontal, inferência e gestos, pose/relocalização, temperatura por 20 minutos e ciclo de vida.
2. Validar matriz de apps/OEMs em displays próprios, orientação, resize e foco. Identificar explicitamente apps compatíveis; não usar a expressão “qualquer app Android”.
3. Auditar o runtime Lua e migrar execução de scripts não confiáveis para processo isolado com quota rígida de heap/IPC. A foundation aceita desenvolvimento local, não um marketplace de código hostil.
4. Auditar uso de Accessibility, foreground services, privacidade e exigências de distribuição/target SDK atuais. As declarações e permissões já são explícitas, mas não equivalem a aprovação em loja.
5. Gerar release assinada com chave do mantenedor fora do Git; validar R8, ABI splits, tamanho, crashes e symbolication.

## Recursos incrementais, depois das medições

- Perfetto benchmarks automatizados, Android Baseline Profiles e tracing de câmera→cursor.
- Calibração de lentes/distortion por perfil Cardboard, gamepad e input contínuo de apps que cooperem com o SDK.
- Janelas em profundidades diferentes, anchors persistentes e oclusão opcional em aparelhos suportados.
- Interface de instalação/manifests versionados do SDK, debugging estruturado e plugins empacotados com verificação de proveniência.
- Áudio espacial, rede, navegador/WebXR e recursos comunitários apenas com budgets e permissões próprias.

Nenhum desses itens deve sacrificar a estabilidade ou ser simulado como recurso disponível na UI.
