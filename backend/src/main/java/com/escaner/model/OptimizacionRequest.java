package com.escaner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO de entrada con las restricciones operativas seleccionadas en el Frontend
 * para resolver el modelo de Programación Lineal (Investigación de Operaciones).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptimizacionRequest {

    private Double limiteIdsPps = 100.0;
    private Double anchoBandaKbs = 150.0;
    private Double porcentajeCriticoMin = 0.40;
    private Double pesoCritico = 1.5;
    private Double pesoComun = 1.0;

    public OptimizacionRequest() {
    }

    public Double getLimiteIdsPps() {
        return limiteIdsPps;
    }

    public void setLimiteIdsPps(Double limiteIdsPps) {
        this.limiteIdsPps = limiteIdsPps;
    }

    public Double getAnchoBandaKbs() {
        return anchoBandaKbs;
    }

    public void setAnchoBandaKbs(Double anchoBandaKbs) {
        this.anchoBandaKbs = anchoBandaKbs;
    }

    public Double getPorcentajeCriticoMin() {
        return porcentajeCriticoMin;
    }

    public void setPorcentajeCriticoMin(Double porcentajeCriticoMin) {
        this.porcentajeCriticoMin = porcentajeCriticoMin;
    }

    public Double getPesoCritico() {
        return pesoCritico;
    }

    public void setPesoCritico(Double pesoCritico) {
        this.pesoCritico = pesoCritico;
    }

    public Double getPesoComun() {
        return pesoComun;
    }

    public void setPesoComun(Double pesoComun) {
        this.pesoComun = pesoComun;
    }
}

