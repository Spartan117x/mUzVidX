# Changelog - mUzVidX

Todas las notas notables de cambios para las diferentes versiones de mUzVidX.

---

## [v1.0.2] - 2026-11-20 — Android TV Edition

### 🇪🇸 Español

#### 📺 Soporte Nativo para Android TV & Google TV
- **Lanzador Leanback**: Incorporación de `LEANBACK_LAUNCHER` e icono de banner panorámico oficial (`banner_tv.png`) para visualización directa en la pantalla de inicio de Android TV / Google TV.
- **Navegación 100% con Control Remoto (D-Pad)**: Navegación completa mediante cruceta direccional sin depender de mouse o emulación táctil.
- **Indicadores de Foco de Alto Contraste**: Estados de foco visualmente reforzados con bordes brillantes y resplandores en botones, pestañas, elementos de lista y controles multimedia.
- **Mini Reproductor Inteligente**: Presionar el botón `Atrás` en el reproductor minimiza la reproducción a la barra inferior sin cortar el streaming, facilitando la navegación continua.
- **Gestión de Foco en Búsqueda**: El campo de búsqueda despliega el teclado de Android TV y sitúa el foco automáticamente en el primer resultado tras la búsqueda.

#### 🎬 Streaming 1080p Nativo Inmutable (Solución Definitiva HLS)
- **Extracción HLS Maestro (`VISIONOS` + `visitorData`)**: Implementación en `YouTubeService.kt` de extracción de manifiestos HLS maestros oficiales de GoogleVideo, superando la restricción de 15 MB de streams adaptativos directos.
- **Eliminación del Corte a los 62 Segundos**: Se erradica el bloqueo `HTTP 403 Forbidden` a los 60-62 segundos de reproducción.
- **Persistencia de Calidad**: La resolución 1080p seleccionada por el usuario (`userSelectedResolution`) permanece inalterada en el primer video y en todos los videos siguientes de la cola sin degradarse a 360p.
- **Conmutación Dinámica de Calidad**: Cambio instantáneo entre calidades (1080p, 720p, 480p, 360p, 240p) mediante `TrackSelectionOverride` en ExoPlayer sin reiniciar la reproducción.

#### 🧪 Pruebas Verificadas en Hardware Real (TCL 4K Smart TV `192.168.1.69:5555` - Android 14)
- **Foco Inicial**: Verificado que al iniciar la aplicación, el foco se posiciona correctamente en la barra de modos.
- **Navegación Bidireccional**: Pruebas completas de desplazamiento horizontal y vertical entre pestañas (*Canciones*, *Videos*, *Modo Libre*), lista de resultados y barra de navegación inferior.
- **Búsqueda**: Búsqueda asistida en TV con posicionamiento de foco automático en los elementos del RecyclerView.
- **Reproducción Continua 1080p**: Pista de prueba reproducida durante más de **2 minutos (135 segundos)** continuos a 1080p sin cortes ni caída de calidad.
- **Transición de Cola**: Avance de pista verificado con `KEYCODE_MEDIA_NEXT`; las pistas subsiguientes cargan y reproducen inmediatamente en 1080p (`targetHeight=1080`).

---

### 🇬🇧 English

#### 📺 Native Android TV & Google TV Support
- **Leanback Launcher**: Integrated `LEANBACK_LAUNCHER` and official widescreen banner (`banner_tv.png`) for seamless presentation on the Android TV / Google TV home screen.
- **100% Remote Control Navigation (D-Pad)**: Full directional pad navigation without requiring air-mouse or touch inputs.
- **High-Contrast Focus Indicators**: Specially styled focus states featuring bright cyan/magenta halos on all buttons, tabs, cards, and media controls.
- **Smart Mini-Player**: Pressing `Back` while playing in full-screen seamlessly minimizes to the persistent bottom mini-player without interrupting audio/video playback.
- **Search Focus Management**: Activating the search bar summons the Android TV IME, and submitting a query automatically focuses the first item in the results list.

#### 🎬 Immutable 1080p Native Streaming (Definitive HLS Fix)
- **Master HLS Extraction (`VISIONOS` + `visitorData`)**: Implemented GoogleVideo master HLS manifest retrieval in `YouTubeService.kt`, overcoming the 15 MB download ceiling inherent to direct adaptive iOS formats.
- **Eliminated 62-Second Playback Wall**: Completely eliminated `HTTP 403 Forbidden` errors around seconds 60-62 of playback.
- **Quality Persistence Across Queue**: User-selected resolution (`userSelectedResolution`) remains strictly locked in 1080p for the initial track and all subsequent tracks in the queue without downgrading to 360p.
- **Seamless Quality Switching**: Instant resolution switching (1080p, 720p, 480p, 360p, 240p) using ExoPlayer `TrackSelectionOverride` without resetting playback time.

#### 🧪 Hardware Verification (TCL 4K Smart TV `192.168.1.69:5555` - Android 14)
- **Initial Focus**: Verified application launch assigns focus reliably to the top navigation mode bar.
- **Bidirectional D-Pad Navigation**: Verified seamless lateral and vertical navigation across tabs (*Canciones*, *Videos*, *Modo Libre*), search results, and bottom navigation items.
- **Search Flow**: Verified query submission automatically transfers focus to search result rows.
- **Continuous 1080p Playback**: Test track streamed continuously for over **2 minutes (135 seconds)** at 1080p without buffering stalls, errors, or resolution drop.
- **Queue Transition**: Skipping to subsequent tracks via `KEYCODE_MEDIA_NEXT` verified that each new video automatically loads and plays at 1080p (`targetHeight=1080`).

---

## [v1.0.1] - 2026-11-19
- **Detección Exacta de Canales Oficiales**: Distinción estricta entre videos de canales oficiales verificados ("Video Oficial") y videos de la comunidad ("Video").
- **Conmutación en Vivo Audio/Video**: Botón de modo audio habilitado para videos sin cortes de señal.
- **Reproducción Determinista 1:1**: Eliminadas sustituciones heurísticas automáticas.
- **Metadatos ID3**: Inyección completa de etiquetas (Título, Artista, Álbum) en descargas de audio.

---

## [v1.0.0] - 2026-11-18
- Lanzamiento inicial con streaming de audio y video, visualizador FFT de 36 bandas, ecualizador de 5 bandas, extractor Innertube y conversión a MP3 con FFmpeg.
