package com.escaner.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Regulador adaptativo de tráfico TCP (Rate Limiter sin bloqueos) alimentado por
 * el modelo matemático de Investigación de Operaciones (X1* y X2*).
 *
 * Espacia en nanosegundos el envío de paquetes hacia puertos críticos (21, 23, 445, 3389)
 * y puertos comunes (22, 53, 80, 443, 8080) para evitar ráfagas que saturen el canal LAN
 * o activen bloqueos en sistemas IDS/IPS corporativos.
 */
@Component
public class AdaptiveRateLimiter {

    // Puertos clasificados como críticos (X1) en la formulación del modelo I.O.
    private static final Set<Integer> CRITICAL_PORTS = Set.of(21, 23, 445, 3389);

    private volatile double x1RatePps = 50.0;
    private volatile double x2RatePps = 50.0;
    private volatile double zMaximo = 125.0;
    private volatile String modoOperacion = "Modo Balanceado (Óptimo estándar)";

    // Intervalo mínimo en nanosegundos entre paquetes consecutivos (1 segundo = 1_000_000_000 ns)
    private volatile long criticalIntervalNanos = 20_000_000L; // 50 pps -> 20 ms entre paquetes
    private volatile long commonIntervalNanos = 20_000_000L;   // 50 pps -> 20 ms entre paquetes
    private volatile long globalIntervalNanos = 10_000_000L;   // 100 pps -> 10 ms (techo total IDS)

    // Marcadores atómicos del siguiente instante libre para emisión uniforme sin ráfagas
    private final AtomicLong nextCriticalTicketNanos = new AtomicLong(0L);
    private final AtomicLong nextCommonTicketNanos = new AtomicLong(0L);
    private final AtomicLong nextGlobalTicketNanos = new AtomicLong(0L);

    // Contadores de telemetría por sesión de escaneo
    private final AtomicInteger criticalPacketsSent = new AtomicInteger(0);
    private final AtomicInteger commonPacketsSent = new AtomicInteger(0);

    public synchronized void updateOptimalRates(double x1Pps, double x2Pps, double zOpt, String modo) {
        double safeX1 = Math.max(5.0, x1Pps);

        // Si el modelo de I.O. asignó tasa positiva a X2 (ej. Sigiloso=20, Balanceado=50, Agresivo=100):
        // se respeta exactamente dicha tasa para los puertos comunes.
        // Si X2* == 0 (esquina del modelo de I.O. cuando la holgura de ancho de banda concentra
        // todo el margen seguro en puertos críticos X1*): los comunes emplean la capacidad
        // remanente del canal (safeX1) cuando la fila crítica no está emitiendo, garantizando
        // un escaneo rápido sin exceder nunca el límite global del firewall (R1).
        double safeX2;
        if (x2Pps >= 5.0) {
            safeX2 = x2Pps;
        } else {
            safeX2 = safeX1;
        }

        double totalPps = Math.max(safeX1, (x2Pps >= 5.0 ? (safeX1 + safeX2) : safeX1));

        this.x1RatePps = safeX1;
        this.x2RatePps = (x2Pps >= 5.0) ? x2Pps : 0.0;
        this.zMaximo = zOpt;
        this.modoOperacion = (modo != null && !modo.isBlank()) ? modo : "Modo Adaptativo";

        this.criticalIntervalNanos = Math.max(250_000L, Math.round(1_000_000_000.0 / safeX1));
        this.commonIntervalNanos = Math.max(250_000L, Math.round(1_000_000_000.0 / safeX2));
        this.globalIntervalNanos = Math.max(250_000L, Math.round(1_000_000_000.0 / totalPps));
    }

    public void resetSessionCounters() {
        long now = System.nanoTime();
        nextCriticalTicketNanos.set(now);
        nextCommonTicketNanos.set(now);
        nextGlobalTicketNanos.set(now);
        criticalPacketsSent.set(0);
        commonPacketsSent.set(0);
    }

    public boolean isCriticalPort(int port) {
        return CRITICAL_PORTS.contains(port);
    }

    /**
     * Solicita permiso regulado antes de abrir un socket TCP hacia el puerto indicado.
     * Si varios hilos solicitan envío al mismo tiempo, cada hilo recibe un turno exacto
     * espaciado según 1 / X1* o 1 / X2* segundos, respetando además el techo global del firewall.
     */
    public void acquirePermitForPort(int port) {
        if (isCriticalPort(port)) {
            criticalPacketsSent.incrementAndGet();
            acquireSmoothSlot(nextCriticalTicketNanos, criticalIntervalNanos);
        } else {
            commonPacketsSent.incrementAndGet();
            acquireSmoothSlot(nextCommonTicketNanos, commonIntervalNanos);
        }
        // Techo de seguridad global (no exceder la tasa total de paquetes del firewall)
        acquireSmoothSlot(nextGlobalTicketNanos, globalIntervalNanos);
    }

    private void acquireSmoothSlot(AtomicLong ticketTracker, long intervalNanos) {
        long waitNanos;
        while (true) {
            long now = System.nanoTime();
            long prevTicket = ticketTracker.get();
            long scheduledTime = Math.max(now, prevTicket);
            long nextTicket = scheduledTime + intervalNanos;
            if (ticketTracker.compareAndSet(prevTicket, nextTicket)) {
                waitNanos = scheduledTime - now;
                break;
            }
        }

        if (waitNanos > 0L) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public double getX1RatePps() {
        return x1RatePps;
    }

    public double getX2RatePps() {
        return x2RatePps;
    }

    public double getZMaximo() {
        return zMaximo;
    }

    public String getModoOperacion() {
        return modoOperacion;
    }

    public int getCriticalPacketsSent() {
        return criticalPacketsSent.get();
    }

    public int getCommonPacketsSent() {
        return commonPacketsSent.get();
    }
}

