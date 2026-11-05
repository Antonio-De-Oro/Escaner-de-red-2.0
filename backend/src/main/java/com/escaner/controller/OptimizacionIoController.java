package com.escaner.controller;

import com.escaner.model.OptimizacionRequest;
import com.escaner.service.OptimizacionIoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador REST que expone al Frontend el cálculo en tiempo real del modelo de
 * Investigación de Operaciones (conectado al microservicio Python en `motor-io/`).
 */
@RestController
@RequestMapping("/api/io")
@CrossOrigin(origins = "*")
public class OptimizacionIoController {

    private final OptimizacionIoService optimizacionIoService;

    public OptimizacionIoController(OptimizacionIoService optimizacionIoService) {
        this.optimizacionIoService = optimizacionIoService;
    }

    @GetMapping("/estado")
    public ResponseEntity<Map<String, Object>> obtenerEstado() {
        return ResponseEntity.ok(optimizacionIoService.obtenerEstadoActual());
    }

    @PostMapping("/optimizar")
    public ResponseEntity<Map<String, Object>> optimizarTrafico(
            @RequestBody(required = false) OptimizacionRequest request,
            Authentication authentication) {

        boolean esAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        // Los auditores pueden simular el modelo libremente en pantalla;
        // solo los administradores actualizan la tasa activa del motor de escaneo.
        Map<String, Object> solucion = optimizacionIoService.calcularOptimo(request, esAdmin);
        return ResponseEntity.ok(solucion);
    }

}

