package com.escaner.controller;

import com.escaner.entity.Auditoria;
import com.escaner.entity.Dispositivo;
import com.escaner.entity.PuertoDispositivo;
import com.escaner.repository.AuditoriaRepository;
import com.escaner.repository.DispositivoRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/auditorias")
@CrossOrigin(origins = "*")
public class AuditoriaController {

    private final AuditoriaRepository auditoriaRepository;
    private final DispositivoRepository dispositivoRepository;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AuditoriaController(AuditoriaRepository auditoriaRepository, DispositivoRepository dispositivoRepository) {
        this.auditoriaRepository = auditoriaRepository;
        this.dispositivoRepository = dispositivoRepository;
    }

    /**
     * Retorna la lista cronológica de auditorías con métricas consolidadas.
     * Accesible tanto por Administradores como por Auditores.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getAuditorias() {
        List<Auditoria> list = auditoriaRepository.findAllByOrderByFechaInicioDesc();
        List<Map<String, Object>> response = new ArrayList<>();

        for (Auditoria a : list) {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", a.getId());
            dto.put("fechaInicio", a.getFechaInicio() != null ? a.getFechaInicio().format(FORMATTER) : "-");
            dto.put("fechaFin", a.getFechaFin() != null ? a.getFechaFin().format(FORMATTER) : "-");
            dto.put("rangoSubred", a.getRangoSubred() != null ? a.getRangoSubred() : "-");
            dto.put("gateway", a.getGateway() != null ? a.getGateway() : "-");
            dto.put("totalHostsAnalizados", a.getTotalHostsAnalizados() != null ? a.getTotalHostsAnalizados() : 254);
            dto.put("hostsActivos", a.getHostsActivos() != null ? a.getHostsActivos() : 0);
            dto.put("duracionSegundos", a.getDuracionSegundos() != null ? a.getDuracionSegundos() : 0);
            dto.put("estado", a.getEstado() != null ? a.getEstado() : "COMPLETADO");
            dto.put("operador", a.getUsuario() != null ? a.getUsuario().getUsername() : "Sistema");

            List<Dispositivo> disps = dispositivoRepository.findByAuditoriaId(a.getId());
            long criticos = disps.stream().filter(d -> "CRÍTICO".equalsIgnoreCase(d.getNivelRiesgo())).count();
            dto.put("totalDispositivos", disps.size());
            dto.put("amenazasCriticas", criticos);

            response.add(dto);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Retorna los detalles y hallazgos completos de una auditoría para inspección forense.
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAuditoriaDetalle(@PathVariable Long id) {
        Optional<Auditoria> opt = auditoriaRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Auditoria a = opt.get();
        List<Dispositivo> disps = dispositivoRepository.findByAuditoriaId(id);

        Map<String, Object> response = new HashMap<>();
        response.put("id", a.getId());
        response.put("fechaInicio", a.getFechaInicio() != null ? a.getFechaInicio().format(FORMATTER) : "-");
        response.put("fechaFin", a.getFechaFin() != null ? a.getFechaFin().format(FORMATTER) : "-");
        response.put("rangoSubred", a.getRangoSubred());
        response.put("subnetMask", a.getRangoSubred());
        response.put("gateway", a.getGateway());
        response.put("totalHostsAnalizados", a.getTotalHostsAnalizados());
        response.put("hostsActivos", a.getHostsActivos());
        long criticos = disps.stream().filter(d -> "CRÍTICO".equalsIgnoreCase(d.getNivelRiesgo())).count();
        response.put("amenazasCriticas", criticos);
        response.put("duracionSegundos", a.getDuracionSegundos());
        response.put("estado", a.getEstado());
        response.put("operador", a.getUsuario() != null ? a.getUsuario().getUsername() : "Sistema");

        // Mapeo estructurado para compatibilidad directa con el visualizador de VulneraSolve
        List<Map<String, Object>> deviceList = new ArrayList<>();
        for (Dispositivo d : disps) {
            Map<String, Object> devMap = new HashMap<>();
            devMap.put("id", d.getId());
            devMap.put("ip", d.getIp());
            devMap.put("mac", d.getMac());
            devMap.put("vendor", d.getFabricante());
            devMap.put("hostname", d.getHostname());
            devMap.put("os", d.getSistemaOperativo());
            devMap.put("deviceType", d.getTipoDispositivo());
            devMap.put("latencyMs", d.getLatenciaMs());
            devMap.put("riskLevel", d.getNivelRiesgo());
            devMap.put("riskDescription", d.getDescripcionRiesgo());
            devMap.put("detectedAt", a.getFechaInicio() != null ? a.getFechaInicio().format(FORMATTER) : "-");

            List<Integer> openPorts = new ArrayList<>();
            List<Map<String, Object>> puertosList = new ArrayList<>();
            if (d.getPuertos() != null) {
                for (PuertoDispositivo p : d.getPuertos()) {
                    openPorts.add(p.getNumeroPuerto());
                    Map<String, Object> portMap = new HashMap<>();
                    portMap.put("numeroPuerto", p.getNumeroPuerto());
                    portMap.put("protocolo", p.getProtocolo());
                    portMap.put("servicioIdentificado", p.getServicioIdentificado());
                    portMap.put("esCritico", p.isEsCritico());
                    puertosList.add(portMap);
                }
            }
            devMap.put("openPorts", openPorts);
            devMap.put("puertos", puertosList);

            deviceList.add(devMap);
        }

        response.put("dispositivos", deviceList);
        return ResponseEntity.ok(response);
    }
}
