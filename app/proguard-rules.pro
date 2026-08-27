# Space Invaders - regras R8/ProGuard
# O jogo usa apenas Canvas/Paint/SharedPreferences sem reflexao, entao as
# regras padrao do AGP ja bastam. Mantemos o pacote por seguranca.

-keep class com.example.spaceinvaders.** { *; }

# Vibrator/ToneGenerator sao acessados via getSystemService, sem reflexao,
# mas mantemos para evitar surpresas em otimizacao agressiva.
-keep class android.os.Vibrator { *; }
