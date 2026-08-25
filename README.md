# Space Invaders — Android

Jogo estilo Space Invaders com visual neon moderno, feito em Kotlin puro (Canvas API), sem dependências externas.

## Recursos
- Partículas de explosão com ondas de choque
- Glow neon (shadow layers) e screen shake
- Starfield com paralaxe e flash de tela
- Ondas progressivas, inimigos blindados (2 HP)
- Controle por toque (arraste para mover, toque para atirar)

## Controles
- **Arraste** em qualquer lugar da tela para mover a nave (movimento relativo, sem saltos)
- **Segure pressionado** para atirar automaticamente

## Pré-visualização no PC (sem instalar)
Baixe o artefato `space-invaders-desktop-preview` na aba **Actions**, extraia e rode:
```bash
java -jar space-invaders-preview.jar
```
(Requer Java 8+. É o mesmo jogo rodando numa janela 1280x720.)

## CI
O workflow `.github/workflows/android.yml` gera dois artefatos a cada push em `main`:
- `space-invaders-debug-apk` — APK para Android
- `space-invaders-desktop-preview` — JAR executável para testar no PC
