package com.escaner.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "historial_chat_ia")
public class HistorialChatIa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String pregunta;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String respuesta;

    @Column(length = 60)
    private String modelo = "gemini-3.5-flash-lite";

    @Column(name = "tokens_consumidos")
    private Integer tokensConsumidos;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auditoria_id")
    private Auditoria auditoria;

    public HistorialChatIa() {
    }

    public HistorialChatIa(String pregunta, String respuesta, String modelo, Usuario usuario, Auditoria auditoria) {
        this.pregunta = pregunta;
        this.respuesta = respuesta;
        this.modelo = modelo != null ? modelo : "gemini-3.5-flash-lite";
        this.usuario = usuario;
        this.auditoria = auditoria;
        this.fechaHora = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPregunta() {
        return pregunta;
    }

    public void setPregunta(String pregunta) {
        this.pregunta = pregunta;
    }

    public String getRespuesta() {
        return respuesta;
    }

    public void setRespuesta(String respuesta) {
        this.respuesta = respuesta;
    }

    public String getModelo() {
        return modelo;
    }

    public void setModelo(String modelo) {
        this.modelo = modelo;
    }

    public Integer getTokensConsumidos() {
        return tokensConsumidos;
    }

    public void setTokensConsumidos(Integer tokensConsumidos) {
        this.tokensConsumidos = tokensConsumidos;
    }

    public LocalDateTime getFechaHora() {
        return fechaHora;
    }

    public void setFechaHora(LocalDateTime fechaHora) {
        this.fechaHora = fechaHora;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public Auditoria getAuditoria() {
        return auditoria;
    }

    public void setAuditoria(Auditoria auditoria) {
        this.auditoria = auditoria;
    }
}

