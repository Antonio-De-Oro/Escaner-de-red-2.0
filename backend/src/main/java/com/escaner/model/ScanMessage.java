package com.escaner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanMessage {

    // Tipo de mensaje: "scan_start", "progress", "device", "scan_complete", "error", "status"
    private String type;

    // Métricas del escaneo
    private Integer percent;
    private Integer current;
    private Integer total;
    private Integer found;
    private Long time; // Tiempo en segundos
    private String msg;
    private String gw; // Puerta de enlace / Gateway

    // Datos del dispositivo auditado
    private String ip;
    private String subnetMask;
    private String mac;
    private String vendor;
    private String hostname;
    private String os;
    private Long latencyMs;
    private List<Integer> openPorts = new ArrayList<>();
    private String deviceType;
    private String riskLevel;       // "CRÍTICO", "ALTO", "MEDIO", "BAJO"
    private String riskDescription; // Detalle de la vulnerabilidad o advertencia

    public ScanMessage() {
    }

    public static ScanMessage scanStart(String ip, String gw, String subnetMask, int total, String msg) {
        ScanMessage sm = new ScanMessage();
        sm.setType("scan_start");
        sm.setIp(ip);
        sm.setGw(gw);
        sm.setSubnetMask(subnetMask);
        sm.setTotal(total);
        sm.setCurrent(0);
        sm.setPercent(0);
        sm.setFound(0);
        sm.setMsg(msg);
        return sm;
    }

    public static ScanMessage progress(int percent, int current, int total, int found) {
        ScanMessage sm = new ScanMessage();
        sm.setType("progress");
        sm.setPercent(percent);
        sm.setCurrent(current);
        sm.setTotal(total);
        sm.setFound(found);
        return sm;
    }

    public static ScanMessage scanComplete(long timeSec, int found, int total, String msg) {
        ScanMessage sm = new ScanMessage();
        sm.setType("scan_complete");
        sm.setTime(timeSec);
        sm.setFound(found);
        sm.setTotal(total);
        sm.setPercent(100);
        sm.setMsg(msg);
        return sm;
    }

    public static ScanMessage error(String msg) {
        ScanMessage sm = new ScanMessage();
        sm.setType("error");
        sm.setMsg(msg);
        return sm;
    }

    // Getters y Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getPercent() {
        return percent;
    }

    public void setPercent(Integer percent) {
        this.percent = percent;
    }

    public Integer getCurrent() {
        return current;
    }

    public void setCurrent(Integer current) {
        this.current = current;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getFound() {
        return found;
    }

    public void setFound(Integer found) {
        this.found = found;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getGw() {
        return gw;
    }

    public void setGw(String gw) {
        this.gw = gw;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getSubnetMask() {
        return subnetMask;
    }

    public void setSubnetMask(String subnetMask) {
        this.subnetMask = subnetMask;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public List<Integer> getOpenPorts() {
        return openPorts;
    }

    public void setOpenPorts(List<Integer> openPorts) {
        this.openPorts = openPorts != null ? openPorts : new ArrayList<>();
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getRiskDescription() {
        return riskDescription;
    }

    public void setRiskDescription(String riskDescription) {
        this.riskDescription = riskDescription;
    }
}
