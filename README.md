# Calculador de gastos

Proceso batch en Java 21 que lee resúmenes de tarjeta de crédito (PDF de BBVA
Visa), extrae los consumos, los categoriza por reglas de comercio y genera por
cada PDF un reporte HTML con un gráfico de torta de gastos por categoría,
tooltip con el detalle de operaciones de cada porción, tablas de detalle y
modo claro/oscuro.

## Requisitos

- Windows con una JDK 21 instalada. `procesar.bat` usa por defecto
  `C:\Users\Leonel\.jdks\corretto-21.0.8`; si tu JDK está en otra ruta,
  editá la línea `set "JAVA_HOME=..."` del `.bat`.
- No hace falta Maven ni Gradle: la única dependencia (Apache PDFBox) ya está
  en `lib/pdfbox-app-3.0.5.jar`.

## Cómo ejecutar

1. Copiá los resúmenes PDF a la carpeta `pdfs/`.
2. Ejecutá el proceso batch desde la raíz del proyecto:

   ```bat
   procesar.bat
   ```

   Compila `src/Main.java` y genera en `htmls/` un reporte
   `gastos-<nombre-del-pdf>.html` por cada PDF de `pdfs/`. En consola imprime
   el resumen de gastos por categoría de cada resumen procesado.

3. Abrí el HTML generado en el navegador.

Para procesar un solo archivo:

```bat
procesar.bat ruta\al\resumen.pdf
```

También se puede invocar la clase directamente:

```bat
java -cp "out;lib\pdfbox-app-3.0.5.jar" Main [pdf-o-directorio] [directorio-salida]
```

## Estructura

```
├── pdfs/          resúmenes de tarjeta (ignorado por git)
├── htmls/         reportes generados (ignorado por git)
├── src/Main.java  todo el proceso: parseo, categorización y generación del HTML
├── lib/           PDFBox standalone
└── procesar.bat   compila y ejecuta
```

## Categorización

Las reglas están en el mapa `REGLAS` de `src/Main.java`: cada categoría tiene
una lista de palabras clave que se buscan en la descripción del consumo
(la primera coincidencia gana). Los comercios que no matchean ninguna regla
caen en "Otros". Para ajustar una categoría, editá la lista de palabras clave
y volvé a correr `procesar.bat`.

Notas:

- La torta muestra como máximo 8 porciones: las categorías más chicas se
  agrupan en "Otros" (el detalle completo está en la tabla del reporte).
- Los consumos en dólares (Spotify, Netflix, etc.) se listan aparte y no
  entran en la torta, para no mezclar monedas.
