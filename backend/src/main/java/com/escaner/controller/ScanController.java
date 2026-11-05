package com.escaner.controller;

import com.escaner.model.OptimizacionRequest;
import com.escaner.service.AdaptiveRateLimiter;
import com.escaner.service.LocalNetworkScannerService;
import com.escaner.service.OptimizacionIoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {

    private final LocalNetworkScannerService scannerService;
    private final OptimizacionIoService optimizacionIoService;
    private final AdaptiveRateLimiter rateLimiter;

    public ScanController(LocalNetworkScannerService scannerService,
                          OptimizacionIoService optimizacionIoService,
                          AdaptiveRateLimiter rateLimiter) {
        this.scannerService = scannerService;
        this.optimizacionIoService = optimizacionIoService;
        this.rateLimiter = rateLimiter;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> startScan(
            @RequestBody(required = false) OptimizacionRequest ioParams,
            org.springframework.security.core.Authentication authentication) {

        String username = authentication != null ? authentication.getName() : null;
        System.out.println("=== RECIBIDA PETICIÓN POST /api/scan POR: " + username + " ===");

        // Si el frontend envía parámetros de optimización I.O., resolver y aplicar al Rate Limiter
        Map<String, Object> ioAplicado = null;
        if (ioParams != null) {
            ioAplicado = optimizacionIoService.calcularOptimo(ioParams, true);
        }

        boolean started = scannerService.startScanAsync(username);
        if (started) {
            System.out.println(String.format(
                    "=== ESCANEO LOCAL INICIADO [Rate Limiter I.O.: X1*=%.1f pps, X2*=%.1f pps, Z*=%.1f] ===",
                    rateLimiter.getX1RatePps(), rateLimiter.getX2RatePps(), rateLimiter.getZMaximo()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "Escaneo iniciado en la interfaz local");
            body.put("x1Optimo", rateLimiter.getX1RatePps());
            body.put("x2Optimo", rateLimiter.getX2RatePps());
            body.put("zMaximo", rateLimiter.getZMaximo());
            body.put("modoOperacion", rateLimiter.getModoOperacion());
            if (ioAplicado != null) {
                body.put("fuenteMotor", ioAplicado.get("fuenteMotor"));
            }
            return ResponseEntity.ok(body);
        } else {
            System.out.println("=== ESCANEO RECHAZADO: YA HAY UNO EN PROGRESO ===");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "Ya hay un escaneo en progreso en la interfaz local"));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "Backend running");
        status.put("scanning", scannerService.isScanning());
        status.put("os", System.getProperty("os.name"));
        status.put("engine", "Native NIC Concurrency Engine + Adaptive I.O. Rate Limiter");
        status.put("x1RatePps", rateLimiter.getX1RatePps());
        status.put("x2RatePps", rateLimiter.getX2RatePps());
        status.put("zMaximo", rateLimiter.getZMaximo());
        status.put("modoIo", rateLimiter.getModoOperacion());
        return ResponseEntity.ok(status);
    }
}
