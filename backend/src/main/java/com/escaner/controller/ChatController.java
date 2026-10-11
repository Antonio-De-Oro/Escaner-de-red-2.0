package com.escaner.controller;

import com.escaner.service.GeminiChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final GeminiChatService geminiChatService;

    public ChatController(GeminiChatService geminiChatService) {
        this.geminiChatService = geminiChatService;
    }

    /**
     * Endpoint para enviar una pregunta o consulta al agente inteligente VulneraBot.
     */
    @PostMapping("/ask")
    public ResponseEntity<?> enviarPregunta(@RequestBody Map<String, Object> payload,
                                            Authentication authentication) {
        String pregunta = (String) payload.get("pregunta");
        if (pregunta == null || pregunta.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "La pregunta o consulta no puede estar vacía."));
        }

        Long auditoriaId = null;
        if (payload.get("auditoriaId") != null) {
            try {
                auditoriaId = Long.valueOf(payload.get("auditoriaId").toString());
            } catch (NumberFormatException ignored) {
            }
        }

        String username = (authentication != null) ? authentication.getName() : null;
        Map<String, Object> respuesta = geminiChatService.procesarMensaje(pregunta.trim(), auditoriaId, username);
        return ResponseEntity.ok(respuesta);
    }

    /**
     * Retorna el historial de conversaciones del usuario autenticado actual.
     */
    @GetMapping("/historial")
    public ResponseEntity<List<Map<String, Object>>> obtenerHistorial(Authentication authentication) {
        String username = (authentication != null) ? authentication.getName() : null;
        return ResponseEntity.ok(geminiChatService.obtenerHistorial(username));
    }

    /**
     * Limpia el historial de conversaciones del usuario autenticado actual.
     */
    @DeleteMapping("/historial")
    public ResponseEntity<?> limpiarHistorial(Authentication authentication) {
        String username = (authentication != null) ? authentication.getName() : null;
        geminiChatService.limpiarHistorial(username);
        return ResponseEntity.ok(Map.of("mensaje", "Historial de conversación eliminado con éxito."));
    }
}

