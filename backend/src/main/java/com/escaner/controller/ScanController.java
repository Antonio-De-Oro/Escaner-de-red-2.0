package com.escaner.controller;

import com.escaner.service.LocalNetworkScannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> startScan() {
        System.out.println("=== RECIBIDA PETICIÓN POST /api/scan ===");
        boolean started = scannerService.startScanAsync();
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
