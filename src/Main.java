import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proceso batch: lee resúmenes de tarjeta de crédito (PDF de BBVA Visa) y
 * movimientos de Mercado Pago (CSV en mpago/), extrae los consumos, los
 * categoriza por reglas de comercio y genera un único reporte HTML con un
 * selector de período (un período por resumen), un selector de fuente
 * (Visa / Mercado Pago / ambas) y un gráfico de torta (donut) de gastos
 * por categoría.
 *
 * Uso: java Main [pdf-o-directorio] [directorio-salida]
 *   - Sin argumentos procesa todos los PDFs de "pdfs/" y escribe en "htmls/".
 *   - Con un PDF puntual, genera el reporte solo con ese período.
 */
public class Main {

    record Movimiento(String fecha, LocalDate dia, String descripcion, String cupon,
                      BigDecimal importe, boolean enDolares, String categoria, String fuente) {}

    record Categoria(String nombre, BigDecimal total, int operaciones) {}

    record Periodo(LocalDate desde, LocalDate hasta, String etiqueta) {}

    record Resumen(String id, Periodo periodo, List<Movimiento> visa, List<Movimiento> mpago) {}

    // Reglas de categorización: primera coincidencia gana (sobre la descripción
    // normalizada en mayúsculas y sin acentos). Se cargan desde ARCHIVO_REGLAS,
    // editable sin tocar el código; el orden del archivo define la prioridad.
    private static final Path ARCHIVO_REGLAS = Path.of("categorias.txt");
    private static final Path DIRECTORIO_MPAGO = Path.of("mpago");
    private static final Map<String, List<String>> reglas = new LinkedHashMap<>();
    private static final String OTROS = "Otros";
    private static final int MAX_PORCIONES_TORTA = 8;

    private static final String FUENTE_VISA = "Visa";
    private static final String FUENTE_MPAGO = "Mercado Pago";

    // Paleta categórica validada (dataviz): orden fijo, modo claro / oscuro.
    private static final String[] PALETA_CLARA = {"#2a78d6", "#1baf7a", "#eda100", "#008300",
            "#4a3aa7", "#e34948", "#e87ba4", "#eb6834"};
    private static final String[] PALETA_OSCURA = {"#3987e5", "#199e70", "#c98500", "#008300",
            "#9085e9", "#e66767", "#d55181", "#d95926"};

    private static final Pattern LINEA_CONSUMO = Pattern.compile(
            "^(\\d{2}-[A-Za-z]{3}-\\d{2})\\s+(.+?)\\s+(\\d{6})\\s+(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})$");

    private static final Pattern CIERRE_ANTERIOR = Pattern.compile(
            "CIERRE ANTERIOR\\s+(\\d{2}-[A-Za-z]{3}-\\d{2})");
    private static final Pattern CIERRE_ACTUAL = Pattern.compile(
            "CIERRE ACTUAL\\s+(\\d{2}-[A-Za-z]{3}-\\d{2})");

    private static final Pattern FECHA_VISA = Pattern.compile("(\\d{2})-([A-Za-z]{3})-(\\d{2})");
    private static final Pattern FECHA_MPAGO = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{4})");

    private static final Map<String, Integer> MESES = Map.ofEntries(
            Map.entry("ENE", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("ABR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AGO", 8),
            Map.entry("SEP", 9), Map.entry("SET", 9), Map.entry("OCT", 10), Map.entry("NOV", 11),
            Map.entry("DIC", 12));

    public static void main(String[] args) throws Exception {
        Path entrada = Path.of(args.length > 0 ? args[0] : "pdfs");
        Path dirSalida = Path.of(args.length > 1 ? args[1] : "htmls");

        try {
            cargarReglas(ARCHIVO_REGLAS);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }

        List<Movimiento> mpagoTodos = cargarMovimientosMpago(DIRECTORIO_MPAGO);

        List<File> pdfs;
        if (Files.isDirectory(entrada)) {
            try (var stream = Files.list(entrada)) {
                pdfs = stream.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                        .sorted().map(Path::toFile).toList();
            }
            if (pdfs.isEmpty()) {
                System.err.println("No hay PDFs en el directorio: " + entrada.toAbsolutePath());
                System.exit(1);
            }
        } else if (Files.isRegularFile(entrada)) {
            pdfs = List.of(entrada.toFile());
        } else {
            System.err.println("No se encuentra: " + entrada.toAbsolutePath());
            System.exit(1);
            return;
        }

        Files.createDirectories(dirSalida);
        int fallidos = 0;
        List<Resumen> resumenes = new ArrayList<>();
        for (File pdf : pdfs) {
            try {
                resumenes.add(parsearResumen(pdf, mpagoTodos));
            } catch (Exception e) {
                fallidos++;
                System.err.println("Error procesando " + pdf.getName() + ": " + e.getMessage());
            }
        }
        if (resumenes.isEmpty()) {
            System.err.println("No se pudo procesar ningún PDF");
            System.exit(2);
        }
        resumenes.sort(Comparator.comparing(
                (Resumen r) -> r.periodo() == null ? null : r.periodo().hasta(),
                Comparator.nullsFirst(Comparator.naturalOrder())));

        Path salida = dirSalida.resolve("gastos.html");
        Files.writeString(salida, generarHtml(resumenes), StandardCharsets.UTF_8);

        System.out.printf("%nProcesados %d de %d PDFs%n", pdfs.size() - fallidos, pdfs.size());
        System.out.println("Reporte generado: " + salida.toAbsolutePath());
        if (fallidos > 0) System.exit(2);
    }

    static Resumen parsearResumen(File pdf, List<Movimiento> mpagoTodos) throws Exception {
        String base = pdf.getName().replaceFirst("(?i)\\.pdf$", "");

        String texto;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            texto = new PDFTextStripper().getText(doc);
        }

        List<Movimiento> visa = parsearConsumos(texto);
        if (visa.isEmpty()) {
            throw new IllegalStateException("no se encontraron consumos (¿es un resumen de tarjeta BBVA?)");
        }

        Periodo periodo = parsearPeriodo(texto);
        List<Movimiento> mpago = filtrarPorPeriodo(mpagoTodos, periodo);

        System.out.println();
        System.out.println("=== " + pdf.getName() + " ===");
        if (periodo != null) {
            System.out.printf("Período: %s — Visa %d ops, Mercado Pago %d ops%n",
                    periodo.etiqueta(), visa.size(), mpago.size());
        }
        List<Movimiento> ambas = combinar(visa, mpago);
        imprimirResumen(agrupar(ambas.stream().filter(m -> !m.enDolares()).toList()),
                ambas.stream().filter(Movimiento::enDolares).toList());

        return new Resumen(base, periodo, visa, mpago);
    }

    static List<Movimiento> combinar(List<Movimiento> visa, List<Movimiento> mpago) {
        List<Movimiento> ambas = new ArrayList<>(visa);
        ambas.addAll(mpago);
        ambas.sort(Comparator.comparing(Movimiento::dia,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return ambas;
    }

    // ------------------------------------------------------------------ parseo

    static List<Movimiento> parsearConsumos(String texto) {
        List<Movimiento> resultado = new ArrayList<>();
        boolean dentroDeConsumos = false;
        for (String linea : texto.split("\\r?\\n")) {
            String limpia = linea.strip();
            if (limpia.startsWith("Consumos ")) {
                dentroDeConsumos = true;
                continue;
            }
            if (limpia.startsWith("TOTAL CONSUMOS")) {
                dentroDeConsumos = false;
                continue;
            }
            if (!dentroDeConsumos) continue;

            Matcher m = LINEA_CONSUMO.matcher(limpia);
            if (!m.matches()) continue;

            String descripcion = m.group(2).strip();
            boolean usd = normalizar(descripcion).contains("USD");
            BigDecimal importe = new BigDecimal(m.group(4).replace(".", "").replace(',', '.'));
            resultado.add(new Movimiento(m.group(1), parsearFechaVisa(m.group(1)), descripcion,
                    m.group(3), importe, usd, categorizar(descripcion), FUENTE_VISA));
        }
        return resultado;
    }

    static Periodo parsearPeriodo(String texto) {
        Matcher desde = CIERRE_ANTERIOR.matcher(texto);
        Matcher hasta = CIERRE_ACTUAL.matcher(texto);
        if (!desde.find() || !hasta.find()) return null;
        return new Periodo(parsearFechaVisa(desde.group(1)), parsearFechaVisa(hasta.group(1)),
                desde.group(1) + " al " + hasta.group(1));
    }

    static LocalDate parsearFechaVisa(String fecha) {
        Matcher m = FECHA_VISA.matcher(fecha);
        if (!m.matches()) return null;
        Integer mes = MESES.get(normalizar(m.group(2)));
        if (mes == null) return null;
        return LocalDate.of(2000 + Integer.parseInt(m.group(3)), mes, Integer.parseInt(m.group(1)));
    }

    static LocalDate parsearFechaMpago(String fecha) {
        Matcher m = FECHA_MPAGO.matcher(fecha);
        if (!m.matches()) return null;
        return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(1)));
    }

    /**
     * Lee todos los CSV de movimientos de Mercado Pago del directorio dado.
     * Solo toma los movimientos con monto negativo (gastos, guardados con el
     * importe en positivo), excluye transferencias propias (Leonel Fernandez /
     * Melina Taboada) y deduplica por REFERENCE_ID por si los archivos se
     * superponen.
     */
    static List<Movimiento> cargarMovimientosMpago(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> csvs;
        try (var stream = Files.list(dir)) {
            csvs = stream.filter(p -> p.toString().toLowerCase().endsWith(".csv")).sorted().toList();
        } catch (java.io.IOException e) {
            System.err.println("No se pudo listar " + dir + ": " + e.getMessage());
            return List.of();
        }
        List<Movimiento> resultado = new ArrayList<>();
        Set<String> referencias = new HashSet<>();
        for (Path csv : csvs) {
            try {
                for (String linea : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
                    String[] campos = linea.strip().split(";");
                    if (campos.length < 4 || !FECHA_MPAGO.matcher(campos[0]).matches()) continue;
                    BigDecimal neto = new BigDecimal(campos[3].replace(".", "").replace(',', '.'));
                    if (neto.signum() >= 0) continue;
                    String descripcion = campos[1].strip();
                    if (esMovimientoPropio(descripcion)) continue;
                    if (!referencias.add(campos[2])) continue;
                    resultado.add(new Movimiento(campos[0], parsearFechaMpago(campos[0]), descripcion,
                            campos[2], neto.negate(), false, categorizar(descripcion), FUENTE_MPAGO));
                }
            } catch (Exception e) {
                System.err.println("Error leyendo " + csv.getFileName() + ": " + e.getMessage());
            }
        }
        return resultado;
    }

    static boolean esMovimientoPropio(String descripcion) {
        String d = normalizar(descripcion);
        return (d.contains("FERNANDEZ") && d.contains("LEONEL"))
                || (d.contains("TABOADA") && d.contains("MELINA"));
    }

    /**
     * Se queda con los movimientos posteriores al cierre anterior y hasta el
     * cierre actual inclusive, que es el rango que cubre el resumen.
     */
    static List<Movimiento> filtrarPorPeriodo(List<Movimiento> movimientos, Periodo periodo) {
        if (periodo == null || periodo.desde() == null || periodo.hasta() == null) {
            System.err.println("Aviso: no se pudo determinar el período del resumen; "
                    + "se incluyen todos los movimientos de Mercado Pago");
            return movimientos;
        }
        return movimientos.stream()
                .filter(m -> m.dia() != null && m.dia().isAfter(periodo.desde())
                        && !m.dia().isAfter(periodo.hasta()))
                .toList();
    }

    /**
     * Carga las reglas de categorización desde un archivo de texto con formato
     * "Categoría = palabra clave, palabra clave, ...". Las líneas vacías o que
     * empiezan con # se ignoran; si una categoría aparece en más de una línea,
     * sus palabras clave se acumulan.
     */
    static void cargarReglas(Path archivo) {
        if (!Files.isRegularFile(archivo)) {
            throw new IllegalStateException("No se encuentra el archivo de reglas: "
                    + archivo.toAbsolutePath());
        }
        List<String> lineas;
        try {
            lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo leer " + archivo + ": " + e.getMessage());
        }
        reglas.clear();
        for (String linea : lineas) {
            String limpia = linea.strip();
            if (limpia.isEmpty() || limpia.startsWith("#")) continue;
            int igual = limpia.indexOf('=');
            if (igual < 1) {
                System.err.println("Regla ignorada (falta '='): " + limpia);
                continue;
            }
            String categoria = limpia.substring(0, igual).strip();
            List<String> claves = java.util.Arrays.stream(limpia.substring(igual + 1).split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).map(Main::normalizar).toList();
            if (categoria.isEmpty() || claves.isEmpty()) {
                System.err.println("Regla ignorada (categoría o palabras clave vacías): " + limpia);
                continue;
            }
            reglas.computeIfAbsent(categoria, k -> new ArrayList<>()).addAll(claves);
        }
        if (reglas.isEmpty()) {
            throw new IllegalStateException("El archivo de reglas no tiene ninguna regla válida: "
                    + archivo.toAbsolutePath());
        }
    }

    static String categorizar(String descripcion) {
        String desc = normalizar(descripcion);
        for (Map.Entry<String, List<String>> regla : reglas.entrySet()) {
            if (regla.getValue().stream().anyMatch(desc::contains)) return regla.getKey();
        }
        return OTROS;
    }

    static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase();
    }

    static List<Categoria> agrupar(List<Movimiento> movimientos) {
        Map<String, List<Movimiento>> porCategoria = new LinkedHashMap<>();
        for (Movimiento m : movimientos) {
            porCategoria.computeIfAbsent(m.categoria(), k -> new ArrayList<>()).add(m);
        }
        return porCategoria.entrySet().stream()
                .map(e -> new Categoria(e.getKey(),
                        e.getValue().stream().map(Movimiento::importe).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted(Comparator.comparing(Categoria::total).reversed())
                .toList();
    }

    /**
     * Colapsa la cola de categorías en "Otros" para que la torta tenga como
     * máximo 8 porciones. La categoría de respaldo "Otros" (comercios sin
     * regla) se funde con esa porción colapsada, así nunca hay dos porciones
     * con el mismo nombre, y "Otros" va siempre al final.
     */
    static List<Categoria> paraTorta(List<Categoria> categorias) {
        List<Categoria> conNombre = new ArrayList<>(
                categorias.stream().filter(c -> !c.nombre().equals(OTROS)).toList());
        BigDecimal resto = categorias.stream().filter(c -> c.nombre().equals(OTROS))
                .map(Categoria::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        int ops = categorias.stream().filter(c -> c.nombre().equals(OTROS))
                .mapToInt(Categoria::operaciones).sum();
        boolean hayOtros = categorias.size() > conNombre.size();

        int lugares = hayOtros || conNombre.size() > MAX_PORCIONES_TORTA
                ? MAX_PORCIONES_TORTA - 1 : MAX_PORCIONES_TORTA;
        while (conNombre.size() > lugares) {
            Categoria c = conNombre.remove(conNombre.size() - 1);
            resto = resto.add(c.total());
            ops += c.operaciones();
            hayOtros = true;
        }
        if (hayOtros) conNombre.add(new Categoria(OTROS, resto, ops));
        return conNombre;
    }

    // ------------------------------------------------------------------ salida

    static void imprimirResumen(List<Categoria> categorias, List<Movimiento> enDolares) {
        BigDecimal total = categorias.stream().map(Categoria::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.println();
        System.out.println("Gastos por categoria (ARS):");
        for (Categoria c : categorias) {
            System.out.printf("  %-32s $ %15s  (%2d ops, %5.1f%%)%n",
                    c.nombre(), formatoMonto(c.total()), c.operaciones(), porcentaje(c.total(), total));
        }
        System.out.printf("  %-32s $ %15s%n", "TOTAL", formatoMonto(total));
        if (!enDolares.isEmpty()) {
            BigDecimal totalUsd = enDolares.stream().map(Movimiento::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
            System.out.printf("  Consumos en dolares: USD %s (%d ops)%n", formatoMonto(totalUsd), enDolares.size());
        }
    }

    static double porcentaje(BigDecimal parte, BigDecimal total) {
        return parte.multiply(BigDecimal.valueOf(100))
                .divide(total, 1, RoundingMode.HALF_UP).doubleValue();
    }

    static String formatoMonto(BigDecimal valor) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols();
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", simbolos).format(valor);
    }

    static String escaparHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ HTML

    static String generarHtml(List<Resumen> resumenes) {
        Resumen inicial = resumenes.get(resumenes.size() - 1);
        StringBuilder botonesPeriodo = new StringBuilder();
        StringBuilder vistas = new StringBuilder();
        for (Resumen r : resumenes) {
            String etiqueta = r.periodo() == null ? r.id() : r.periodo().etiqueta();
            botonesPeriodo.append("    <button type=\"button\" data-periodo=\"")
                    .append(escaparHtml(r.id())).append("\"")
                    .append(r == inicial ? " class=\"activo\"" : "").append(">")
                    .append(escaparHtml(etiqueta)).append("</button>\n");

            String kpiPeriodo = generarKpiPeriodo(r.periodo() == null ? null : r.periodo().etiqueta());
            vistas.append(generarVista(r.id(), "visa", false, kpiPeriodo, r.visa()))
                    .append(generarVista(r.id(), "mpago", false, kpiPeriodo, r.mpago()))
                    .append(generarVista(r.id(), "ambas", r == inicial, kpiPeriodo,
                            combinar(r.visa(), r.mpago())));
        }

        return plantilla()
                .replace("__COLORES__", generarVariablesColor())
                .replace("__BOTONES_PERIODO__", botonesPeriodo.toString())
                .replace("__VISTAS__", vistas.toString());
    }

    static String generarVista(String periodoId, String id, boolean visible, String kpiPeriodo,
                               List<Movimiento> movimientos) {
        String oculta = visible ? "" : " hidden";
        if (movimientos.isEmpty()) {
            return "  <div class=\"vista\" data-periodo=\"" + escaparHtml(periodoId)
                    + "\" data-vista=\"" + id + "\"" + oculta + ">\n"
                    + "    <section class=\"tarjeta\"><p class=\"secundario\">Sin movimientos en el período.</p></section>\n"
                    + "  </div>\n";
        }

        List<Movimiento> enPesos = movimientos.stream().filter(m -> !m.enDolares()).toList();
        List<Movimiento> enDolares = movimientos.stream().filter(Movimiento::enDolares).toList();
        List<Categoria> categorias = agrupar(enPesos);
        BigDecimal total = categorias.stream().map(Categoria::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        String seccionGrafico = "";
        String seccionCategorias = "";
        if (total.signum() > 0) {
            List<Categoria> torta = paraTorta(categorias);
            seccionGrafico = plantillaGrafico()
                    .replace("__DONUT__", generarDonut(torta, total))
                    .replace("__LEYENDA__", generarLeyenda(torta, total))
                    .replace("__DATOS_OPS__", generarDatosOperaciones(torta, enPesos));
            seccionCategorias = plantillaCategorias()
                    .replace("__TABLA_CATEGORIAS__", generarTablaCategorias(categorias, total));
        }

        return plantillaVista()
                .replace("__PERIODO_ID__", escaparHtml(periodoId))
                .replace("__ID__", id)
                .replace("__OCULTA__", oculta)
                .replace("__TOTAL__", formatoMonto(total))
                .replace("__OPERACIONES__", String.valueOf(movimientos.size()))
                .replace("__KPI_PERIODO__", kpiPeriodo)
                .replace("__SECCION_GRAFICO__", seccionGrafico)
                .replace("__SECCION_CATEGORIAS__", seccionCategorias)
                .replace("__SECCION_USD__", generarSeccionUsd(enDolares))
                .replace("__TABLA_MOVIMIENTOS__", generarTablaMovimientos(movimientos));
    }

    static String generarKpiPeriodo(String periodoResumen) {
        if (periodoResumen == null) return "";
        return "<div class=\"kpi\"><div class=\"valor fecha\">" + escaparHtml(periodoResumen)
                + "</div><div class=\"rotulo\">Período del resumen</div></div>";
    }

    static String generarVariablesColor() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PALETA_CLARA.length; i++) {
            sb.append("      --serie-").append(i + 1).append(": ").append(PALETA_CLARA[i]).append(";\n");
        }
        return sb.toString();
    }

    static String generarDonut(List<Categoria> torta, BigDecimal total) {
        double cx = 180, cy = 180, rExt = 140, rInt = 88, rEtiqueta = 156;
        StringBuilder sb = new StringBuilder();
        sb.append("<svg viewBox=\"0 0 360 360\" role=\"img\" aria-label=\"Gastos por categoría\">\n");

        double angulo = -90; // arranca a las 12, sentido horario
        StringBuilder etiquetas = new StringBuilder();
        for (int i = 0; i < torta.size(); i++) {
            Categoria c = torta.get(i);
            double fraccion = c.total().doubleValue() / total.doubleValue();
            double barrido = fraccion * 360;
            double desde = angulo, hasta = angulo + barrido;
            angulo = hasta;

            String path = arcoDonut(cx, cy, rExt, rInt, desde, hasta);
            double pct = fraccion * 100;
            sb.append(String.format(java.util.Locale.ROOT,
                    "  <path d=\"%s\" fill=\"var(--serie-%d)\" stroke=\"var(--superficie)\" stroke-width=\"2\" " +
                    "data-idx=\"%d\" data-nombre=\"%s\" data-monto=\"$ %s\" data-pct=\"%.1f%%\" data-ops=\"%d\"/>\n",
                    path, i + 1, i, escaparHtml(c.nombre()), formatoMonto(c.total()), pct, c.operaciones()));

            if (pct >= 5) { // etiqueta directa selectiva: solo porciones grandes
                double medio = Math.toRadians((desde + hasta) / 2);
                double x = cx + rEtiqueta * Math.cos(medio);
                double y = cy + rEtiqueta * Math.sin(medio);
                String anclaje = Math.cos(medio) >= 0.25 ? "start" : (Math.cos(medio) <= -0.25 ? "end" : "middle");
                etiquetas.append(String.format(java.util.Locale.ROOT,
                        "  <text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" class=\"etiqueta\">%.0f%%</text>\n",
                        x, y + 4, anclaje, pct));
            }
        }
        sb.append(etiquetas);
        sb.append("  <text x=\"180\" y=\"172\" text-anchor=\"middle\" class=\"centro-monto\">$ ")
          .append(formatoMonto(total)).append("</text>\n");
        sb.append("  <text x=\"180\" y=\"196\" text-anchor=\"middle\" class=\"centro-detalle\">total consumos</text>\n");
        sb.append("</svg>");
        return sb.toString();
    }

    static String arcoDonut(double cx, double cy, double rExt, double rInt, double desde, double hasta) {
        double a1 = Math.toRadians(desde), a2 = Math.toRadians(hasta);
        int arcoGrande = (hasta - desde) > 180 ? 1 : 0;
        return String.format(java.util.Locale.ROOT,
                "M %.2f %.2f A %.0f %.0f 0 %d 1 %.2f %.2f L %.2f %.2f A %.0f %.0f 0 %d 0 %.2f %.2f Z",
                cx + rExt * Math.cos(a1), cy + rExt * Math.sin(a1),
                rExt, rExt, arcoGrande,
                cx + rExt * Math.cos(a2), cy + rExt * Math.sin(a2),
                cx + rInt * Math.cos(a2), cy + rInt * Math.sin(a2),
                rInt, rInt, arcoGrande,
                cx + rInt * Math.cos(a1), cy + rInt * Math.sin(a1));
    }

    /**
     * Arma el JSON con los movimientos de cada porción de la torta (mismo orden
     * que las porciones), para que el tooltip pueda listarlos. La porción "Otros"
     * junta los movimientos de todas las categorías colapsadas.
     */
    static String generarDatosOperaciones(List<Categoria> torta, List<Movimiento> movimientos) {
        Set<String> nombresPrincipales = new HashSet<>();
        for (Categoria c : torta) {
            if (!c.nombre().equals(OTROS)) nombresPrincipales.add(c.nombre());
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < torta.size(); i++) {
            Categoria c = torta.get(i);
            List<Movimiento> deLaPorcion = movimientos.stream()
                    .filter(m -> c.nombre().equals(OTROS)
                            ? !nombresPrincipales.contains(m.categoria())
                            : m.categoria().equals(c.nombre()))
                    .sorted(Comparator.comparing(Movimiento::importe).reversed())
                    .toList();
            if (i > 0) sb.append(",");
            sb.append("\n  [");
            for (int j = 0; j < deLaPorcion.size(); j++) {
                Movimiento m = deLaPorcion.get(j);
                if (j > 0) sb.append(",");
                sb.append(String.format("{\"f\":\"%s\",\"d\":\"%s\",\"m\":\"$ %s\"}",
                        escaparJson(m.fecha()), escaparJson(m.descripcion()),
                        escaparJson(formatoMonto(m.importe()))));
            }
            sb.append("]");
        }
        sb.append("\n]");
        return sb.toString();
    }

    static String escaparJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e");
    }

    static String generarLeyenda(List<Categoria> torta, BigDecimal total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < torta.size(); i++) {
            Categoria c = torta.get(i);
            sb.append(String.format(java.util.Locale.ROOT, """
                      <li>
                        <span class="muestra" style="background:var(--serie-%d)"></span>
                        <span class="nombre">%s</span>
                        <span class="monto">$ %s</span>
                        <span class="pct">%.1f%%</span>
                      </li>
                    """, i + 1, escaparHtml(c.nombre()), formatoMonto(c.total()), porcentaje(c.total(), total)));
        }
        return sb.toString();
    }

    static String generarTablaCategorias(List<Categoria> categorias, BigDecimal total) {
        StringBuilder sb = new StringBuilder();
        for (Categoria c : categorias) {
            sb.append(String.format(java.util.Locale.ROOT,
                    "          <tr><td>%s</td><td class=\"num\">%d</td><td class=\"num\">$ %s</td><td class=\"num\">%.1f%%</td></tr>\n",
                    escaparHtml(c.nombre()), c.operaciones(), formatoMonto(c.total()), porcentaje(c.total(), total)));
        }
        sb.append(String.format(
                "          <tr class=\"fila-total\"><td>Total</td><td class=\"num\"></td><td class=\"num\">$ %s</td><td class=\"num\">100%%</td></tr>\n",
                formatoMonto(total)));
        return sb.toString();
    }

    static String generarTablaMovimientos(List<Movimiento> movimientos) {
        StringBuilder sb = new StringBuilder();
        for (Movimiento m : movimientos) {
            String moneda = m.enDolares() ? "USD" : "$";
            sb.append(String.format(
                    "            <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s %s</td></tr>\n",
                    m.fecha(), escaparHtml(m.descripcion()), m.fuente(), escaparHtml(m.categoria()),
                    moneda, formatoMonto(m.importe())));
        }
        return sb.toString();
    }

    static String generarSeccionUsd(List<Movimiento> enDolares) {
        if (enDolares.isEmpty()) return "";
        BigDecimal total = enDolares.stream().map(Movimiento::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        StringBuilder items = new StringBuilder();
        for (Movimiento m : enDolares) {
            items.append("        <li>").append(escaparHtml(m.descripcion()))
                 .append(" — USD ").append(formatoMonto(m.importe())).append("</li>\n");
        }
        return """
                    <section class="tarjeta">
                      <h2>Consumos en dólares (fuera de la torta)</h2>
                      <p class="secundario">Total: USD __TOTAL_USD__</p>
                      <ul class="lista-usd">
                __ITEMS__      </ul>
                    </section>
                """.replace("__TOTAL_USD__", formatoMonto(total)).replace("__ITEMS__", items.toString());
    }

    static String plantillaVista() {
        return """
  <div class="vista" data-periodo="__PERIODO_ID__" data-vista="__ID__"__OCULTA__>
    <section class="tarjeta kpis">
      <div class="kpi"><div class="valor">$ __TOTAL__</div><div class="rotulo">Total consumos ARS</div></div>
      <div class="kpi"><div class="valor">__OPERACIONES__</div><div class="rotulo">Operaciones</div></div>
      __KPI_PERIODO__
    </section>
__SECCION_GRAFICO__
__SECCION_CATEGORIAS__
__SECCION_USD__
    <section class="tarjeta desplazable">
      <details>
        <summary>Todos los movimientos (__OPERACIONES__)</summary>
        <table>
          <thead><tr><th>Fecha</th><th>Descripción</th><th>Fuente</th><th>Categoría</th><th class="num">Importe</th></tr></thead>
          <tbody>
__TABLA_MOVIMIENTOS__          </tbody>
        </table>
      </details>
    </section>
  </div>
""";
    }

    static String plantillaGrafico() {
        return """
    <section class="tarjeta">
      <h2>Gastos por categoría</h2>
      <div class="grafico">
__DONUT__
        <ul class="leyenda">
__LEYENDA__        </ul>
      </div>
    </section>
    <script type="application/json" class="datos-operaciones">
__DATOS_OPS__
    </script>
""";
    }

    static String plantillaCategorias() {
        return """
    <section class="tarjeta desplazable">
      <h2>Detalle por categoría</h2>
      <table>
        <thead><tr><th>Categoría</th><th class="num">Operaciones</th><th class="num">Total</th><th class="num">%</th></tr></thead>
        <tbody>
__TABLA_CATEGORIAS__        </tbody>
      </table>
    </section>
""";
    }

    static String plantilla() {
        return """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Gastos por categoría</title>
<style>
  :root {
    --superficie: #fcfcfb;
    --plano: #f9f9f7;
    --tinta: #0b0b0b;
    --tinta-secundaria: #52514e;
    --tinta-tenue: #898781;
    --grilla: #e1e0d9;
    --borde: rgba(11,11,11,0.10);
__COLORES__  }
  @media (prefers-color-scheme: dark) {
    :root {
      --superficie: #1a1a19;
      --plano: #0d0d0d;
      --tinta: #ffffff;
      --tinta-secundaria: #c3c2b7;
      --tinta-tenue: #898781;
      --grilla: #2c2c2a;
      --borde: rgba(255,255,255,0.10);
      --serie-1: #3987e5; --serie-2: #199e70; --serie-3: #c98500; --serie-4: #008300;
      --serie-5: #9085e9; --serie-6: #e66767; --serie-7: #d55181; --serie-8: #d95926;
    }
  }
  * { box-sizing: border-box; margin: 0; }
  body {
    font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
    background: var(--plano); color: var(--tinta);
    padding: 24px; line-height: 1.45;
  }
  main { max-width: 960px; margin: 0 auto; display: grid; gap: 20px; }
  h1 { font-size: 1.5rem; }
  h2 { font-size: 1.05rem; margin-bottom: 12px; }
  .secundario { color: var(--tinta-secundaria); font-size: 0.9rem; }
  .tarjeta {
    background: var(--superficie); border: 1px solid var(--borde);
    border-radius: 12px; padding: 20px;
  }
  .selectores { display: grid; gap: 10px; justify-items: start; }
  .selector {
    display: inline-flex; flex-wrap: wrap;
    background: var(--superficie); border: 1px solid var(--borde);
    border-radius: 10px; overflow: hidden;
  }
  .selector button {
    border: 0; background: none; color: var(--tinta-secundaria);
    font: inherit; font-size: 0.9rem; padding: 8px 18px; cursor: pointer;
  }
  .selector button + button { border-left: 1px solid var(--borde); }
  .selector button.activo { background: var(--tinta); color: var(--superficie); font-weight: 600; }
  .vista { display: grid; gap: 20px; }
  .vista[hidden] { display: none; }
  .kpis { display: flex; gap: 16px; flex-wrap: wrap; }
  .kpi { flex: 1 1 180px; }
  .kpi .valor { font-size: 2rem; font-weight: 650; }
  .kpi .valor.fecha { font-size: 1.35rem; line-height: 2.9rem; white-space: nowrap; }
  .kpi .rotulo { color: var(--tinta-tenue); font-size: 0.8rem; text-transform: uppercase; letter-spacing: .04em; }
  .grafico { display: flex; gap: 28px; align-items: center; flex-wrap: wrap; }
  .grafico svg { width: min(380px, 100%); height: auto; flex: 0 1 380px; }
  .grafico svg path { cursor: pointer; }
  .grafico svg path:hover { opacity: 0.85; }
  .etiqueta { font-size: 13px; fill: var(--tinta-secundaria); }
  .centro-monto { font-size: 19px; font-weight: 700; fill: var(--tinta); }
  .centro-detalle { font-size: 12px; fill: var(--tinta-tenue); }
  .leyenda { list-style: none; padding: 0; flex: 1 1 300px; display: grid; gap: 8px; }
  .leyenda li { display: grid; grid-template-columns: 14px 1fr auto auto; gap: 10px; align-items: center; }
  .muestra { width: 12px; height: 12px; border-radius: 4px; display: inline-block; }
  .leyenda .monto, .leyenda .pct { font-variant-numeric: tabular-nums; }
  .leyenda .pct { color: var(--tinta-tenue); min-width: 52px; text-align: right; }
  table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
  th, td { padding: 7px 10px; text-align: left; border-bottom: 1px solid var(--grilla); }
  th { color: var(--tinta-tenue); font-weight: 600; font-size: 0.78rem; text-transform: uppercase; letter-spacing: .04em; }
  td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
  .fila-total td { font-weight: 700; border-top: 2px solid var(--grilla); border-bottom: none; }
  .desplazable { overflow-x: auto; }
  details summary { cursor: pointer; font-weight: 600; }
  details[open] summary { margin-bottom: 12px; }
  .lista-usd { padding-left: 20px; color: var(--tinta-secundaria); font-size: 0.9rem; }
  #tooltip {
    position: fixed; pointer-events: none; display: none; z-index: 10;
    background: var(--tinta); color: var(--superficie);
    padding: 9px 11px; border-radius: 8px; font-size: 0.8rem; max-width: min(480px, 92vw);
  }
  #tooltip strong { display: block; }
  #tooltip ul {
    list-style: none; margin: 7px 0 0; padding: 6px 0 0;
    border-top: 1px solid color-mix(in srgb, var(--superficie) 25%, transparent);
    display: grid; gap: 2px;
  }
  #tooltip li { display: flex; justify-content: space-between; gap: 14px; font-size: 0.72rem; opacity: .92; }
  #tooltip li .op-monto { font-variant-numeric: tabular-nums; white-space: nowrap; }
</style>
</head>
<body>
<main>
  <header>
    <h1>¿En qué categorías gastás más?</h1>
    <p class="secundario">Consumos del período en pesos — Visa y Mercado Pago</p>
  </header>

  <div class="selectores">
    <div class="selector" id="selector-periodo" role="group" aria-label="Período">
__BOTONES_PERIODO__    </div>
    <div class="selector" id="selector-fuente" role="group" aria-label="Fuente de datos">
      <button type="button" data-vista="visa">Visa</button>
      <button type="button" data-vista="mpago">Mercado Pago</button>
      <button type="button" data-vista="ambas" class="activo">Ambas</button>
    </div>
  </div>

__VISTAS__
</main>
<div id="tooltip"></div>
<script>
  const tooltip = document.getElementById('tooltip');

  function escaparTexto(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  let periodoActivo = document.querySelector('#selector-periodo button.activo').dataset.periodo;
  let fuenteActiva = 'ambas';

  function actualizarVistas() {
    document.querySelectorAll('.vista').forEach(v =>
      v.hidden = v.dataset.periodo !== periodoActivo || v.dataset.vista !== fuenteActiva);
  }

  document.querySelectorAll('.selector button').forEach(b => {
    b.addEventListener('click', () => {
      b.closest('.selector').querySelectorAll('button').forEach(o => o.classList.toggle('activo', o === b));
      if (b.dataset.periodo) periodoActivo = b.dataset.periodo;
      else fuenteActiva = b.dataset.vista;
      actualizarVistas();
    });
  });

  document.querySelectorAll('.vista').forEach(v => {
    const datos = v.querySelector('.datos-operaciones');
    if (!datos) return;
    const opsPorPorcion = JSON.parse(datos.textContent);
    v.querySelectorAll('.grafico svg path').forEach(p => {
      p.addEventListener('mousemove', e => {
        const ops = opsPorPorcion[+p.dataset.idx] || [];
        let html = '<strong>' + escaparTexto(p.dataset.nombre) + '</strong>' +
          p.dataset.monto + ' · ' + p.dataset.pct + ' · ' + p.dataset.ops + ' operaciones';
        html += '<ul>' + ops.map(o =>
          '<li><span class="op-desc">' + escaparTexto(o.f + ' · ' + o.d) + '</span>' +
          '<span class="op-monto">' + escaparTexto(o.m) + '</span></li>').join('') + '</ul>';
        tooltip.innerHTML = html;
        tooltip.style.display = 'block';
        const r = tooltip.getBoundingClientRect();
        let x = e.clientX + 14, y = e.clientY + 14;
        if (x + r.width > window.innerWidth - 8) x = e.clientX - r.width - 14;
        if (y + r.height > window.innerHeight - 8) y = Math.max(8, e.clientY - r.height - 14);
        tooltip.style.left = x + 'px';
        tooltip.style.top = y + 'px';
      });
      p.addEventListener('mouseleave', () => tooltip.style.display = 'none');
    });
  });
</script>
</body>
</html>
""";
    }
}
