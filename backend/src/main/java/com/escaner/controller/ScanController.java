package com.escaner.controller;

import com.escaner.service.ArduinoSerialService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ScanController {

    private final ArduinoSerialService arduinoService;

    public ScanController(ArduinoSerialService arduinoService) {
        this.arduinoService = arduinoService;
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> startScan() {
        System.out.println("=== RECIBIDA PETICIÓN POST /api/scan ===");
        arduinoService.sendScanCommand();
        System.out.println("=== COMANDO ENVIADO AL ARDUINO ===");
        return ResponseEntity.ok(Map.of("status", "Scan command sent to Arduino"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        return ResponseEntity.ok(Map.of("status", "Backend running"));
    }
}
