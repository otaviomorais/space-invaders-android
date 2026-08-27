# Space Invaders — Android

Jogo estilo Space Invaders com estética militar/tática, feito em Kotlin puro (Canvas API), sem dependências externas.

## Visual atual (militar)

- **Nave do jogador:** caça delta de liga gunmetal com cockpit de vidro, faixas de skin, motores com chama animada e evolução por power-ups (canhões de ponta de asa, pods laterais, blindagem, aletas).
- **Invasores:** naves de guerra angulares por variante (hunter/bomber/shield-bearer/sniper/swarmer etc.) com casco `hullShader` cacheado — sem alocação por frame.
- **Boss:** dreadnought com placas anguladas, fileiras de torres e brilho de motores; núcleo vulnerável pulsante.
- **Efeitos:** explosões cinematográficas com partículas separadas por tipo — `FIRE` (rampa branco→amarelo→laranja→vermelho), `SMOKE` cinza sob as entidades, `DEBRIS` girando, `SPARK`/`EMBER`, `FLASH` e anel `RING` de onda de choque; tudo em blending aditivo onde faz sentido.
- **HUD militar:** paleta aço/âmbar no lugar do neon — SCORE âmbar claro, WAVE âmbar, vidas vermelho dessaturado.

Demais sistemas: glow cacheado via `setLocalMatrix` (shaders unitários), `PorterDuff.Mode.ADD` sem `saveLayer`, shake, vinheta, textos flutuantes e fundo cósmico com galáxias/planeta/nebulosas.

## Recursos
- Partículas por tipo com `drag`/`grow`/`rot` e cap de 600 (fumaça abaixo, fogo/faíscas/flash acima)
- Cache de `RadialGradient`/`LinearGradient` em `shaderCache` reposicionado por `Matrix`
- Starfield com paralaxe, galáxias espirais, planeta com atmosfera
- Ondas progressivas, setores a cada 3 waves, modificadores de horda após wave 12, boss a cada 4 waves
- Controle por toque com `dragPointerId` e `findPointerIndex` (multitouch não puxa a nave)

## Controles
- **Arraste** em qualquer lugar da tela para mover a nave (movimento relativo, sem saltos)
- **Segure pressionado** para atirar automaticamente
- Botões de **dash** e **mina** (canto inferior esquerdo) e **especial** (canto inferior direito)

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

## Build local
O projeto usa **Gradle Wrapper** (Gradle 8.7). Não é necessário instalar Gradle:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew assembleRelease   # requer keystore (ver abaixo)
```

`applicationId` = `com.otaviomorais.spaceinvaders` (o `namespace`/`package` Kotlin permanece `com.example.spaceinvaders`).

## Assinatura / Releases

### Keystore fixo (recomendado)
Configure nos **Secrets** do repositório:
- `ANDROID_KEYSTORE_BASE64` — `base64` do `.keystore`/`.jks`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD` (opcional, cai no password do keystore)

O workflow decodifica o secret em `$RUNNER_TEMP/ci.keystore` e assina o release. Sem esses secrets, o CI gera um keystore efêmero (APKs não são atualizáveis entre si).

Para gerar um release com APK assinado, crie uma tag:
```bash
git tag v1.3.0
git push origin v1.3.0
```
O job `release` compila o APK assinado (fixo se houver secret, efêmero caso contrário), cria a GitHub Release e anexa o APK automaticamente.

## CI
O workflow `.github/workflows/android.yml` (usa `./gradlew`) gera a cada push:
- **Testes** unitários (`./gradlew testDebugUnitTest`) — validam a lógica pura em `GameRules`
- **Artifact** `space-invaders-debug-apk` — APK debug para Android
- **Artifact** `space-invaders-desktop-preview` — JAR executável para testar no PC
- **Release** (em tags `v*`) — APK release assinado (fixo ou efêmero)

## IA (v1.1)
- Tiros inimigos **mirados** na posição do jogador
- Inimigos **mergulhadores** abandonam a formação em ataques em S
- Sobreviventes finais ficam mais agressivos; velocidade e cadência escalam por onda

## Power-ups e efeitos (v1.2+)
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
