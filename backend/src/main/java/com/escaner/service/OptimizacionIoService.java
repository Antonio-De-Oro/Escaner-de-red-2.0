package com.escaner.service;

import com.escaner.model.OptimizacionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

/**
 * Servicio puente entre Spring Boot y el microservicio Python de Investigación de Operaciones
 * (`motor-io/optimizador_io.py`). Gestiona la comunicación HTTP REST, el autoarranque opcional
 * del motor Python y un respaldo analítico local en caso de contingencia.
 */
@Service
public class OptimizacionIoService {

    private static final Logger log = LoggerFactory.getLogger(OptimizacionIoService.class);
    private static final double EPSILON = 1e-7;

    @Value("${io.service.url:http://127.0.0.1:8001}")
    private String ioServiceUrl;

    @Value("${io.service.auto-start:true}")
    private boolean autoStartPython;

    private final ObjectMapper objectMapper;
    private final AdaptiveRateLimiter rateLimiter;
    private final HttpClient httpClient;
    private Process managedPythonProcess;

    public OptimizacionIoService(ObjectMapper objectMapper, AdaptiveRateLimiter rateLimiter) {
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1200))
                .build();
    }

    @PostConstruct
    public void inicializarConexionMicroservicio() {
        if (verificarSaludMicroservicio()) {
            log.info("[MOTOR-IO] Conectado a instancia activa del microservicio Python en {}", ioServiceUrl);
            return;
        }

        if (autoStartPython) {
            intentarIniciarMicroservicioPython();
        }
    }

    private void intentarIniciarMicroservicioPython() {
        try {
            Path scriptPath = localizarScriptPython();
            if (scriptPath == null) {
                log.warn("[MOTOR-IO] No se encontró motor-io/optimizador_io.py para autoarranque; se usará conexión bajo demanda o respaldo local.");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder("python", "-X", "utf8", scriptPath.toAbsolutePath().toString());
            pb.directory(scriptPath.getParent().toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            this.managedPythonProcess = pb.start();

            // Esperar brevemente hasta que el socket HTTP del microservicio responda
            for (int i = 0; i < 10; i++) {
                Thread.sleep(150);
                if (verificarSaludMicroservicio()) {
                    log.info("[MOTOR-IO] Microservicio Python iniciado automáticamente desde {}", scriptPath);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("[MOTOR-IO] No fue posible auto-iniciar el proceso Python ({}). Respaldo analítico disponible.", e.getMessage());
        }
    }

    private Path localizarScriptPython() {
        List<Path> candidatos = List.of(
                Paths.get("..", "motor-io", "optimizador_io.py"),
                Paths.get("motor-io", "optimizador_io.py"),
                Paths.get(System.getProperty("user.dir"), "..", "motor-io", "optimizador_io.py"),
                Paths.get(System.getProperty("user.dir"), "motor-io", "optimizador_io.py")
        );
        for (Path p : candidatos) {
            Path norm = p.toAbsolutePath().normalize();
            if (Files.exists(norm) && Files.isRegularFile(norm)) {
                return norm;
            }
        }
        return null;
    }

    public boolean verificarSaludMicroservicio() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ioServiceUrl + "/api/salud"))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Consulta al microservicio Python (`POST /api/optimizar`) para obtener las tasas
     * óptimas X1*, X2* y Z*. Si el microservicio no está encendido, resuelve el mismo
     * sistema lineal localmente para mantener continuidad operativa al 100%.
     */
    public Map<String, Object> calcularOptimo(OptimizacionRequest request, boolean aplicarAlEscaner) {
        OptimizacionRequest safeReq = normalizarSolicitud(request);
        Map<String, Object> resultado;

        try {
            String jsonBody = objectMapper.writeValueAsString(safeReq);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(ioServiceUrl + "/api/optimizar"))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                resultado = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            } else {
                throw new IllegalStateException("Código HTTP inesperado del motor Python: " + response.statusCode());
            }
        } catch (Exception ex) {
            log.warn("[MOTOR-IO] Microservicio Python no disponible ({}); resolviendo con motor analítico de respaldo en Java.", ex.getMessage());
            resultado = resolverRespaldoJava(safeReq);
        }

        if (aplicarAlEscaner) {
            double x1 = ((Number) resultado.getOrDefault("x1Optimo", 50.0)).doubleValue();
            double x2 = ((Number) resultado.getOrDefault("x2Optimo", 50.0)).doubleValue();
            double z = ((Number) resultado.getOrDefault("zMaximo", 125.0)).doubleValue();
            String modo = String.valueOf(resultado.getOrDefault("modoOperacion", "Modo Adaptativo"));
            rateLimiter.updateOptimalRates(x1, x2, z, modo);
        }

        return resultado;
    }

    public Map<String, Object> obtenerEstadoActual() {
        boolean pythonActivo = verificarSaludMicroservicio();
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("microservicioActivo", pythonActivo);
        estado.put("urlMicroservicio", ioServiceUrl);
        estado.put("fuenteMotor", pythonActivo ? "MICROSERVICIO_PYTHON" : "MOTOR_RESPALDO_JAVA");
        estado.put("x1ActivoPps", rateLimiter.getX1RatePps());
        estado.put("x2ActivoPps", rateLimiter.getX2RatePps());
        estado.put("zActivo", rateLimiter.getZMaximo());
        estado.put("modoActivo", rateLimiter.getModoOperacion());
        return estado;
    }

    private OptimizacionRequest normalizarSolicitud(OptimizacionRequest req) {
        OptimizacionRequest out = new OptimizacionRequest();
        if (req == null) {
            return out;
        }
        double ids = req.getLimiteIdsPps() != null ? req.getLimiteIdsPps() : 100.0;
        double bw = req.getAnchoBandaKbs() != null ? req.getAnchoBandaKbs() : 150.0;
        double pct = req.getPorcentajeCriticoMin() != null ? req.getPorcentajeCriticoMin() : 0.40;
        if (pct > 1.0) {
            pct = pct / 100.0;
        }
        double c1 = req.getPesoCritico() != null ? req.getPesoCritico() : 1.5;
        double c2 = req.getPesoComun() != null ? req.getPesoComun() : 1.0;

        out.setLimiteIdsPps(Math.max(10.0, Math.min(2000.0, ids)));
        out.setAnchoBandaKbs(Math.max(20.0, Math.min(5000.0, bw)));
        out.setPorcentajeCriticoMin(Math.max(0.05, Math.min(0.95, pct)));
        out.setPesoCritico(Math.max(0.1, Math.min(20.0, c1)));
        out.setPesoComun(Math.max(0.1, Math.min(20.0, c2)));
        return out;
    }

    /**
     * Respaldo analítico idéntico por intersección de vértices (Regla de Cramer)
     * para garantizar alta disponibilidad si el proceso externo en Python se detiene.
     */
    private Map<String, Object> resolverRespaldoJava(OptimizacionRequest p) {
        double ids = p.getLimiteIdsPps();
        double bw = p.getAnchoBandaKbs();
        double alfa = p.getPorcentajeCriticoMin();
        double c1 = p.getPesoCritico();
        double c2 = p.getPesoComun();

        double[][] r = {
                {1.0, 1.0, ids},
                {2.0, 1.0, bw},
                {-(1.0 - alfa), alfa, 0.0},
                {-1.0, 0.0, 0.0},
                {0.0, -1.0, 0.0}
        };
        String[] codes = {"R1", "R2", "R3", "R4", "R5"};

        List<Map<String, Object>> vertices = new ArrayList<>();
        double bestX1 = 0.0;
        double bestX2 = 0.0;
        double bestZ = -1.0;
        String bestInter = "Origen";

        for (int i = 0; i < r.length; i++) {
            for (int j = i + 1; j < r.length; j++) {
                double det = r[i][0] * r[j][1] - r[i][1] * r[j][0];
                if (Math.abs(det) < EPSILON) continue;

                double x1 = (r[i][2] * r[j][1] - r[i][1] * r[j][2]) / det;
                double x2 = (r[i][0] * r[j][2] - r[i][2] * r[j][0]) / det;
                if (Math.abs(x1) < EPSILON) x1 = 0.0;
                if (Math.abs(x2) < EPSILON) x2 = 0.0;

                boolean factible = true;
                for (double[] c : r) {
                    if (c[0] * x1 + c[1] * x2 > c[2] + EPSILON) {
                        factible = false;
                        break;
                    }
                }
                if (!factible) continue;

                boolean dup = false;
                for (Map<String, Object> v : vertices) {
                    double vx1 = ((Number) v.get("x1")).doubleValue();
                    double vx2 = ((Number) v.get("x2")).doubleValue();
                    if (Math.abs(vx1 - x1) < 1e-3 && Math.abs(vx2 - x2) < 1e-3) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    double z = c1 * x1 + c2 * x2;
                    String inter = codes[i] + " ∩ " + codes[j];
                    Map<String, Object> vMap = new LinkedHashMap<>();
                    vMap.put("indice", vertices.size() + 1);
                    vMap.put("x1", redondear(x1, 2));
                    vMap.put("x2", redondear(x2, 2));
                    vMap.put("z", redondear(z, 2));
                    vMap.put("interseccion", inter);
                    vertices.add(vMap);

                    if (z > bestZ + EPSILON || (Math.abs(z - bestZ) <= EPSILON && x1 > bestX1)) {
                        bestZ = z;
                        bestX1 = x1;
                        bestX2 = x2;
                        bestInter = inter;
                    }
                }
            }
        }

        if (bestZ < 0) {
            bestZ = 0.0;
        }

        double totalPps = bestX1 + bestX2;
        double consumoKbs = 2.0 * bestX1 + 1.0 * bestX2;
        double consumoMbps = (consumoKbs * 8.0) / 1000.0;
        double limiteMbps = (bw * 8.0) / 1000.0;
        double usoIds = ids > 0 ? (totalPps / ids) * 100.0 : 0.0;
        double usoCanal = bw > 0 ? (consumoKbs / bw) * 100.0 : 0.0;
        double propCrit = totalPps > 0 ? (bestX1 / totalPps) * 100.0 : 0.0;

        String modo = "Modo Personalizado (Adaptativo)";
        if (ids <= 65 && bw <= 100) {
            modo = "Modo Sigiloso (Red sensible)";
        } else if (Math.abs(ids - 100.0) < 1.0 && Math.abs(bw - 150.0) < 1.0) {
            modo = "Modo Balanceado (Óptimo estándar)";
        } else if (ids >= 160 || bw >= 250) {
            modo = "Modo Agresivo (Alto rendimiento)";
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("exito", true);
        res.put("fuenteMotor", "MOTOR_RESPALDO_JAVA");
        res.put("microservicioActivo", false);
        res.put("modoOperacion", modo);
        res.put("x1Optimo", redondear(bestX1, 2));
        res.put("x2Optimo", redondear(bestX2, 2));
        res.put("paquetesTotalesPps", redondear(totalPps, 2));
        res.put("zMaximo", redondear(bestZ, 2));
        res.put("consumoAnchoBandaKbs", redondear(consumoKbs, 2));
        res.put("consumoAnchoBandaMbps", redondear(consumoMbps, 3));
        res.put("limiteAnchoBandaKbs", redondear(bw, 2));
        res.put("limiteAnchoBandaMbps", redondear(limiteMbps, 3));
        res.put("limiteIdsPps", redondear(ids, 2));
        res.put("porcentajeCriticoMin", redondear(alfa * 100.0, 1));
        res.put("proporcionCriticaReal", redondear(propCrit, 1));
        res.put("usoIdsPorcentaje", redondear(usoIds, 1));
        res.put("usoCanalPorcentaje", redondear(usoCanal, 1));
        res.put("pesoCritico", redondear(c1, 2));
        res.put("pesoComun", redondear(c2, 2));
        res.put("interseccionOptima", bestInter);
        res.put("interseccionAmigable", traducirInterseccionAmigable(bestInter));
        res.put("verticesFactibles", vertices);
        if (bestX2 < 0.5 && bw >= (2.0 * ids)) {
            res.put("explicacion", String.format(Locale.US,
                    "En resumen: tu red tiene ancho de banda de sobra, por lo que el único tope activo es el firewall: "
                    + "el modelo dedica los %s pps (paquetes por segundo) a priorizar puertos peligrosos "
                    + "(revisando los comunes con ritmo ligero de respaldo) para lograr %s puntos usando solo %s KB/s (%.2f Mbps). "
                    + "Si tu firewall lo permite, sube la barra 1 para aprovechar aún más tu red.",
                    formatearLimpio(bestX1), formatearLimpio(bestZ), formatearLimpio(consumoKbs), consumoMbps));
        } else {
            res.put("explicacion", String.format(Locale.US,
                    "En resumen: el escáner enviará %s pps (paquetes por segundo) a puertos peligrosos y %s pps a puertos normales (%s pps en total). "
                    + "Así consigue el mejor aprovechamiento posible (%s puntos de efectividad) ocupando apenas %s KB/s (%.2f Mbps) de tu red local, "
                    + "sin poner lento el internet ni provocar bloqueos del firewall (%s%% del margen seguro).",
                    formatearLimpio(bestX1), formatearLimpio(bestX2), formatearLimpio(totalPps),
                    formatearLimpio(bestZ), formatearLimpio(consumoKbs), consumoMbps, formatearLimpio(usoIds)));
        }
        return res;
    }

    private String formatearLimpio(double val) {
        if (Math.abs(val - Math.round(val)) < 0.05) {
            return String.valueOf(Math.round(val));
        }
        return String.format(Locale.US, "%.1f", val);
    }

    private String traducirInterseccionAmigable(String inter) {
        if (inter == null) return "Punto óptimo dentro de tus límites seguros";
        if (inter.contains("R1") && inter.contains("R2")) return "Equilibrio ideal entre firewall y red";
        if (inter.contains("R1") && inter.contains("R3")) return "Equilibrio entre firewall y prioridad crítica";
        if (inter.contains("R2") && inter.contains("R3")) return "Equilibrio entre red y prioridad crítica";
        if (inter.contains("R2") && inter.contains("X2>=0")) return "Máximo enfoque crítico permitido por tu red";
        if (inter.contains("R1") && inter.contains("X2>=0")) return "Máximo enfoque crítico permitido por el firewall";
        return "Punto óptimo dentro de tus límites seguros";
    }

    private double redondear(double val, int decimales) {
        double factor = Math.pow(10, decimales);
        return Math.round(val * factor) / factor;
    }

    @PreDestroy
    public void detenerMicroservicioGestionado() {
        if (managedPythonProcess != null && managedPythonProcess.isAlive()) {
            log.info("[MOTOR-IO] Cerrando proceso secundario del microservicio Python...");
            managedPythonProcess.destroy();
        }
    }
}

