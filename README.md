# Space Invaders — Android

Jogo estilo Space Invaders com visual neon moderno, feito em Kotlin puro (Canvas API), sem dependências externas.

## Recursos
- Partículas de explosão com ondas de choque
- Glow neon (shadow layers) e screen shake
- Starfield com paralaxe e flash de tela
- Ondas progressivas, inimigos blindados (2 HP)
- Controle por toque (arraste para mover, toque para atirar)

## Build local
```bash
gradle assembleDebug
```

## CI
O workflow `.github/workflows/android.yml` compila o APK a cada push em `main` e publica o artefato na aba **Actions**.
