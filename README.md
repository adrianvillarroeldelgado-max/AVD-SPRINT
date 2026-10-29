# AVD Sprint (Android)

App de evaluación de la velocidad para entrenadores, con la misma imagen y el mismo sistema de publicación que AVD jump.

## Qué hace

- **1 · Deportistas:** plantel, informe completo, informes PDF (una o todas las pruebas), historial, exportación CSV y copia de seguridad.
- **2 · Perfil fuerza-velocidad horizontal** (método de Samozino, como My Sprint): parciales desde vídeo a 240 fps o a mano, F0, V0, Pmax, pendiente F-V, RFmax, DRF, Vmax y τ, gráficos, conclusiones, recomendaciones, plan de 6–9 semanas y cargas de trineo. Esquema de colocación de picas con corrección de paralaje desde el punto de paneo.
- **3 · Biomecánica de carrera** (tipo Kinovea): esqueleto manual o con seguimiento automático de marcadores, ángulos articulares, ángulo libre, kinograma de 5 posiciones con alertas técnicas, tiempo de contacto, vuelo, longitud y frecuencia de paso, y velocidad A–B calibrada.
- **4 · Fotocélula por vídeo** (tipo Photo Finish): línea vertical que detecta el primer cuadro en que el cuerpo la cruza, a la ida y a la vuelta. 5-0-5, 10-0-10, 5-10-5, T-Test, ida y vuelta, sprint y pruebas personalizadas, con déficit de cambio de dirección.
- **5 · Progreso:** compara todas las evaluaciones (mejoró, empeoró, sin cambio claro), alertas técnicas corregidas y notas del entrenador.

## Cómo está hecha

- La interfaz es la app web de `web/index.html` (la misma del artifact de Claude). `tools/build_web.py` la copia a `app/src/main/assets/www/` con las librerías (Chart.js, jsPDF) y las fuentes incluidas, para que funcione **sin internet**.
- Android aporta lo que el navegador no puede:
  - **Grabar en cámara lenta real a 240 fps** con la sesión de alta velocidad de Camera2 (`RecordActivity`), en horizontal y con una línea de referencia arrastrable. Si el teléfono no ofrece cámara lenta a otras apps, graba al máximo disponible y lo avisa.
  - **Guardar y compartir** PDF, CSV, kinogramas y copias: se guardan en *Descargas › AVD Sprint* y se abre el menú para compartir.
  - **Actualización automática** desde GitHub Releases (versión de descarga directa).
- Los datos quedan solo en el teléfono.

## Cambiar la app

Edita `web/index.html` (o actualiza el artifact y copia su contenido ahí) y sube el cambio a `main`: GitHub compila y publica la versión nueva sola.

## Publicar para descarga

Ver **PUBLICAR.md**: GitHub Releases con APK firmado, enlace fijo a la última versión y página de descarga en GitHub Pages.
