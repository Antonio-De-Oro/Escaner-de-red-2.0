package com.escaner.controller;

import com.escaner.service.LocalNetworkScannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {

    private final LocalNetworkScannerService scannerService;

    public ScanController(LocalNetworkScannerService scannerService) {
        this.scannerService = scannerService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> startScan(org.springframework.security.core.Authentication authentication) {
        String username = authentication != null ? authentication.getName() : null;
        System.out.println("=== RECIBIDA PETICIÓN POST /api/scan POR: " + username + " ===");
        boolean started = scannerService.startScanAsync(username);
        if (started) {
            System.out.println("=== ESCANEO LOCAL INICIADO EXITOSAMENTE ===");
            return ResponseEntity.ok(Map.of("status", "Escaneo iniciado en la interfaz local"));
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
        status.put("engine", "Native NIC Concurrency Engine");
        return ResponseEntity.ok(status);
    }
}
