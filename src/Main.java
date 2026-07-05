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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proceso batch: lee resúmenes de tarjeta de crédito (PDF de BBVA Visa),
 * extrae los consumos, los categoriza por reglas de comercio y genera por
 * cada PDF un reporte HTML con un gráfico de torta (donut) de gastos por
 * categoría.
 *
 * Uso: java Main [pdf-o-directorio] [directorio-salida]
 *   - Sin argumentos procesa todos los PDFs de "pdfs/" y escribe en "htmls/".
 *   - Con un PDF puntual, genera su HTML en el directorio de salida.
 */
public class Main {

    record Movimiento(String fecha, String descripcion, String cupon, BigDecimal importe,
                      boolean enDolares, String categoria) {}

    record Categoria(String nombre, BigDecimal total, int operaciones) {}

    // Reglas de categorización: primera coincidencia gana (sobre la descripción
    // normalizada en mayúsculas y sin acentos).
    private static final Map<String, List<String>> REGLAS = new LinkedHashMap<>();
    static {
        REGLAS.put("Educación", List.of("INSTITUTO", "COLEGIO", "UNIVERSIDAD"));
        REGLAS.put("Salud", List.of("OSDE", "FARMACIA", "FARMACITY", "SWISS MEDICAL"));
        REGLAS.put("Impuestos", List.of("AFIP", "ARCA", "MONOTRIB"));
        REGLAS.put("Seguros", List.of("FEDERACION PAT", "SEGURO", "LA CAJA"));
        REGLAS.put("Servicios", List.of("EDENOR", "EDESUR", "TELECENTRO", "PERSONAL", "MOVISTAR",
                "CLARO", "AYSA", "METROGAS", "NATURGY", "FIBERTEL", "CABLEVISION"));
        REGLAS.put("Gastronomía", List.of("BONAFIDE", "MC D", "MCD", "MCDONALD", "BURGER",
                "BODEGON", "ENTREPALITOS", "RESTAURAN", "PARRILLA", "CAFE", "GRIDO",
                "HELADER", "PIZZ", "STARBUCKS", "MOSTAZA"));
        REGLAS.put("Supermercado y almacén", List.of("JUMBO", "CENCOSUD", "CARREFOUR", "COTO",
                "CASADELPOL", "FIAMBRERIA", "PEDIDOSYA", "AMANDA", "EMPORIO", "FAN DE PAN",
                "REPOST", "PANADERIA", "VERDULERIA", "CARNICERIA", "CHANGOMAS", "VEA",
                "DISCO SA"));
        REGLAS.put("Indumentaria y calzado", List.of("GRIMOLDI", "MOOV", "GRISINO", "MACOWENS",
                "MAVERICK", "DEXTER", "ZARA", "LEVIS", "CHEEKY", "MIMO"));
        REGLAS.put("Entretenimiento", List.of("SPOTIFY", "NETFLIX", "PARAMOUN", "DISNEY", "HBO",
                "MAX.COM", "JUGUETES", "PANINI", "CINEMARK", "HOYTS", "STEAM", "PLAYSTATION",
                "SEVEN MARKET"));
        REGLAS.put("Mascotas", List.of("NUTRICAN", "PUPPIS", "VETERINAR", "PET"));
        REGLAS.put("Estacionamiento y transporte", List.of("ESTACIONAMIENTO", "YPF", "SHELL",
                "AXION", "PEAJE", "SUBE", "UBER", "CABIFY", "KOMO"));
        REGLAS.put("Hogar y tecnología", List.of("HIDROLIT", "TECNOTEAM", "VIDECOM", "EASY",
                "SODIMAC", "FRAVEGA", "GARBARINO", "FERRETERIA"));
        REGLAS.put("Compras online", List.of("MERCADOLIBRE", "MELI", "TIENDAMIA", "AMAZON"));
    }
    private static final String OTROS = "Otros";
    private static final int MAX_PORCIONES_TORTA = 8;

    // Paleta categórica validada (dataviz): orden fijo, modo claro / oscuro.
    private static final String[] PALETA_CLARA = {"#2a78d6", "#1baf7a", "#eda100", "#008300",
            "#4a3aa7", "#e34948", "#e87ba4", "#eb6834"};
    private static final String[] PALETA_OSCURA = {"#3987e5", "#199e70", "#c98500", "#008300",
            "#9085e9", "#e66767", "#d55181", "#d95926"};

    private static final Pattern LINEA_CONSUMO = Pattern.compile(
            "^(\\d{2}-[A-Za-z]{3}-\\d{2})\\s+(.+?)\\s+(\\d{6})\\s+(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})$");

    public static void main(String[] args) throws Exception {
        Path entrada = Path.of(args.length > 0 ? args[0] : "pdfs");
        Path dirSalida = Path.of(args.length > 1 ? args[1] : "htmls");

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
        for (File pdf : pdfs) {
            try {
                procesar(pdf, dirSalida);
            } catch (Exception e) {
                fallidos++;
                System.err.println("Error procesando " + pdf.getName() + ": " + e.getMessage());
            }
        }
        System.out.printf("%nProcesados %d de %d PDFs -> %s%n",
                pdfs.size() - fallidos, pdfs.size(), dirSalida.toAbsolutePath());
        if (fallidos > 0) System.exit(2);
    }

    static void procesar(File pdf, Path dirSalida) throws Exception {
        String base = pdf.getName().replaceFirst("(?i)\\.pdf$", "");
        Path salida = dirSalida.resolve("gastos-" + base + ".html");

        String texto;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            texto = new PDFTextStripper().getText(doc);
        }

        List<Movimiento> movimientos = parsearConsumos(texto);
        if (movimientos.isEmpty()) {
            throw new IllegalStateException("no se encontraron consumos (¿es un resumen de tarjeta BBVA?)");
        }

        List<Categoria> categorias = agrupar(movimientos.stream().filter(m -> !m.enDolares()).toList());
        List<Movimiento> enDolares = movimientos.stream().filter(Movimiento::enDolares).toList();

        String html = generarHtml(base, movimientos, categorias, enDolares);
        Files.writeString(salida, html, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("=== " + pdf.getName() + " ===");
        imprimirResumen(categorias, enDolares, salida);
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
            resultado.add(new Movimiento(m.group(1), descripcion, m.group(3), importe, usd,
                    categorizar(descripcion)));
        }
        return resultado;
    }

    static String categorizar(String descripcion) {
        String desc = normalizar(descripcion);
        for (Map.Entry<String, List<String>> regla : REGLAS.entrySet()) {
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

    /** Colapsa la cola de categorías en "Otros" para que la torta tenga como máximo 8 porciones. */
    static List<Categoria> paraTorta(List<Categoria> categorias) {
        if (categorias.size() <= MAX_PORCIONES_TORTA) return categorias;
        List<Categoria> torta = new ArrayList<>(categorias.subList(0, MAX_PORCIONES_TORTA - 1));
        BigDecimal resto = BigDecimal.ZERO;
        int ops = 0;
        for (Categoria c : categorias.subList(MAX_PORCIONES_TORTA - 1, categorias.size())) {
            resto = resto.add(c.total());
            ops += c.operaciones();
        }
        torta.add(new Categoria(OTROS, resto, ops));
        return torta;
    }

    // ------------------------------------------------------------------ salida

    static void imprimirResumen(List<Categoria> categorias, List<Movimiento> enDolares, Path salida) {
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
        System.out.println();
        System.out.println("Reporte generado: " + salida.toAbsolutePath());
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

    static String generarHtml(String periodo, List<Movimiento> movimientos,
                              List<Categoria> categorias, List<Movimiento> enDolares) {
        List<Categoria> torta = paraTorta(categorias);
        BigDecimal total = categorias.stream().map(Categoria::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        String svg = generarDonut(torta, total);
        String leyenda = generarLeyenda(torta, total);
        String tablaCategorias = generarTablaCategorias(categorias, total);
        String tablaMovimientos = generarTablaMovimientos(movimientos);
        String seccionUsd = generarSeccionUsd(enDolares);
        String variablesColor = generarVariablesColor();
        String datosOperaciones = generarDatosOperaciones(torta,
                movimientos.stream().filter(m -> !m.enDolares()).toList());

        return plantilla()
                .replace("__DATOS_OPS__", datosOperaciones)
                .replace("__PERIODO__", escaparHtml(periodo))
                .replace("__TOTAL__", formatoMonto(total))
                .replace("__OPERACIONES__", String.valueOf(movimientos.size()))
                .replace("__COLORES__", variablesColor)
                .replace("__DONUT__", svg)
                .replace("__LEYENDA__", leyenda)
                .replace("__TABLA_CATEGORIAS__", tablaCategorias)
                .replace("__TABLA_MOVIMIENTOS__", tablaMovimientos)
                .replace("__SECCION_USD__", seccionUsd);
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
        java.util.Set<String> nombresPrincipales = new java.util.HashSet<>();
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
                    "        <tr><td>%s</td><td class=\"num\">%d</td><td class=\"num\">$ %s</td><td class=\"num\">%.1f%%</td></tr>\n",
                    escaparHtml(c.nombre()), c.operaciones(), formatoMonto(c.total()), porcentaje(c.total(), total)));
        }
        sb.append(String.format(
                "        <tr class=\"fila-total\"><td>Total</td><td class=\"num\"></td><td class=\"num\">$ %s</td><td class=\"num\">100%%</td></tr>\n",
                formatoMonto(total)));
        return sb.toString();
    }

    static String generarTablaMovimientos(List<Movimiento> movimientos) {
        StringBuilder sb = new StringBuilder();
        for (Movimiento m : movimientos) {
            String moneda = m.enDolares() ? "USD" : "$";
            sb.append(String.format(
                    "        <tr><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s %s</td></tr>\n",
                    m.fecha(), escaparHtml(m.descripcion()), escaparHtml(m.categoria()),
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

    static String plantilla() {
        return """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Gastos por categoría — __PERIODO__</title>
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
  .kpis { display: flex; gap: 16px; flex-wrap: wrap; }
  .kpi { flex: 1 1 180px; }
  .kpi .valor { font-size: 2rem; font-weight: 650; }
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
    <p class="secundario">Resumen de tarjeta __PERIODO__ — consumos del período en pesos</p>
  </header>

  <section class="tarjeta kpis">
    <div class="kpi"><div class="valor">$ __TOTAL__</div><div class="rotulo">Total consumos ARS</div></div>
    <div class="kpi"><div class="valor">__OPERACIONES__</div><div class="rotulo">Operaciones</div></div>
  </section>

  <section class="tarjeta">
    <h2>Gastos por categoría</h2>
    <div class="grafico">
__DONUT__
      <ul class="leyenda">
__LEYENDA__      </ul>
    </div>
  </section>

  <section class="tarjeta desplazable">
    <h2>Detalle por categoría</h2>
    <table>
      <thead><tr><th>Categoría</th><th class="num">Operaciones</th><th class="num">Total</th><th class="num">%</th></tr></thead>
      <tbody>
__TABLA_CATEGORIAS__      </tbody>
    </table>
  </section>

__SECCION_USD__
  <section class="tarjeta desplazable">
    <details>
      <summary>Todos los movimientos (__OPERACIONES__)</summary>
      <table>
        <thead><tr><th>Fecha</th><th>Descripción</th><th>Categoría</th><th class="num">Importe</th></tr></thead>
        <tbody>
__TABLA_MOVIMIENTOS__        </tbody>
      </table>
    </details>
  </section>
</main>
<div id="tooltip"></div>
<script id="datos-operaciones" type="application/json">
__DATOS_OPS__
</script>
<script>
  const tooltip = document.getElementById('tooltip');
  const opsPorPorcion = JSON.parse(document.getElementById('datos-operaciones').textContent);

  function escaparTexto(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  document.querySelectorAll('.grafico svg path').forEach(p => {
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
</script>
</body>
</html>
""";
    }
}
