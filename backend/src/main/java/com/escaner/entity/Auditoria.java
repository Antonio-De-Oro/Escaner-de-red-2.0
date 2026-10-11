package com.escaner.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "auditorias")
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDateTime fechaInicio = LocalDateTime.now();

    @Column(name = "fecha_fin")
    private LocalDateTime fechaFin;

    @Column(name = "rango_subred", length = 50)
    private String rangoSubred;

    @Column(length = 45)
    private String gateway;

    @Column(name = "total_hosts_analizados")
    private Integer totalHostsAnalizados;

    @Column(name = "hosts_activos")
    private Integer hostsActivos = 0;

    @Column(name = "duracion_segundos")
    private Long duracionSegundos;

    @Column(length = 30)
    private String estado = "COMPLETADO";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @OneToMany(mappedBy = "auditoria", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Dispositivo> dispositivos = new ArrayList<>();

    public Auditoria() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getFechaInicio() {
        return fechaInicio;
    }

    public void setFechaInicio(LocalDateTime fechaInicio) {
        this.fechaInicio = fechaInicio;
    }

    public LocalDateTime getFechaFin() {
        return fechaFin;
    }

    public void setFechaFin(LocalDateTime fechaFin) {
        this.fechaFin = fechaFin;
    }

    public String getRangoSubred() {
        return rangoSubred;
    }

    public void setRangoSubred(String rangoSubred) {
        this.rangoSubred = rangoSubred;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public Integer getTotalHostsAnalizados() {
        return totalHostsAnalizados;
    }

    public void setTotalHostsAnalizados(Integer totalHostsAnalizados) {
        this.totalHostsAnalizados = totalHostsAnalizados;
    }

    public Integer getHostsActivos() {
        return hostsActivos;
    }

    public void setHostsActivos(Integer hostsActivos) {
        this.hostsActivos = hostsActivos;
    }

    public Long getDuracionSegundos() {
        return duracionSegundos;
    }

    public void setDuracionSegundos(Long duracionSegundos) {
        this.duracionSegundos = duracionSegundos;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public List<Dispositivo> getDispositivos() {
        return dispositivos;
    }

    public void setDispositivos(List<Dispositivo> dispositivos) {
        this.dispositivos = dispositivos;
    }

    public void addDispositivo(Dispositivo dispositivo) {
        dispositivos.add(dispositivo);
        dispositivo.setAuditoria(this);
    }
}

