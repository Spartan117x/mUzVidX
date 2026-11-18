# mUzVidX 🎵🎬

<p align="center">
  <img src="mUzVidX-icono.jpg" alt="mUzVidX Logo" width="160" style="border-radius: 28px;" />
</p>

<p align="center">
  <b>Reproductor y Descargador Multimedia de Alta Fidelidad para Android</b><br>
  <i>Streaming y descarga en línea de Audio y Video con Visualizador FFT en tiempo real, Ecualizador de 5 bandas, Modo PiP y Conversión MP3 con FFmpeg</i>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-brightgreen.svg" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Kotlin-1.9.0-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Media3_ExoPlayer-1.5.1-blue.svg" alt="ExoPlayer" />
  <img src="https://img.shields.io/badge/FFmpeg_Kit-6.1.4-red.svg" alt="FFmpeg" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-orange.svg" alt="License GPL v3" />
</p>

---

## 🌟 Características Principales

### 🎧 Reproducción y Streaming Dual (Audio & Video)
- **Cambio Dinámico Audio / Video**: Switch en vivo para alternar entre modo sólo audio (óptimo para ahorro de batería/datos) y modo video de alta definición sin perder la posición de reproducción.
- **Reproducción Continua en Segundo Plano**: Soporte completo para reproducción en background y control desde la barra de notificaciones del sistema (`MediaSessionService`).
- **Modo Picture-in-Picture (PiP)**: Minimiza el video a una ventana flotante para continuar viendo tus contenidos mientras usas otras aplicaciones.
- **Vistas Inteligentes (Vertical y Horizontal 50/50)**: En orientación horizontal, activa una vista dividida sincronizada: 50% video HD y 50% visualizador espectral de audio.

### 📊 Visualizador de Audio Espectral FFT en Tiempo Real
- **36 Bandas de Frecuencia**: Análisis acústico reactivo dividido en **Graves** (12 bandas), **Medios** (12 bandas) y **Agudos** (12 bandas).
- **Física de Cápsulas de Pico (Peak Caps)**: Caída por gravedad balística y desvanecimiento suave para una respuesta visual orgánica y precisa con cada beat musical.
- **Modo Minimalista**: Opción para alternar entre la carátula HD del álbum y el visualizador reactivo de ondas sonoras.

### 🎚️ Ecualizador Paramétrico de 5 Bandas & Presets
- Control de ganancia en decibelios en 5 rangos de frecuencia clave (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz).
- Presets optimizados: Normal, Bass Boost, Rock, Pop, Jazz, Clásica, Vocal y Custom.

### 📥 Descargas y Extracción Multimedia
- **Múltiples Resoluciones de Video**: Selector de resolución (1080p, 720p, 480p, 360p) con detección inteligente de streams progresivos y muxed para garantizar descargas sin fallos.
- **Extractor SoundCloud & YouTube Innertube**: Búsqueda limpia y sin anuncios con navegación por artistas, álbumes y canciones.
- **Conversor MP3 Automático**: Motor de transcodificación integrado con **FFmpegKit 6.1.4**, permitiendo convertir cualquier descarga de video o audio en un archivo MP3 limpio con tags ID3.

---

## 🏗️ Arquitectura Técnica

```
com.muzvidx.app/
├── data/
│   ├── model/             # Modelos de datos (Song, Video, StreamFormat, Playlist)
│   └── repository/        # Repositorios de red y almacenamiento local
├── extractor/
│   ├── YouTubeService.kt   # Cliente Innertube público y parser de streams
│   └── SoundCloudService.kt# Cliente API desacoplado
├── player/
│   ├── PlayerService.kt   # Servicio en segundo plano con Media3 ExoPlayer
│   └── VisualizerManager.kt# Captura de forma de onda y FFT con AudioEffect
├── ui/
│   ├── audio/             # Pantalla de reproductor de audio y visualizador
│   ├── video/             # Reproductor de video ExoPlayer con modo PiP
│   ├── library/           # Biblioteca oficial y favoritos
│   ├── search/            # Búsqueda libre y filtrado
│   ├── downloads/         # Gestor de descargas y conversor FFmpeg
│   └── equalizer/         # Interfaz de ecualizador de 5 bandas
└── view/
    └── AudioVisualizerView.kt # Canvas personalizado de alto rendimiento (60fps)
```

---

## 🚀 Compilación e Instalación

### Requisitos Previos
- **Android Studio** Hedgehog / Ladybug o superior
- **JDK 17**
- **Android SDK** API 36 (Mínimo: Android 8.0 Oreo - API 26)
- **NDK** con soporte para arquitecturas `arm64-v8a` y `armeabi-v7a`

### Pasos para compilar desde terminal

1. Clonar el repositorio:
   ```bash
   git clone https://github.com/TU_USUARIO/mUzVidX.git
   cd mUzVidX
   ```

2. Compilar APK en modo Debug:
   ```bash
   ./gradlew assembleDebug
   ```

3. El archivo APK generado estará en:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

4. Instalar directamente en un dispositivo conectado mediante ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 🔒 Privacidad y Seguridad

- **Cero Telemetría Invasiva**: No recopila datos de usuario ni transmite identificadores de hardware.
- **Sin Claves Privadas en el Repositorio**: El código no incluye credenciales hardcodeadas ni tokens de API privados.
- **Almacenamiento Local**: Todo el historial de reproducciones, favoritos y descargas se guarda estrictamente en el dispositivo del usuario.

Para reportar problemas de seguridad, consulta nuestra [Política de Seguridad](SECURITY.md).

---

## 🤝 Contribuciones

¡Las contribuciones de la comunidad son bienvenidas! Por favor lee las [Directrices de Contribución](CONTRIBUTING.md) antes de enviar un Pull Request.

---

## 📄 Licencia

Este proyecto está licenciado bajo la **GNU General Public License v3.0** (GPL-3.0). Consulta el archivo [LICENSE](LICENSE) para más detalles.
