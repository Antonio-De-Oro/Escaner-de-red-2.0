package com.escaner.service;

import com.escaner.entity.Auditoria;
import com.escaner.entity.Dispositivo;
import com.escaner.entity.HistorialChatIa;
import com.escaner.entity.PuertoDispositivo;
import com.escaner.entity.Usuario;
import com.escaner.repository.AuditoriaRepository;
import com.escaner.repository.DispositivoRepository;
import com.escaner.repository.HistorialChatIaRepository;
import com.escaner.repository.UsuarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class GeminiChatService {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatService.class);

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-3.5-flash-lite}")
    private String model;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String apiUrl;

    private final HistorialChatIaRepository historialChatIaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final DispositivoRepository dispositivoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiChatService(HistorialChatIaRepository historialChatIaRepository,
                             AuditoriaRepository auditoriaRepository,
                             DispositivoRepository dispositivoRepository,
                             UsuarioRepository usuarioRepository,
                             ObjectMapper objectMapper) {
        this.historialChatIaRepository = historialChatIaRepository;
        this.auditoriaRepository = auditoriaRepository;
        this.dispositivoRepository = dispositivoRepository;
        this.usuarioRepository = usuarioRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Procesa la consulta del usuario enviando el contexto de la auditoría y el historial conversacional
     * a Google Gemini 3.5 Flash Lite y persiste la interacción en MariaDB.
     */
    @Transactional
    public Map<String, Object> procesarMensaje(String pregunta, Long auditoriaId, String username) {
        Usuario usuario = null;
        if (username != null) {
            usuario = usuarioRepository.findByUsername(username).orElse(null);
        }

        // 1. Obtener la auditoría de referencia (específica o la más reciente)
        Auditoria auditoria = null;
        if (auditoriaId != null) {
            auditoria = auditoriaRepository.findById(auditoriaId).orElse(null);
        }
        if (auditoria == null) {
            List<Auditoria> list = auditoriaRepository.findAll(Sort.by(Sort.Direction.DESC, "fechaInicio"));
            if (!list.isEmpty()) {
                auditoria = list.get(0);
            }
        }

        // 2. Construir el prompt de sistema con el contexto técnico de la red
        String systemInstruction = construirInstruccionSistema(auditoria);

        // 3. Preparar el historial conversacional reciente para contexto multi-turno
        List<Map<String, Object>> contents = new ArrayList<>();
        if (usuario != null) {
            List<HistorialChatIa> historialPrevio = historialChatIaRepository.findByUsuarioIdOrderByFechaHoraAsc(usuario.getId());
            // Tomar los últimos 6 mensajes para no sobrecargar el prompt
            int startIdx = Math.max(0, historialPrevio.size() - 6);
            for (int i = startIdx; i < historialPrevio.size(); i++) {
                HistorialChatIa h = historialPrevio.get(i);
                contents.add(crearTurno("user", h.getPregunta()));
                contents.add(crearTurno("model", h.getRespuesta()));
            }
        }

        // Añadir el mensaje actual del usuario
        contents.add(crearTurno("user", pregunta));

        // 4. Invocar la API de Gemini
        String respuestaIa;
        Integer tokens = null;
        try {
            Map<String, Object> geminiResult = llamarApiGemini(systemInstruction, contents);
            respuestaIa = (String) geminiResult.get("texto");
            tokens = (Integer) geminiResult.get("tokens");
        } catch (Exception e) {
            log.error("Error al comunicarse con la API de Gemini: {}", e.getMessage(), e);
            respuestaIa = generarRespuestaContingencia(pregunta, auditoria, e.getMessage());
        }

        // 5. Persistir en la base de datos MariaDB
        HistorialChatIa registro = new HistorialChatIa(pregunta, respuestaIa, model, usuario, auditoria);
        if (tokens != null) {
            registro.setTokensConsumidos(tokens);
        }
        HistorialChatIa guardado = historialChatIaRepository.save(registro);

        Map<String, Object> response = new HashMap<>();
        response.put("id", guardado.getId());
        response.put("pregunta", guardado.getPregunta());
        response.put("respuesta", guardado.getRespuesta());
        response.put("modelo", model);
        response.put("fechaHora", guardado.getFechaHora().toString());
        return response;
    }

    private Map<String, Object> crearTurno(String role, String text) {
        Map<String, Object> part = new HashMap<>();
        part.put("text", text);

        Map<String, Object> turno = new HashMap<>();
        turno.put("role", role);
        turno.put("parts", List.of(part));
        return turno;
    }

    /**
     * Construye la instrucción del sistema infundiendo el estado y los hallazgos de seguridad de la red.
     */
    private String construirInstruccionSistema(Auditoria auditoria) {
        StringBuilder sb = new StringBuilder();
        sb.append("Eres VulneraBot, el agente inteligente de ciberseguridad y consultoría técnica de la plataforma VulneraSolve.\n");
        sb.append("Tu misión es asesorar a analistas, auditores y administradores de red sobre el estado de seguridad de sus sistemas.\n\n");

        if (auditoria != null) {
            sb.append("=== CONTEXTO DE LA AUDITORÍA DE RED VULNERASOLVE (SESIÓN #").append(auditoria.getId()).append(") ===\n");
            sb.append("Subred: ").append(auditoria.getRangoSubred() != null ? auditoria.getRangoSubred() : "Local").append("\n");
            sb.append("Puerta de enlace (Gateway): ").append(auditoria.getGateway() != null ? auditoria.getGateway() : "192.168.1.254").append("\n");
            sb.append("Dispositivos activos: ").append(auditoria.getHostsActivos()).append("\n\n");

            List<Dispositivo> disps = dispositivoRepository.findByAuditoriaId(auditoria.getId());
            if (!disps.isEmpty()) {
                sb.append("DISPOSITIVOS AUDITADOS Y PUERTOS DETECTADOS:\n");
                for (Dispositivo d : disps) {
                    sb.append("• IP: ").append(d.getIp())
                      .append(" | Nombre: ").append(d.getHostname() != null ? d.getHostname() : "Desconocido")
                      .append(" | SO: ").append(d.getSistemaOperativo() != null ? d.getSistemaOperativo() : "Desconocido")
                      .append(" | Tipo: ").append(d.getTipoDispositivo() != null ? d.getTipoDispositivo() : "Genérico")
                      .append(" | Nivel de riesgo: ").append(d.getNivelRiesgo());

                    List<PuertoDispositivo> puertos = d.getPuertos();
                    if (puertos != null && !puertos.isEmpty()) {
                        sb.append(" | Puertos abiertos: ");
                        List<String> pList = new ArrayList<>();
                        for (PuertoDispositivo p : puertos) {
                            String crit = p.isEsCritico() ? " [CRÍTICO]" : "";
                            pList.add(p.getNumeroPuerto() + "/" + p.getProtocolo() + " (" + p.getServicioIdentificado() + ")" + crit);
                        }
                        sb.append(String.join(", ", pList));
                    } else {
                        sb.append(" | Sin puertos abiertos reportados");
                    }
                    sb.append("\n");
                }
            }
        } else {
            sb.append("Actualmente no hay ninguna auditoría registrada en la base de datos de VulneraSolve. ");
            sb.append("Recomienda al usuario realizar un escaneo desde el panel principal.\n");
        }

        sb.append("\n=== DIRECTRICES DE RESPUESTA DE VULNERABOT ===\n");
        sb.append("1. Si el usuario te pregunta cómo solucionar, mitigar o remediar puertos críticos encontrados (por ejemplo SMB 445, Telnet 23, FTP 21, RDP 3389, HTTP 80):\n");
        sb.append("   - Explica brevemente por qué representa un riesgo crítico en una red local (vectores de ataque, ransomware, intercepción en texto claro, etc.).\n");
        sb.append("   - Proporciona una guía de remediación práctica con pasos numerados.\n");
        sb.append("   - Incluye comandos específicos según el sistema operativo detectado (ej. comandos PowerShell / CMD para Windows, o comandos bash / systemctl / ufw / iptables para Linux).\n");
        sb.append("   - Menciona la IP y el equipo específico involucrado en la red para que la respuesta sea 100% personalizada y útil para el auditor.\n");
        sb.append("2. Si te preguntan sobre buenas prácticas generales, protocolos de hardening, segmentación de red o conceptos de ciberseguridad, responde con rigor técnico pero redacción comprensible.\n");
        sb.append("3. Utiliza formato Markdown limpio (títulos con ###, listas, negrita para énfasis y bloques ```powershell / ```bash para comandos de remediación).\n");
        sb.append("4. Mantén un tono profesional, experto y colaborativo.\n");
        sb.append("5. RESTRICCIÓN ESTRICTA DE DOMINIO Y AHORRO DE TOKENS (GUARDRAIL):\n");
        sb.append("   - Tu alcance está estricta y exclusivamente limitado a: ciberseguridad, redes informáticas, protocolos, puertos, administración de sistemas, auditoría de vulnerabilidades y el funcionamiento de la plataforma VulneraSolve.\n");
        sb.append("   - Si el usuario te hace preguntas ajenas a estos temas (por ejemplo: fútbol, deportes, cuándo juega un equipo, farándula, entretenimiento, cocina, clima, temas personales, política, etc.), TIENES ESTRICTAMENTE PROHIBIDO responder o desarrollar el tema.\n");
        sb.append("   - En esos casos no relacionados, responde únicamente con una o dos oraciones breves y corteses (máximo 40 palabras) indicando que como agente VulneraBot estás especializado exclusivamente en ciberseguridad y auditoría de redes para VulneraSolve, e invita al usuario a consultar sobre los equipos, puertos o vulnerabilidades de su red.\n");

        return sb.toString();
    }

    /**
     * Realiza la llamada HTTP POST a la API oficial de Google Gemini.
     */
    private Map<String, Object> llamarApiGemini(String systemInstruction, List<Map<String, Object>> contents) throws Exception {
        String effectiveKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : System.getenv("GEMINI_API_KEY");
        if (effectiveKey == null || effectiveKey.isBlank()) {
            throw new IllegalStateException("No se ha configurado la clave API de Gemini (GEMINI_API_KEY).");
        }

        String endpoint = String.format("%s/%s:generateContent?key=%s", apiUrl, model, effectiveKey);

        Map<String, Object> requestBody = new HashMap<>();

        // Instrucción del sistema
        Map<String, Object> sysPart = new HashMap<>();
        sysPart.put("text", systemInstruction);
        Map<String, Object> sysInstructionMap = new HashMap<>();
        sysInstructionMap.put("parts", List.of(sysPart));
        requestBody.put("system_instruction", sysInstructionMap);

        // Configuración de generación para control de tokens y precisión técnica
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("maxOutputTokens", 1200);
        generationConfig.put("temperature", 0.4);
        requestBody.put("generationConfig", generationConfig);

        // Turnos de conversación
        requestBody.put("contents", contents);

        String jsonPayload = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Error HTTP " + response.statusCode() + " al consultar Gemini: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode candidate = candidates.get(0);
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray() && !parts.isEmpty()) {
                String text = parts.get(0).path("text").asText();
                Integer totalTokens = root.path("usageMetadata").path("totalTokenCount").asInt(0);

                Map<String, Object> result = new HashMap<>();
                result.put("texto", text);
                result.put("tokens", totalTokens > 0 ? totalTokens : null);
                return result;
            }
        }

        throw new RuntimeException("Formato de respuesta inesperado de Gemini: " + response.body());
    }

    /**
     * Generador de respuesta de contingencia en caso de desconexión a internet o error en API.
     */
    private String generarRespuestaContingencia(String pregunta, Auditoria auditoria, String errorMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ **Modo de contingencia local de VulneraBot**\n\n");
        sb.append("No se pudo contactar temporalmente al servicio en la nube de Google Gemini (`").append(errorMsg).append("`).\n\n");
        sb.append("A continuación te presento la recomendación técnica basada en la base de conocimiento local de VulneraSolve:\n\n");

        if (pregunta.toLowerCase().contains("445") || pregunta.toLowerCase().contains("smb")) {
            sb.append("### Mitigación para el puerto 445 (SMB)\n");
            sb.append("1. **Deshabilitar SMBv1**: Ejecuta en PowerShell como Administrador:\n");
            sb.append("   ```powershell\n   Disable-WindowsOptionalFeature -Online -FeatureName SMB1Protocol\n   ```\n");
            sb.append("2. **Bloquear en el Firewall**: Si el equipo no requiere compartir archivos en red local:\n");
            sb.append("   ```powershell\n   New-NetFirewallRule -DisplayName \"Bloquear SMB 445\" -Direction Inbound -LocalPort 445 -Protocol TCP -Action Block\n   ```\n");
        } else if (pregunta.toLowerCase().contains("23") || pregunta.toLowerCase().contains("telnet")) {
            sb.append("### Mitigación para el puerto 23 (Telnet)\n");
            sb.append("1. **Deshabilitar el servicio Telnet**: Al ser un protocolo en texto plano sin cifrado, debe reemplazarse inmediatamente por SSH (puerto 22).\n");
            sb.append("2. **En equipos Linux / Routers**:\n");
            sb.append("   ```bash\n   sudo systemctl stop telnet\n   sudo systemctl disable telnet\n   ```\n");
        } else {
            sb.append("Por favor asegúrate de contar con acceso a internet y una clave de API válida para Google Gemini 3.5 Flash Lite configurada en `GEMINI_API_KEY`.\n");
        }

        return sb.toString();
    }

    /**
     * Consulta el historial conversacional del usuario actual.
     */
    public List<Map<String, Object>> obtenerHistorial(String username) {
        if (username == null) return Collections.emptyList();
        Usuario usuario = usuarioRepository.findByUsername(username).orElse(null);
        if (usuario == null) return Collections.emptyList();

        List<HistorialChatIa> historial = historialChatIaRepository.findByUsuarioIdOrderByFechaHoraAsc(usuario.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (HistorialChatIa h : historial) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", h.getId());
            map.put("pregunta", h.getPregunta());
            map.put("respuesta", h.getRespuesta());
            map.put("modelo", h.getModelo());
            map.put("fechaHora", h.getFechaHora().toString());
            result.add(map);
        }
        return result;
    }

    /**
     * Limpia el historial de chat del usuario actual.
     */
    @Transactional
    public void limpiarHistorial(String username) {
        if (username == null) return;
        Usuario usuario = usuarioRepository.findByUsername(username).orElse(null);
        if (usuario != null) {
            historialChatIaRepository.deleteByUsuarioId(usuario.getId());
        }
    }
}
