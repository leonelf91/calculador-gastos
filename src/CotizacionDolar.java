import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Proceso batch: consulta al Banco Nación la cotización del dólar billete de
 * los últimos 90 días y genera en docs/index.html una página (publicable como
 * GitHub Page) con la evolución diaria del valor de venta, un monto fijo en
 * pesos y su equivalente en dólares a la cotización del día de procesamiento.
 *
 * Uso: java CotizacionDolar
 */
public class CotizacionDolar {

    record Cotizacion(LocalDate fecha, BigDecimal venta) {}

    private static final String BNA_BASE = "https://www.bna.com.ar/Cotizador";
    private static final String ID_TABLA = "billetes";
    private static final String ID_MONEDA_DOLAR = "22";
    private static final int DIAS_HISTORIA = 90;

    private static final BigDecimal MONTO_ARS = new BigDecimal("113680000");

    private static final Path SALIDA = Path.of("docs", "index.html");

    // La página publicada va cifrada con AES-256-GCM; la clave se deriva con
    // PBKDF2 de la frase guardada en ARCHIVO_CLAVE (local, ignorado por git).
    private static final Path ARCHIVO_CLAVE = Path.of("clave.txt");
    private static final int ITERACIONES_PBKDF2 = 600_000;

    private static final DateTimeFormatter FECHA_BNA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_CSV = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter FECHA_CORTA = DateTimeFormatter.ofPattern("dd/MM");

    // Geometría del SVG del gráfico (coordenadas del viewBox).
    private static final int SVG_ANCHO = 940, SVG_ALTO = 420;
    private static final int MARGEN_IZQ = 70, MARGEN_DER = 24, MARGEN_SUP = 18, MARGEN_INF = 40;

    public static void main(String[] args) throws Exception {
        LocalDate hasta = LocalDate.now();
        LocalDate desde = hasta.minusDays(DIAS_HISTORIA);
        HttpClient http = clienteHttp();

        if (!hayCotizaciones(http, desde, hasta)) {
            System.err.println("El BNA informa que no hay cotizaciones en el rango "
                    + FECHA_BNA.format(desde) + " - " + FECHA_BNA.format(hasta));
            System.exit(1);
        }

        List<Cotizacion> cotizaciones = descargarCotizaciones(http, desde, hasta);
        if (cotizaciones.isEmpty()) {
            System.err.println("La descarga del BNA no trajo cotizaciones.");
            System.exit(1);
        }

        Cotizacion ultima = cotizaciones.get(cotizaciones.size() - 1);
        BigDecimal montoUsd = MONTO_ARS.divide(ultima.venta(), 2, RoundingMode.HALF_UP);

        System.out.printf("Cotizaciones obtenidas: %d (del %s al %s)%n", cotizaciones.size(),
                FECHA_BNA.format(cotizaciones.get(0).fecha()), FECHA_BNA.format(ultima.fecha()));
        System.out.printf("Venta del día de procesamiento (%s): %s%n",
                FECHA_BNA.format(ultima.fecha()), pesos2().format(ultima.venta()));
        System.out.printf("Monto %s ARS = %s USD%n",
                pesos0().format(MONTO_ARS), dolares().format(montoUsd));

        String html = generarHtml(cotizaciones, ultima, montoUsd, hasta);
        String clave = leerOCrearClave();
        Cifrado cifrado = encriptar(html, clave);
        String pagina = plantillaLoader()
                .replace("__SALT__", cifrado.salt())
                .replace("__IV__", cifrado.iv())
                .replace("__DATOS__", cifrado.datos())
                .replace("__ITERACIONES__", String.valueOf(ITERACIONES_PBKDF2));
        Files.createDirectories(SALIDA.getParent());
        Files.writeString(SALIDA, pagina, StandardCharsets.UTF_8);
        System.out.println("Página generada (cifrada con la clave de "
                + ARCHIVO_CLAVE + "): " + SALIDA.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // Cifrado de la página
    // ------------------------------------------------------------------

    record Cifrado(String salt, String iv, String datos) {}

    private static String leerOCrearClave() throws Exception {
        if (Files.exists(ARCHIVO_CLAVE)) {
            String clave = Files.readString(ARCHIVO_CLAVE, StandardCharsets.UTF_8).trim();
            if (!clave.isEmpty()) return clave;
        }
        byte[] azar = new byte[12];
        new SecureRandom().nextBytes(azar);
        String clave = Base64.getUrlEncoder().withoutPadding().encodeToString(azar);
        Files.writeString(ARCHIVO_CLAVE, clave + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("Se generó una clave nueva en " + ARCHIVO_CLAVE.toAbsolutePath()
                + " (editable; no se versiona)");
        return clave;
    }

    private static Cifrado encriptar(String html, String clave) throws Exception {
        SecureRandom azar = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        azar.nextBytes(salt);
        azar.nextBytes(iv);

        SecretKeyFactory fabrica = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] llave = fabrica.generateSecret(
                new PBEKeySpec(clave.toCharArray(), salt, ITERACIONES_PBKDF2, 256)).getEncoded();
        Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
        cifrador.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(llave, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] datos = cifrador.doFinal(html.getBytes(StandardCharsets.UTF_8));

        Base64.Encoder b64 = Base64.getEncoder();
        return new Cifrado(b64.encodeToString(salt), b64.encodeToString(iv),
                b64.encodeToString(datos));
    }

    // ------------------------------------------------------------------
    // BNA
    // ------------------------------------------------------------------

    private static boolean hayCotizaciones(HttpClient http, LocalDate desde, LocalDate hasta)
            throws Exception {
        return Boolean.parseBoolean(get(http, urlCotizador("HayCotizacionesEnRango", desde, hasta)).trim());
    }

    private static List<Cotizacion> descargarCotizaciones(HttpClient http, LocalDate desde,
                                                          LocalDate hasta) throws Exception {
        String csv = get(http, urlCotizador("DescargarPorFecha", desde, hasta));

        // Formato: Moneda;Fecha cotizacion;Compra;Venta;
        List<Cotizacion> cotizaciones = new ArrayList<>();
        for (String linea : csv.split("\r?\n")) {
            String[] campos = linea.trim().split(";");
            if (campos.length < 4 || !campos[1].trim().matches("\\d{1,2}/\\d{1,2}/\\d{4}")) continue;
            LocalDate fecha = LocalDate.parse(campos[1].trim(), FECHA_CSV);
            BigDecimal venta = new BigDecimal(campos[3].trim().replace(".", "").replace(',', '.'));
            cotizaciones.add(new Cotizacion(fecha, venta));
        }
        cotizaciones.sort(Comparator.comparing(Cotizacion::fecha));
        return cotizaciones;
    }

    private static String urlCotizador(String metodo, LocalDate desde, LocalDate hasta) {
        return BNA_BASE + "/" + metodo + "?id=" + ID_TABLA
                + "&fechaDesde=" + URLEncoder.encode(FECHA_BNA.format(desde), StandardCharsets.UTF_8)
                + "&fechaHasta=" + URLEncoder.encode(FECHA_BNA.format(hasta), StandardCharsets.UTF_8)
                + "&idMonedaDescarga=" + ID_MONEDA_DOLAR;
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " en " + url);
        }
        return response.body();
    }

    // El proxy corporativo intercepta TLS con un certificado que la JDK no
    // conoce, así que se acepta cualquier certificado del servidor.
    private static HttpClient clienteHttp() throws Exception {
        TrustManager[] confiarTodo = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, confiarTodo, new SecureRandom());
        return HttpClient.newBuilder().sslContext(ssl)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    // ------------------------------------------------------------------
    // Página HTML
    // ------------------------------------------------------------------

    private static String generarHtml(List<Cotizacion> cotizaciones, Cotizacion ultima,
                                      BigDecimal montoUsd, LocalDate procesado) {
        double min = cotizaciones.stream().mapToDouble(c -> c.venta().doubleValue()).min().orElse(0);
        double max = cotizaciones.stream().mapToDouble(c -> c.venta().doubleValue()).max().orElse(1);
        double paso = pasoLindo((max - min) / 5);
        double yMin = Math.floor(min / paso) * paso - paso / 2;
        double yMax = Math.ceil(max / paso) * paso + paso / 2;

        long diaInicial = cotizaciones.get(0).fecha().toEpochDay();
        long diaFinal = ultima.fecha().toEpochDay();
        int plotAncho = SVG_ANCHO - MARGEN_IZQ - MARGEN_DER;
        int plotAlto = SVG_ALTO - MARGEN_SUP - MARGEN_INF;

        // Grilla horizontal recesiva + rótulos del eje Y. Las coordenadas SVG
        // se formatean con Locale.ROOT: el punto decimal es parte de la sintaxis.
        StringBuilder grilla = new StringBuilder();
        for (double v = Math.ceil(yMin / paso) * paso; v <= yMax + 0.001; v += paso) {
            double y = MARGEN_SUP + plotAlto - (v - yMin) / (yMax - yMin) * plotAlto;
            grilla.append(svg("      <line class=\"grilla\" x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\"/>%n",
                    MARGEN_IZQ, y, MARGEN_IZQ + plotAncho, y));
            grilla.append(svg("      <text class=\"eje\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>%n",
                    MARGEN_IZQ - 10, y + 4, pesos0().format(v)));
        }

        // Marcas del eje X (~6 fechas equiespaciadas).
        int n = cotizaciones.size();
        int cadaCuantos = Math.max(1, (int) Math.ceil(n / 6.0));
        StringBuilder ejeX = new StringBuilder();
        for (int i = 0; i < n; i += cadaCuantos) {
            Cotizacion c = cotizaciones.get(i);
            double x = xDe(c.fecha().toEpochDay(), diaInicial, diaFinal, plotAncho);
            ejeX.append(svg("      <text class=\"eje\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>%n",
                    x, MARGEN_SUP + plotAlto + 26, FECHA_CORTA.format(c.fecha())));
        }

        // Línea de la serie y datos precalculados para el tooltip.
        StringBuilder puntosLinea = new StringBuilder();
        StringBuilder datosJs = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Cotizacion c = cotizaciones.get(i);
            double x = xDe(c.fecha().toEpochDay(), diaInicial, diaFinal, plotAncho);
            double y = MARGEN_SUP + plotAlto - (c.venta().doubleValue() - yMin) / (yMax - yMin) * plotAlto;
            puntosLinea.append(i == 0 ? "M" : " L").append(svg("%.1f %.1f", x, y));
            if (i > 0) datosJs.append(",");
            BigDecimal usdDia = MONTO_ARS.divide(c.venta(), 2, RoundingMode.HALF_UP);
            datosJs.append(svg("{x:%.1f,y:%.1f,f:\"%s\",v:\"%s\",u:\"%s\"}",
                    x, y, FECHA_BNA.format(c.fecha()), pesos2().format(c.venta()),
                    dolares().format(usdDia)));
        }

        double ux = xDe(diaFinal, diaInicial, diaFinal, plotAncho);
        double uy = MARGEN_SUP + plotAlto
                - (ultima.venta().doubleValue() - yMin) / (yMax - yMin) * plotAlto;

        return plantilla()
                .replace("__RANGO__", FECHA_BNA.format(cotizaciones.get(0).fecha())
                        + " – " + FECHA_BNA.format(ultima.fecha()))
                .replace("__PROCESADO__", FECHA_BNA.format(procesado))
                .replace("__GRILLA__", grilla.toString())
                .replace("__EJE_X__", ejeX.toString())
                .replace("__LINEA__", puntosLinea.toString())
                .replace("__ULTIMO_X__", svg("%.1f", ux))
                .replace("__ULTIMO_Y__", svg("%.1f", uy))
                .replace("__ETIQUETA_ULTIMO__", pesos2().format(ultima.venta()))
                .replace("__FECHA_ULTIMA__", FECHA_BNA.format(ultima.fecha()))
                .replace("__VENTA_ULTIMA__", pesos2().format(ultima.venta()))
                .replace("__MONTO_ARS__", pesos0().format(MONTO_ARS))
                .replace("__MONTO_USD__", dolares().format(montoUsd))
                .replace("__DATOS__", datosJs.toString());
    }

    // Formatea coordenadas SVG/JS siempre con punto decimal, ignorando el
    // locale del sistema (es-AR usa coma y rompería la sintaxis).
    private static String svg(String patron, Object... args) {
        return String.format(Locale.ROOT, patron, args);
    }

    private static double xDe(long dia, long diaInicial, long diaFinal, int plotAncho) {
        return MARGEN_IZQ + (dia - diaInicial) / (double) (diaFinal - diaInicial) * plotAncho;
    }

    // Redondea un paso de eje a 1, 2 o 5 por la potencia de diez que corresponda.
    private static double pasoLindo(double crudo) {
        if (crudo <= 0) return 1;
        double potencia = Math.pow(10, Math.floor(Math.log10(crudo)));
        double base = crudo / potencia;
        double lindo = base <= 1 ? 1 : base <= 2 ? 2 : base <= 5 ? 5 : 10;
        return lindo * potencia;
    }

    private static DecimalFormat pesos0() { return formato("$ #,##0"); }

    private static DecimalFormat pesos2() { return formato("$ #,##0.00"); }

    private static DecimalFormat dolares() { return formato("US$ #,##0.00"); }

    private static DecimalFormat formato(String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat(patron, simbolos);
    }

    // Página publicada: solo contiene el reporte cifrado y el formulario de
    // clave; el descifrado (PBKDF2 + AES-GCM) corre en el navegador.
    static String plantillaLoader() {
        return """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dólar BNA — acceso</title>
<style>
  :root {
    --superficie: #fcfcfb;
    --plano: #f9f9f7;
    --tinta: #0b0b0b;
    --tinta-secundaria: #52514e;
    --borde: rgba(11,11,11,0.10);
    --acento: #2a78d6;
    --error: #b3261e;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --superficie: #1a1a19;
      --plano: #0d0d0d;
      --tinta: #ffffff;
      --tinta-secundaria: #c3c2b7;
      --borde: rgba(255,255,255,0.10);
      --acento: #3987e5;
      --error: #ff8a80;
    }
  }
  * { box-sizing: border-box; margin: 0; }
  body {
    font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
    background: var(--plano); color: var(--tinta);
    min-height: 100vh; display: grid; place-items: center; padding: 24px;
  }
  form {
    background: var(--superficie); border: 1px solid var(--borde);
    border-radius: 12px; padding: 28px; width: min(380px, 100%);
    display: grid; gap: 14px;
  }
  h1 { font-size: 1.2rem; }
  p { color: var(--tinta-secundaria); font-size: 0.9rem; }
  .fila { display: flex; gap: 10px; }
  input {
    flex: 1; min-width: 0; padding: 9px 12px; font-size: 1rem;
    border: 1px solid var(--borde); border-radius: 8px;
    background: var(--plano); color: var(--tinta);
  }
  button {
    padding: 9px 18px; font-size: 1rem; border: none; border-radius: 8px;
    background: var(--acento); color: #fff; cursor: pointer;
  }
  #error { color: var(--error); }
  [hidden] { display: none; }
</style>
</head>
<body>
<form id="form">
  <h1>Página protegida</h1>
  <p>Ingresá la clave para ver la cotización del dólar.</p>
  <div class="fila">
    <input type="password" id="clave" placeholder="Clave" autofocus autocomplete="current-password">
    <button>Ver</button>
  </div>
  <p id="error" hidden>Clave incorrecta.</p>
</form>
<script>
  const P = {salt: "__SALT__", iv: "__IV__", datos: "__DATOS__", iteraciones: __ITERACIONES__};
  const aBytes = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));

  async function descifrar(clave) {
    const material = await crypto.subtle.importKey(
        'raw', new TextEncoder().encode(clave), 'PBKDF2', false, ['deriveKey']);
    const llave = await crypto.subtle.deriveKey(
        {name: 'PBKDF2', salt: aBytes(P.salt), iterations: P.iteraciones, hash: 'SHA-256'},
        material, {name: 'AES-GCM', length: 256}, false, ['decrypt']);
    const plano = await crypto.subtle.decrypt(
        {name: 'AES-GCM', iv: aBytes(P.iv)}, llave, aBytes(P.datos));
    return new TextDecoder().decode(plano);
  }

  async function intentar(clave, avisarError) {
    try {
      const html = await descifrar(clave);
      document.open();
      document.write(html);
      document.close();
    } catch (e) {
      if (avisarError) document.getElementById('error').hidden = false;
    }
  }

  document.getElementById('form').addEventListener('submit', ev => {
    ev.preventDefault();
    intentar(document.getElementById('clave').value, true);
  });
  // Acceso directo: la clave puede ir como query param (?clave=...) o en el
  // fragmento (#clave); el fragmento no se envía al servidor, el query sí.
  const claveUrl = new URLSearchParams(location.search).get('clave')
      || (location.hash.length > 1 ? decodeURIComponent(location.hash.slice(1)) : null);
  if (claveUrl) intentar(claveUrl, false);
</script>
</body>
</html>
""";
    }

    static String plantilla() {
        return """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dólar BNA — últimos 90 días</title>
<style>
  :root {
    --superficie: #fcfcfb;
    --plano: #f9f9f7;
    --tinta: #0b0b0b;
    --tinta-secundaria: #52514e;
    --tinta-tenue: #898781;
    --grilla: #e1e0d9;
    --borde: rgba(11,11,11,0.10);
    --serie-1: #2a78d6;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --superficie: #1a1a19;
      --plano: #0d0d0d;
      --tinta: #ffffff;
      --tinta-secundaria: #c3c2b7;
      --tinta-tenue: #898781;
      --grilla: #2c2c2a;
      --borde: rgba(255,255,255,0.10);
      --serie-1: #3987e5;
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
  .tarjeta svg { width: 100%; height: auto; display: block; }
  .grilla { stroke: var(--grilla); stroke-width: 1; }
  .eje { font-size: 13px; fill: var(--tinta-secundaria); }
  .serie { fill: none; stroke: var(--serie-1); stroke-width: 2.5; stroke-linejoin: round; stroke-linecap: round; }
  .punto-final { fill: var(--serie-1); stroke: var(--superficie); stroke-width: 2; }
  .etiqueta-final { font-size: 14px; font-weight: 700; fill: var(--tinta); }
  .cruz { stroke: var(--tinta-tenue); stroke-width: 1; stroke-dasharray: 3 3; }
  .punto-hover { fill: var(--serie-1); stroke: var(--superficie); stroke-width: 2; }
  .kpi + .kpi { margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--grilla); }
  .kpi .valor { font-size: 2rem; font-weight: 650; font-variant-numeric: tabular-nums; }
  .kpi .rotulo { color: var(--tinta-tenue); font-size: 0.8rem; text-transform: uppercase; letter-spacing: .04em; }
  #tooltip {
    position: fixed; pointer-events: none; display: none; z-index: 10;
    background: var(--tinta); color: var(--superficie);
    padding: 8px 11px; border-radius: 8px; font-size: 0.8rem;
  }
  #tooltip strong { display: block; font-variant-numeric: tabular-nums; }
  #tooltip .usd {
    display: block; margin-top: 4px; padding-top: 4px; font-size: 0.72rem; opacity: .88;
    border-top: 1px solid color-mix(in srgb, var(--superficie) 25%, transparent);
    font-variant-numeric: tabular-nums;
  }
</style>
</head>
<body>
<main>
  <header>
    <h1>Dólar U.S.A billete — venta (BNA)</h1>
    <p class="secundario">Últimos 90 días: __RANGO__ · Fuente: Banco de la Nación Argentina · Procesado el __PROCESADO__</p>
  </header>

  <section class="tarjeta">
    <h2>Evolución diaria del valor de venta</h2>
    <svg id="grafico" viewBox="0 0 940 420" role="img"
         aria-label="Evolución diaria de la cotización de venta del dólar billete del BNA en los últimos 90 días">
__GRILLA____EJE_X__      <path class="serie" d="__LINEA__"/>
      <circle class="punto-final" cx="__ULTIMO_X__" cy="__ULTIMO_Y__" r="4.5"/>
      <text class="etiqueta-final" x="__ULTIMO_X__" y="__ULTIMO_Y__" dx="-8" dy="-12" text-anchor="end">__ETIQUETA_ULTIMO__</text>
      <g id="hover" style="display:none">
        <line class="cruz" id="cruz" y1="18" y2="380"/>
        <circle class="punto-hover" id="punto-hover" r="4.5"/>
      </g>
    </svg>
  </section>

  <section class="tarjeta">
    <div class="kpi">
      <div class="valor">__MONTO_ARS__ ARS</div>
      <div class="rotulo">Monto en pesos</div>
    </div>
    <div class="kpi">
      <div class="valor">__MONTO_USD__</div>
      <div class="rotulo">Equivalente en dólares — venta del __FECHA_ULTIMA__: __VENTA_ULTIMA__</div>
    </div>
  </section>
</main>

<div id="tooltip"></div>

<script>
  const datos = [__DATOS__];
  const svg = document.getElementById('grafico');
  const hover = document.getElementById('hover');
  const cruz = document.getElementById('cruz');
  const puntoHover = document.getElementById('punto-hover');
  const tooltip = document.getElementById('tooltip');

  svg.addEventListener('mousemove', ev => {
    const rect = svg.getBoundingClientRect();
    const xSvg = (ev.clientX - rect.left) * 940 / rect.width;
    let cercano = datos[0];
    for (const d of datos) {
      if (Math.abs(d.x - xSvg) < Math.abs(cercano.x - xSvg)) cercano = d;
    }
    cruz.setAttribute('x1', cercano.x);
    cruz.setAttribute('x2', cercano.x);
    puntoHover.setAttribute('cx', cercano.x);
    puntoHover.setAttribute('cy', cercano.y);
    hover.style.display = '';
    tooltip.innerHTML = cercano.f + '<strong>' + cercano.v + '</strong>'
        + '<span class="usd">__MONTO_ARS__ = ' + cercano.u + '</span>';
    tooltip.style.display = 'block';
    tooltip.style.left = Math.min(ev.clientX + 14, window.innerWidth - tooltip.offsetWidth - 8) + 'px';
    tooltip.style.top = (ev.clientY - 40) + 'px';
  });
  svg.addEventListener('mouseleave', () => {
    hover.style.display = 'none';
    tooltip.style.display = 'none';
  });
</script>
</body>
</html>
""";
    }
}
