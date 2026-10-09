package com.escaner.service;

import com.escaner.model.ScanMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LocalNetworkScannerService {

    private final SimpMessagingTemplate messagingTemplate;

    @Value("${scanner.thread-pool-size:30}")
    private int threadPoolSize;

    @Value("${scanner.ping-timeout-ms:400}")
    private int pingTimeoutMs;

    @Value("${scanner.port-timeout-ms:250}")
    private int portTimeoutMs;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private ExecutorService scanExecutor;
    private final ExecutorService dnsExecutor = Executors.newCachedThreadPool();

    // Puertos TCP estándar a auditar de manera no intrusiva
    private static final int[] AUDIT_PORTS = {21, 22, 23, 53, 80, 443, 445, 3389, 8080};

    // Catálogo OUI de fabricantes reconocidos (primeros 6 caracteres hexadecimales)
    private static final Map<String, String> OUI_VENDORS = new HashMap<>();

    static {
        // Apple
        registerVendors("Apple", "000393", "000502", "000A27", "000A95", "0010FA", "001124", "001451", "0016CB", "0017F2", "0019E3", "001B63", "001CB3", "001D4F", "001E52", "001EC2", "0021E9", "002241", "002312", "002332", "00236C", "002436", "002500", "00254B", "0025BC", "002608", "00264A", "0026B0", "0026BB", "28CFE9", "3C0754", "406C8F", "442A60", "4860BC", "4C3275", "542696", "5855CA", "600308", "64B0A6", "685B35", "6C4008", "701124", "705681", "784F43", "7CD1C3", "80EA96", "843835", "88665A", "8C8590", "90FD61", "9801A7", "A483E7", "AC87A3", "ACBC32", "B817C2", "BC52B7", "C869CD", "CC08E0", "D023DB", "D89695", "E0B9BA", "E4E0C5", "F01898", "F4F15A", "F81EDF", "FCFC48");
        // Samsung
        registerVendors("Samsung", "0000F0", "0007AB", "001247", "0012FB", "001377", "001599", "0015B9", "001632", "00166C", "0017C9", "0017D5", "0018AF", "001A8A", "001B98", "001C43", "001D25", "001E7D", "001FCC", "002119", "002339", "002399", "002454", "002491", "002567", "002637", "5001D9", "5492BE", "5C0A5B", "647791", "6C8336", "78471D", "8425DB", "88329B", "9401C2", "94350A", "A80600", "B0EC71", "B407F9", "BC4486", "C4731E", "CC07AB", "D0176A", "E458E7", "EC107B", "F47B5E", "FCA13E");
        // Intel
        registerVendors("Intel", "0002B3", "000347", "000423", "0007E9", "000E0C", "001111", "001302", "001320", "0013E8", "001500", "001676", "0018DE", "0019D1", "001B21", "001CC0", "001DE0", "001E67", "001F3B", "00215C", "0022FA", "002314", "0024D7", "0026C6", "00270E", "002710", "3413E8", "4851B7", "4C79BA", "58946B", "6805CA", "7C5CF8", "8086F2", "887556", "8C705A", "A0369F", "A44CC8", "B49691", "C85B76", "D83BBF", "E4A471", "F8633F", "FC7774");
        // TP-Link
        registerVendors("TP-Link", "0019E0", "002127", "0023CD", "002586", "002719", "14CF92", "14CC20", "18A6F7", "1C3BF3", "30B5C2", "50C7BF", "54C80F", "60E327", "6466B3", "647002", "704F57", "74DA38", "74E6B8", "7844FD", "7C8BCA", "8416F9", "90F652", "984827", "A0F3C1", "AC84C6", "B0487A", "B09575", "C006C3", "C025E9", "C04A00", "C46E1F", "C83A35", "D80D17", "D84732", "E4C32A", "E848B8", "EC086B", "EC888F", "F4EC38", "F81A67");
        // Cisco
        registerVendors("Cisco", "00000C", "000142", "000143", "000163", "000164", "000196", "000197", "0001C7", "0001C9", "000216", "000217", "00023D", "00024A", "00024B", "00027D", "00027E", "0002B9", "0002BA", "0002FC", "0002FD", "000331", "000332", "00036B", "00036C", "00039F", "0003A0", "0003E3", "0003E4", "00044D", "00044E", "00049A", "00049B", "0004C0", "0004C1", "0004DD", "0004DE", "000500", "000501", "000531", "000532", "000573", "000574", "00059A", "00059B", "0005DC", "0005DD");
        // Espressif (IoT)
        registerVendors("Espressif (IoT)", "18FE34", "240AC4", "2462AB", "246F28", "24B2DE", "2CF432", "30AEA4", "3C6105", "3C71BF", "4022D8", "483FDA", "485519", "545AA6", "5CCF7F", "600194", "68C63A", "70039F", "782184", "7CDFA1", "840D8E", "84F3EB", "9097D5", "94B555", "94B97E", "A47B9D", "A4CF12", "A4E57C", "AC67B2", "B4E62D", "BCDDC2", "C44F33", "C4DD57", "CC50E3", "D8A01D", "DC4F22", "EC6260", "ECFABC");
        // Huawei
        registerVendors("Huawei", "001882", "001E10", "00259E", "00464B", "0425C5", "0819A6", "0C96BF", "104780", "10C61F", "18C58A", "2008ED", "24DF6A", "286ED4", "3400A3", "384C4F", "404D8E", "4846FB", "548998", "582AF7", "60E701", "68A0F6", "707BE8", "78D752", "80B686", "845B12", "8853D4", "8C34FD", "94049C", "A49947", "AC4E91", "B48B19", "BC7670", "C4F081", "CC96A0", "D46E5C", "DCD2FC", "E8CD2D", "F09838", "F84ABF", "FC48EF");
        // Dell
        registerVendors("Dell", "00065B", "000874", "000BDB", "000D56", "001143", "00123F", "001372", "001422", "0016F0", "00188B", "0019B9", "001AA0", "001C23", "001D09", "001E4F", "001EC9", "002170", "00219B", "002219", "0023AE", "0024E8", "002564", "0026B9", "14FEB5", "180373", "1866DA", "24B6FD", "3417EB", "44A842", "549F35", "74867A", "847BEB", "90B11C", "A41F72", "B82A72", "BC305B", "C81F66", "D067E5", "D481D7", "E0DB55", "F01FAF", "F8DB88");
        // HP
        registerVendors("HP", "0001E6", "0002A5", "0004EA", "000802", "000883", "000BCD", "000E7F", "000F20", "001083", "00110A", "001185", "001279", "001321", "001438", "0014C2", "001560", "001635", "001708", "0017A4", "001871", "0018FE", "0019BB", "001A4B", "001B78", "001CC4", "001E0B", "001F29", "00215A", "002264", "00237D", "002481", "0025B3", "002655", "10604B", "18A958", "28924A", "308D99", "38EAA7", "3CD92B", "5820B1", "645106", "705A0F", "843497", "9457A5", "A0D3C1", "B499BA", "C8D3FF", "D48564", "E83935", "FC15B4");
        // Raspberry Pi
        registerVendors("Raspberry Pi", "B827EB", "DCA632", "E45F01", "28CDC1");
        // Xiaomi
        registerVendors("Xiaomi", "009EC8", "04CF8C", "0C9838", "14F65A", "18F0E4", "2034FB", "286C07", "3480B3", "38A4ED", "40313C", "4C49E3", "50642B", "584498", "640980", "64CC2E", "68DFDD", "742344", "7802F8", "7C1DD9", "8CBEBE", "98FAE3", "9C99A0", "A4C361", "ACC1EE", "B0E235", "B46BFC", "C40BCB", "CC04B4", "D4970B", "DC2919", "E0DCFF", "E4AAEC", "F0B429", "F4F524", "FC643A");
        // Hitron Technologies (Router)
        registerVendors("Hitron Technologies (Router)", "206A94", "441EA1", "90F1AA", "A09353", "BCF685", "F866F2");
        // ASUS
        registerVendors("ASUS", "000C6E", "00112F", "0013D4", "0015F2", "001731", "0018F3", "001A92", "001BFC", "001D60", "001E8C", "002215", "002354", "00248C", "002618", "049226", "04D4C4", "08606E", "086266", "107B44", "10BF48", "14DDA9", "1C872C", "2C4D54", "305A3A", "382C4A", "40167E", "50465D", "54A050", "6045CB", "60A44C", "704D7B", "74D02B", "7824AF", "88D7F6", "9C5C8E", "A85E45", "AC220B", "AC9E17", "BCEE7B", "C86000", "D850E6", "E03F49", "F07959");
        // Netgear
        registerVendors("Netgear", "00095B", "000FB5", "00146C", "00184D", "001B2F", "001E2A", "001F33", "00223F", "0024B2", "0026F2", "08028E", "100C6B", "10DA43", "204E7F", "288088", "2C3033", "30469A", "4494FC", "4C60DE", "6CB0CE", "744401", "841B5E", "9C3DCF", "A00460", "A42BB0", "B07FB9", "C03F0E", "C0FFD4", "E0469A", "E4F4C6", "F417B8");
        // D-Link
        registerVendors("D-Link", "00055D", "000D88", "000F3D", "001195", "001346", "0015E9", "00179A", "00195B", "001B11", "001CF0", "001E58", "002191", "0022B0", "002401", "00265A", "14D64D", "1C7EE5", "28107B", "340804", "7062B8", "78542E", "84C9B2", "9094E4", "A0AB1B", "B8A386", "C0A0BB", "CCB255", "E06995", "FC7516");
        // Google
        registerVendors("Google", "001A11", "3C5A37", "546009", "F4F5DB", "F4032B", "48D6D5", "703ACB", "94E6F7", "A47733", "D86C63");
        // Amazon
        registerVendors("Amazon", "00FC8B", "38F73D", "40B4CD", "44650D", "50DCE7", "6837E9", "6854FD", "74C246", "84D6D0", "AC63BE", "FCA667");
        // Microsoft
        registerVendors("Microsoft", "0003FF", "000D3A", "00125A", "00155D", "0017FA", "001DD8", "002248", "0025AE", "281878", "3059B7", "485073", "501AC5", "5882A8", "6045BD", "703509", "7C1E52", "985FD3", "A438CC", "C83F26", "DC9840", "F460E2");
        // Sony
        registerVendors("Sony", "00014A", "00041F", "000AD9", "001315", "0015C1", "0019C5", "001DBA", "00248D", "709E29", "FC0FE6");
        // VMware
        registerVendors("VMware", "005056", "000C29", "000569");
        // Realtek
        registerVendors("Realtek", "00E04C", "525400", "7C4D8F", "0018E7");
        // MediaTek
        registerVendors("MediaTek", "F0A654", "000CE7", "7079B3");
        // Tuya (IoT)
        registerVendors("Tuya (IoT)", "508A06", "68572D", "708976", "7CF666", "A4C138", "D81F12");
        // Ubiquiti Networks
        registerVendors("Ubiquiti Networks", "00156D", "002722", "24A43C", "44D9E7", "68D79A", "788A20", "802AA8", "B4FBE4", "DC9FDB", "F09FC2");
        // Synology
        registerVendors("Synology", "001132");
    }

    private static void registerVendors(String vendor, String... ouis) {
        for (String oui : ouis) {
            OUI_VENDORS.put(oui.toUpperCase(), vendor);
        }
    }

    public LocalNetworkScannerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public boolean startScanAsync() {
        if (!scanning.compareAndSet(false, true)) {
            System.out.println("[SCANNER] Ya hay un escaneo en ejecución.");
            return false;
        }

        // Ejecutar en hilo de fondo desacoplado para liberar el hilo HTTP
        Thread scanThread = new Thread(this::performNetworkScan, "NetworkScanner-Worker");
        scanThread.setDaemon(true);
        scanThread.start();
        return true;
    }

    private void performNetworkScan() {
        long startTime = System.currentTimeMillis();
        System.out.println("[SCANNER] Iniciando auditoría y descubrimiento de red...");

        try {
            // 1. Detección automática de la NIC y subred
            SubnetInfo subnetInfo = detectSubnetInfo();
            if (subnetInfo == null || subnetInfo.ipList.isEmpty()) {
                String errorMsg = "No se pudo detectar una interfaz de red IPv4 activa con puerta de enlace válida.";
                System.err.println("[SCANNER] " + errorMsg);
                messagingTemplate.convertAndSend("/topic/scan", ScanMessage.error(errorMsg));
                scanning.set(false);
                return;
            }

            int totalHosts = subnetInfo.ipList.size();
            System.out.println(String.format("[SCANNER] Subred: %s | Host: %s | Gateway: %s | Hosts a auditar: %d",
                    subnetInfo.subnetCidr, subnetInfo.localIp, subnetInfo.gatewayIp, totalHosts));

            // Snapshot inicial de la tabla ARP del sistema operativo
            Map<String, String> initialArpTable = getFullArpTable();

            // Notificar inicio del escaneo por WebSocket
            ScanMessage startMsg = ScanMessage.scanStart(
                    subnetInfo.localIp,
                    subnetInfo.gatewayIp,
                    subnetInfo.subnetCidr,
                    totalHosts,
                    "Iniciando escaneo concurrente en la subred " + subnetInfo.subnetCidr
            );
            messagingTemplate.convertAndSend("/topic/scan", startMsg);

            // 2. Ejecutor concurrente con pool de hilos de alta velocidad
            int poolSize = Math.max(20, Math.min(threadPoolSize, 50));
            scanExecutor = Executors.newFixedThreadPool(poolSize);

            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicInteger foundCount = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String targetIp : subnetInfo.ipList) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        auditHost(targetIp, subnetInfo, initialArpTable, foundCount);
                    } catch (Exception e) {
                        System.err.println("[SCANNER] Error auditando IP " + targetIp + ": " + e.getMessage());
                    } finally {
                        int current = completedCount.incrementAndGet();
                        int percent = (int) Math.round(((double) current / totalHosts) * 100);

                        // Emitir progreso periódico por WebSocket
                        messagingTemplate.convertAndSend("/topic/scan",
                                ScanMessage.progress(percent, current, totalHosts, foundCount.get()));
                    }
                }, scanExecutor);

                futures.add(future);
            }

            // Esperar que todos los hilos terminen
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            long totalTimeSec = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println(String.format("[SCANNER] Escaneo finalizado en %ds. Dispositivos activos: %d/%d",
                    totalTimeSec, foundCount.get(), totalHosts));

            // Emitir evento de finalización
            ScanMessage completeMsg = ScanMessage.scanComplete(
                    totalTimeSec,
                    foundCount.get(),
                    totalHosts,
                    "Auditoría completada exitosamente."
            );
            messagingTemplate.convertAndSend("/topic/scan", completeMsg);

        } catch (Exception e) {
            System.err.println("[SCANNER] Excepción general en el escaneo: " + e.getMessage());
            e.printStackTrace();
            messagingTemplate.convertAndSend("/topic/scan",
                    ScanMessage.error("Error durante el escaneo: " + e.getMessage()));
        } finally {
            if (scanExecutor != null && !scanExecutor.isShutdown()) {
                scanExecutor.shutdown();
            }
            scanning.set(false);
        }
    }

    /**
     * Audita un host específico de forma no intrusiva y emite el resultado si responde.
     */
    private void auditHost(String targetIp, SubnetInfo subnetInfo, Map<String, String> initialArpTable,
                           AtomicInteger foundCount) {
        boolean isLocalHost = targetIp.equals(subnetInfo.localIp);
        boolean isReachable = false;
        Long latencyMs = null;
        Integer ttl = null;

        if (isLocalHost) {
            isReachable = true;
            latencyMs = 0L;
            ttl = System.getProperty("os.name").toLowerCase().contains("win") ? 128 : 64;
        } else {
            // Intento 1: Ping ICMP nativo del SO para capturar disponibilidad, latencia y TTL
            PingResult pingResult = executePing(targetIp, pingTimeoutMs);
            if (pingResult.reachable) {
                isReachable = true;
                ttl = pingResult.ttl;
                latencyMs = pingResult.latencyMs;
            } else {
                // Intento 2: Verificación de presencia en caché ARP inicial
                if (initialArpTable.containsKey(targetIp)) {
                    isReachable = true;
                    latencyMs = 1L;
                } else {
                    // Intento 3: Sondeo rápido a puertos estándar para forzar actualización de ARP
                    long probeStart = System.currentTimeMillis();
                    if (isPortOpen(targetIp, 80, 150) || isPortOpen(targetIp, 445, 150)) {
                        isReachable = true;
                        latencyMs = System.currentTimeMillis() - probeStart;
                    }
                }
            }
        }

        // Si el host no responde en absoluto, no se reporta como activo
        if (!isReachable) {
            return;
        }

        foundCount.incrementAndGet();

        // 1. Resolución de Dirección MAC
        String mac;
        if (isLocalHost && subnetInfo.localMac != null) {
            mac = subnetInfo.localMac;
        } else {
            mac = initialArpTable.get(targetIp);
            if (mac == null || mac.contains("No disponible")) {
                mac = resolveMacFromArp(targetIp);
            }
        }

        // 2. Consulta y resolución de Fabricante (OUI)
        String vendor = lookupVendor(mac);

        // 3. Escaneo rápido de puertos críticos
        List<Integer> openPorts = scanCriticalPorts(targetIp, isLocalHost);

        // 4. Fingerprinting de Sistema Operativo por TTL
        String os = determineOs(ttl, isLocalHost, openPorts);

        // 5. Resolución de Hostname (Reverse DNS con timeout + Heurísticas)
        String hostname = resolveHostname(targetIp, isLocalHost, subnetInfo.gatewayIp, vendor, openPorts);

        // 6. Clasificación del Tipo de Dispositivo
        String deviceType = classifyDeviceType(targetIp, subnetInfo.gatewayIp, vendor, openPorts, os);

        // 7. Evaluación del Nivel de Riesgo de Seguridad
        RiskAssessment risk = evaluateSecurityRisk(openPorts);

        // Construir mensaje del dispositivo encontrado
        ScanMessage deviceMsg = new ScanMessage();
        deviceMsg.setType("device");
        deviceMsg.setIp(targetIp);
        deviceMsg.setSubnetMask(subnetInfo.subnetCidr);
        deviceMsg.setMac(mac);
        deviceMsg.setVendor(vendor);
        deviceMsg.setHostname(hostname);
        deviceMsg.setOs(os);
        deviceMsg.setLatencyMs(latencyMs != null ? latencyMs : 1L);
        deviceMsg.setOpenPorts(openPorts);
        deviceMsg.setDeviceType(deviceType);
        deviceMsg.setRiskLevel(risk.level);
        deviceMsg.setRiskDescription(risk.description);
        deviceMsg.setFound(foundCount.get());

        // Emitir el dispositivo descubierto en tiempo real
        messagingTemplate.convertAndSend("/topic/scan", deviceMsg);
    }

    /**
     * Ejecuta un ping nativo del SO para extraer latencia y TTL.
     */
    private PingResult executePing(String ip, int timeoutMs) {
        PingResult result = new PingResult();
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();

        if (isWin) {
            cmd.add("ping");
            cmd.add("-n");
            cmd.add("1");
            cmd.add("-w");
            cmd.add(String.valueOf(timeoutMs));
            cmd.add(ip);
        } else {
            cmd.add("ping");
            cmd.add("-c");
            cmd.add("1");
            cmd.add("-W");
            cmd.add(String.valueOf(Math.max(1, timeoutMs / 1000)));
            cmd.add(ip);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Pattern ttlPattern = Pattern.compile("(?i)ttl[=\\s:]+(\\d+)");
            Pattern timePattern = Pattern.compile("(?i)(?:tiempo|time)[=<]?\\s*(\\d+)\\s*ms");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher ttlMatcher = ttlPattern.matcher(line);
                    if (ttlMatcher.find()) {
                        result.reachable = true;
                        result.ttl = Integer.parseInt(ttlMatcher.group(1));
                    }

                    Matcher timeMatcher = timePattern.matcher(line);
                    if (timeMatcher.find()) {
                        result.latencyMs = Long.parseLong(timeMatcher.group(1));
                    } else if (line.toLowerCase().contains("tiempo<1m") || line.toLowerCase().contains("time<1ms")) {
                        result.latencyMs = 1L;
                    }
                }
            }
            process.waitFor(timeoutMs + 200, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }

        return result;
    }

    /**
     * Obtiene la tabla ARP completa del sistema de forma rápida.
     */
    private Map<String, String> getFullArpTable() {
        Map<String, String> arpMap = new ConcurrentHashMap<>();
        try {
            Process process = new ProcessBuilder("arp", "-a").start();
            Pattern ipMacPattern = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\s+([0-9a-fA-F:-]{11,17})");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = ipMacPattern.matcher(line);
                    if (m.find()) {
                        String ip = m.group(1);
                        String mac = m.group(2).replace('-', ':').toUpperCase();
                        if (!mac.equals("FF:FF:FF:FF:FF:FF") && !mac.startsWith("01:00:5E")) {
                            arpMap.put(ip, mac);
                        }
                    }
                }
            }
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        return arpMap;
    }

    /**
     * Resuelve la dirección MAC consultando la tabla ARP del host.
     */
    private String resolveMacFromArp(String ip) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();

        if (isWin) {
            cmd.add("arp");
            cmd.add("-a");
            cmd.add(ip);
        } else {
            cmd.add("arp");
            cmd.add("-n");
            cmd.add(ip);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Pattern macPattern = Pattern.compile("([0-9a-fA-F]{2}[:-][0-9a-fA-F]{2}[:-][0-9a-fA-F]{2}[:-][0-9a-fA-F]{2}[:-][0-9a-fA-F]{2}[:-][0-9a-fA-F]{2})");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(ip) || !isWin) {
                        Matcher matcher = macPattern.matcher(line);
                        if (matcher.find()) {
                            String mac = matcher.group(1).replace('-', ':').toUpperCase();
                            if (!mac.equals("FF:FF:FF:FF:FF:FF") && !mac.startsWith("01:00:5E")) {
                                process.waitFor(300, TimeUnit.MILLISECONDS);
                                return mac;
                            }
                        }
                    }
                }
            }
            process.waitFor(300, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }

        return "No disponible / Oculta";
    }

    /**
     * Obtiene el fabricante a partir del OUI de la MAC.
     */
    private String lookupVendor(String mac) {
        if (mac == null || mac.contains("No disponible")) {
            return "Desconocido / Genérico";
        }
        String cleanMac = mac.replaceAll("[:\\-]", "").toUpperCase();
        if (cleanMac.length() >= 6) {
            String oui = cleanMac.substring(0, 6);
            String vendor = OUI_VENDORS.get(oui);
            if (vendor != null) {
                return vendor;
            }
        }
        return "Desconocido / Genérico";
    }

    /**
     * Audita puertos TCP críticos de forma rápida y no intrusiva.
     */
    private List<Integer> scanCriticalPorts(String ip, boolean isLocalHost) {
        List<Integer> openPorts = new ArrayList<>();
        for (int port : AUDIT_PORTS) {
            if (isPortOpen(ip, port, portTimeoutMs)) {
                openPorts.add(port);
            }
        }
        return openPorts;
    }

    private boolean isPortOpen(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.setReuseAddress(true);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Identifica el sistema operativo estimado basado en el TTL de respuesta ICMP.
     */
    private String determineOs(Integer ttl, boolean isLocalHost, List<Integer> openPorts) {
        if (isLocalHost) {
            String osName = System.getProperty("os.name");
            return osName != null ? osName : "Host local";
        }

        if (ttl != null) {
            if (ttl >= 33 && ttl <= 64) {
                return "Linux / Android / macOS / iOS";
            } else if (ttl >= 65 && ttl <= 128) {
                return "Microsoft Windows";
            } else if (ttl >= 129 && ttl <= 255) {
                return "Dispositivo de red (Cisco, Router, Switch)";
            }
        }

        // Heurísticas de fallback si ICMP fue bloqueado por firewall
        if (openPorts.contains(445) || openPorts.contains(3389) || openPorts.contains(135)) {
            return "Microsoft Windows (Heurística)";
        }
        if (openPorts.contains(22)) {
            return "Linux / Unix (Heurística)";
        }

        return "Desconocido / bloqueado por firewall";
    }

    /**
     * Resuelve el nombre del dispositivo usando Reverse DNS y heurísticas.
     */
    private String resolveHostname(String ip, boolean isLocalHost, String gatewayIp, String vendor, List<Integer> openPorts) {
        if (isLocalHost) {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException ignored) {
                return "Host local (este equipo)";
            }
        }

        try {
            // Ejecutar Reverse DNS con timeout de 350ms para no bloquear el worker
            CompletableFuture<String> dnsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    return addr.getCanonicalHostName();
                } catch (Exception e) {
                    return ip;
                }
            }, dnsExecutor);

            String canonical = dnsFuture.get(350, TimeUnit.MILLISECONDS);
            if (canonical != null && !canonical.equalsIgnoreCase(ip) && !canonical.isBlank()) {
                return canonical;
            }
        } catch (Exception ignored) {
        }

        // Fallbacks heurísticos cuando no hay DNS inverso
        if (ip.equals(gatewayIp)) {
            return "Puerta de enlace (Router principal)";
        }

        if (vendor != null && !vendor.contains("Desconocido")) {
            return "Dispositivo " + vendor;
        }

        if (openPorts.contains(445) || openPorts.contains(135)) {
            return "Equipo Windows";
        }

        if (openPorts.contains(22)) {
            return "Servidor Linux";
        }

        int lastDot = ip.lastIndexOf('.');
        return "Host-" + (lastDot != -1 ? ip.substring(lastDot + 1) : ip);
    }

    /**
     * Deduce la categoría del equipo.
     */
    private String classifyDeviceType(String ip, String gatewayIp, String vendor, List<Integer> openPorts, String os) {
        boolean isGateway = ip.equals(gatewayIp);
        String vLower = vendor.toLowerCase();

        if (isGateway || openPorts.contains(53)
                || vLower.contains("tp-link") || vLower.contains("cisco") || vLower.contains("hitron")
                || vLower.contains("huawei") || vLower.contains("netgear") || vLower.contains("d-link")
                || vLower.contains("ubiquiti")) {
            return "Router / Puerta de enlace";
        }

        if (openPorts.contains(445) || openPorts.contains(3389) || os.contains("Windows")) {
            return "Estación de trabajo Windows";
        }

        if (openPorts.contains(22) || os.contains("Linux")) {
            return "Servidor Linux";
        }

        if (vLower.contains("apple") || vLower.contains("samsung") || vLower.contains("xiaomi")) {
            return "Dispositivo móvil / tablet";
        }

        if (vLower.contains("espressif") || vLower.contains("tuya") || vLower.contains("raspberry")
                || vLower.contains("amazon") || vLower.contains("google")) {
            return "Dispositivo IoT / domótica";
        }

        return "Host genérico";
    }

    /**
     * Evalúa el nivel de riesgo de seguridad según los puertos abiertos detectados.
     */
    private RiskAssessment evaluateSecurityRisk(List<Integer> openPorts) {
        RiskAssessment assessment = new RiskAssessment();

        // 1. CRÍTICO: Telnet (23) o SMB (445)
        if (openPorts.contains(23) || openPorts.contains(445)) {
            assessment.level = "CRÍTICO";
            List<String> reasons = new ArrayList<>();
            if (openPorts.contains(23)) {
                reasons.add("Puerto Telnet (23) expuesto: transmisión de comandos y credenciales en texto plano sin cifrar");
            }
            if (openPorts.contains(445)) {
                reasons.add("Puerto SMB (445) accesible: vector crítico para malware, ransomware y movimientos laterales");
            }
            assessment.description = String.join(". ", reasons);
            return assessment;
        }

        // 2. ALTO: FTP (21) o RDP (3389)
        if (openPorts.contains(21) || openPorts.contains(3389)) {
            assessment.level = "ALTO";
            List<String> reasons = new ArrayList<>();
            if (openPorts.contains(21)) {
                reasons.add("Puerto FTP (21) expuesto: transferencia de archivos y credenciales sin cifrado TLS");
            }
            if (openPorts.contains(3389)) {
                reasons.add("Puerto RDP (3389) accesible: interfaz de escritorio remoto expuesta a ataques de fuerza bruta");
            }
            assessment.description = String.join(". ", reasons);
            return assessment;
        }

        // 3. MEDIO: HTTP (80) o HTTP-Proxy (8080)
        if (openPorts.contains(80) || openPorts.contains(8080)) {
            assessment.level = "MEDIO";
            assessment.description = "Servicio web sin cifrar activo (puerto "
                    + (openPorts.contains(80) ? "80" : "8080") + "): tráfico HTTP en claro vulnerable a intercepción";
            return assessment;
        }

        // 4. BAJO / SEGURO
        assessment.level = "BAJO";
        if (openPorts.isEmpty()) {
            assessment.description = "Dispositivo protegido: sin servicios estándar expuestos a la red local";
        } else {
            assessment.description = "Servicios seguros: únicamente puertos cifrados/estándar activos (" + openPorts + ")";
        }
        return assessment;
    }

    /**
     * Detección automática de la NIC activa, IP local, máscara de subred y puerta de enlace.
     */
    private SubnetInfo detectSubnetInfo() {
        try {
            // Intentar detectar Gateway e IP vinculada mediante la tabla de enrutamiento del SO
            String detectedGateway = null;
            String detectedInterfaceIp = null;

            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWin) {
                // Windows: parsear 'route print 0.0.0.0'
                Process p = new ProcessBuilder("route", "print", "0.0.0.0").start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("0.0.0.0")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 4) {
                                detectedGateway = parts[2];
                                detectedInterfaceIp = parts[3];
                                break;
                            }
                        }
                    }
                }
                p.waitFor(1, TimeUnit.SECONDS);
            }

            // Buscar la NetworkInterface correspondiente
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface selectedNic = null;
            InterfaceAddress selectedAddress = null;

            List<CandidateInterface> candidates = new ArrayList<>();

            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) {
                    continue;
                }

                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ipStr = addr.getHostAddress();
                        int score = 0;

                        // Coincidencia exacta con la IP que tiene la ruta por defecto
                        if (detectedInterfaceIp != null && ipStr.equals(detectedInterfaceIp)) {
                            score += 100;
                        }

                        // Penalizar interfaces virtuales conocidas
                        String nameLower = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
                        if (nameLower.contains("vmware") || nameLower.contains("virtualbox")
                                || nameLower.contains("hyper-v") || nameLower.contains("wsl")
                                || nameLower.contains("vethernet") || nameLower.contains("tap")
                                || nameLower.contains("teredo") || nameLower.contains("bluetooth")) {
                            score -= 50;
                        } else {
                            score += 20;
                        }

                        // Bonificar direcciones IP privadas de clase C (192.168.x.x) o clase A/B
                        if (addr.isSiteLocalAddress()) {
                            score += 10;
                        }

                        candidates.add(new CandidateInterface(ni, ia, score));
                    }
                }
            }

            if (candidates.isEmpty()) {
                return null;
            }

            // Ordenar por puntuación descendente
            candidates.sort((a, b) -> Integer.compare(b.score, a.score));
            CandidateInterface best = candidates.get(0);
            selectedNic = best.nic;
            selectedAddress = best.ia;

            InetAddress localInet = selectedAddress.getAddress();
            String localIp = localInet.getHostAddress();
            int prefix = selectedAddress.getNetworkPrefixLength();

            // Para seguridad en redes domésticas/empresariales, limitar a /24 si la máscara es muy amplia
            if (prefix < 24 || prefix > 30) {
                prefix = 24;
            }

            // Calcular rango de direcciones IP
            int localIpInt = ipToInt(localInet.getAddress());
            int mask = 0xFFFFFFFF << (32 - prefix);
            int network = localIpInt & mask;
            int broadcast = network | ~mask;

            int startIpInt = network + 1;
            int endIpInt = broadcast - 1;

            List<String> ipList = new ArrayList<>();
            for (int current = startIpInt; current <= endIpInt; current++) {
                ipList.add(intToIp(current));
            }

            String subnetMaskStr = intToIp(mask) + " /" + prefix;

            // Determinar Gateway final
            String gatewayIp = detectedGateway;
            if (gatewayIp == null || gatewayIp.isBlank()) {
                // Fallback común: usualmente .1 o .254
                gatewayIp = intToIp(startIpInt);
            }

            // Obtener MAC local
            String localMac = getHardwareAddress(selectedNic);

            return new SubnetInfo(localIp, localMac, gatewayIp, subnetMaskStr, ipList);

        } catch (Exception e) {
            System.err.println("[SCANNER] Error detectando configuración de red: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private String getHardwareAddress(NetworkInterface ni) {
        try {
            byte[] macBytes = ni.getHardwareAddress();
            if (macBytes != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < macBytes.length; i++) {
                    sb.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return "No disponible";
    }

    private static int ipToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    private static String intToIp(int val) {
        return String.format("%d.%d.%d.%d",
                (val >>> 24) & 0xFF,
                (val >>> 16) & 0xFF,
                (val >>> 8) & 0xFF,
                val & 0xFF);
    }

    @PreDestroy
    public void cleanup() {
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
        }
        dnsExecutor.shutdownNow();
    }

    // Clases auxiliares internas
    private static class PingResult {
        boolean reachable = false;
        Long latencyMs = null;
        Integer ttl = null;
    }

    private static class RiskAssessment {
        String level;
        String description;
    }

    private static class SubnetInfo {
        final String localIp;
        final String localMac;
        final String gatewayIp;
        final String subnetCidr;
        final List<String> ipList;

        SubnetInfo(String localIp, String localMac, String gatewayIp, String subnetCidr, List<String> ipList) {
            this.localIp = localIp;
            this.localMac = localMac;
            this.gatewayIp = gatewayIp;
            this.subnetCidr = subnetCidr;
            this.ipList = ipList;
        }
    }

    private static class CandidateInterface {
        final NetworkInterface nic;
        final InterfaceAddress ia;
        final int score;

        CandidateInterface(NetworkInterface nic, InterfaceAddress ia, int score) {
            this.nic = nic;
            this.ia = ia;
            this.score = score;
        }
    }
}

