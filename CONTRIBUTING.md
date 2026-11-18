# Guía de Contribución a mUzVidX

¡Gracias por tu interés en contribuir a **mUzVidX**! Toda ayuda es apreciada: corrección de errores, optimización de rendimiento, traducción o nuevas funciones.

## Código de Conducta

Sé respetuoso, colaborativo y constructivo en todas las interacciones con la comunidad de mUzVidX.

## ¿Cómo Empezar?

1. **Haz un Fork** del repositorio en GitHub.
2. **Clona tu fork** localmente:
   ```bash
   git clone https://github.com/TU_USUARIO/mUzVidX.git
   cd mUzVidX
   ```
3. **Crea una rama descriptiva** para tu cambio:
   ```bash
   git checkout -b feature/nombre-de-tu-mejora
   # o para un bugfix:
   git checkout -b fix/nombre-del-error
   ```
4. **Desarrolla y prueba tus cambios** asegurando que la aplicación compile sin advertencias críticas:
   ```bash
   ./gradlew assembleDebug
   ```
5. **Haz commits limpios y descriptivos** siguiendo la convención de [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat: agregar soporte para nueva resolución de video`
   - `fix: corregir desincronización de audio en modo PiP`
   - `docs: actualizar documentación de instalación`
6. **Envía un Pull Request** explicando claramente los cambios realizados y adjunta capturas si se trata de modificaciones en la interfaz visual.

## Buenas Prácticas

- Mantén las dependencias actualizadas de forma incremental.
- Nunca agregues claves de API, tokens o credenciales personales al código fuente.
- Asegúrate de respetar el estilo de código Kotlin oficial (`ktlint` / Android Studio formatting).
