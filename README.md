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
(Requer Java 8+. Roda numa janela 1280x720.)

> **Nota:** o preview é uma reimplementação independente em Java/Swing do jogo
> Kotlin/Canvas, para teste rápido no PC sem instalar o APK. Ele não compartilha
> código com a versão Android e pode ficar defasado conforme o jogo evolui. A
> fonte da verdade é `app/src/main/java/com/example/spaceinvaders/`.

## Releases
Para gerar um release com APK assinado, crie uma tag:
```bash
git tag v1.2.0
git push origin v1.2.0
```
O job `release` compila o APK assinado (keystore efêmero gerado no CI), cria a GitHub Release e anexa o APK automaticamente.

## CI
O workflow `.github/workflows/android.yml` gera a cada push:
- **Testes** unitários (`gradle testDebugUnitTest`) — validam a lógica pura em `GameRules`
- **Artifact** `space-invaders-debug-apk` — APK debug para Android
- **Artifact** `space-invaders-desktop-preview` — JAR executável para testar no PC
- **Release** (em tags `v*`) — APK release assinado

## IA (v1.1)
- Tiros inimigos **mirados** na posição do jogador
- Inimigos **mergulhadores** abandonam a formação em ataques em S
- Sobreviventes finais ficam mais agressivos; velocidade e cadência escalam por onda

## Power-ups e efeitos (v1.2)
Inimigos destruídos podem dropar cápsulas (14% de chance):
| Ícone | Power-up | Efeito |
|-------|----------|--------|
| R | Tiro Rápido | Cadência 2.5x por 8s |
| T | Tiro Triplo | Leque de 3 tiros por 8s |
| S | Escudo | Absorve 1 hit |
| ♥ | Vida Extra | +1 vida (máx. 5) |
| N | Nova Cósmica | Dano em tela cheia + limpa projéteis |

**Efeitos de tela:** hit-stop cinematográfico, vinheta cósmica, pulso vermelho de dano, flash, textos flutuantes.

**Fundo cósmico:** galáxias espirais girando, planeta distante com atmosfera, nebulosas profundas em deriva, estrelas cadentes, poeira estelar e estrelas cintilando — tudo se dissolvendo no vazio do infinito nas bordas da tela.
