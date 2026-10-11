package com.escaner.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "puertos_dispositivo")
public class PuertoDispositivo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "numero_puerto", nullable = false)
    private Integer numeroPuerto;

    @Column(length = 20)
    private String protocolo = "TCP";

    @Column(name = "servicio_identificado", length = 150)
    private String servicioIdentificado;

    @Column(name = "es_critico")
    private boolean esCritico = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispositivo_id", nullable = false)
    @JsonIgnore
    private Dispositivo dispositivo;

    public PuertoDispositivo() {
    }

    public PuertoDispositivo(Integer numeroPuerto, String protocolo, String servicioIdentificado, boolean esCritico, Dispositivo dispositivo) {
        this.numeroPuerto = numeroPuerto;
        this.protocolo = protocolo != null ? protocolo : "TCP";
        this.servicioIdentificado = servicioIdentificado;
        this.esCritico = esCritico;
        this.dispositivo = dispositivo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getNumeroPuerto() {
        return numeroPuerto;
    }

    public void setNumeroPuerto(Integer numeroPuerto) {
        this.numeroPuerto = numeroPuerto;
    }

    public String getProtocolo() {
        return protocolo;
    }

    public void setProtocolo(String protocolo) {
        this.protocolo = protocolo;
    }

    public String getServicioIdentificado() {
        return servicioIdentificado;
    }

    public void setServicioIdentificado(String servicioIdentificado) {
        this.servicioIdentificado = servicioIdentificado;
    }

    public boolean isEsCritico() {
        return esCritico;
    }

    public void setEsCritico(boolean esCritico) {
        this.esCritico = esCritico;
    }

    public Dispositivo getDispositivo() {
        return dispositivo;
    }

    public void setDispositivo(Dispositivo dispositivo) {
        this.dispositivo = dispositivo;
    }
}

