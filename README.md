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

## Releases
Para gerar um release com APK assinado, crie uma tag:
```bash
git tag v1.2.0
git push origin v1.2.0
```
O job `release` compila o APK assinado (keystore efêmero gerado no CI), cria a GitHub Release e anexa o APK automaticamente.

## CI
O workflow `.github/workflows/android.yml` gera a cada push:
- **Artifact** `space-invaders-debug-apk` — APK debug para Android
- **Artifact** `space-invaders-desktop-preview` — JAR executável para testar no PC
- **Release** (em tags `v*`) — APK release assinado

## IA (v1.1)
- Tiros inimigos **mirados** na posição do jogador
- Inimigos **mergulhadores** abandonam a formação em ataques em S
- Sobreviventes finais ficam mais agressivos; velocidade e cadência escalam por onda
