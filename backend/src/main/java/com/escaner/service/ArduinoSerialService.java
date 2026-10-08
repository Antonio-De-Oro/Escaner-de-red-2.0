package com.escaner.service;

import com.escaner.model.ScanMessage;
import com.fazecast.jSerialComm.SerialPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;

@Service
public class ArduinoSerialService {

    @Value("${arduino.serial.port}")
    private String portName;

    @Value("${arduino.serial.baudrate}")
    private int baudRate;

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private SerialPort serialPort;
    private PrintWriter output;
    private BufferedReader input;
    private Thread readerThread;

    public ArduinoSerialService(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        try {
            // Buscar y abrir puerto serial
            SerialPort[] ports = SerialPort.getCommPorts();
            System.out.println("Puertos disponibles:");
            for (SerialPort port : ports) {
                System.out.println(" - " + port.getSystemPortName() + ": " + port.getDescriptivePortName());
            }

            serialPort = SerialPort.getCommPort(portName);
            serialPort.setBaudRate(baudRate);
            serialPort.setNumDataBits(8);
            serialPort.setNumStopBits(1);
            serialPort.setParity(SerialPort.NO_PARITY);
            // Timeout más largo: espera hasta 5000ms por datos
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 5000, 0);

            if (serialPort.openPort()) {
                System.out.println("✓ Puerto serial " + portName + " abierto correctamente");
                
                // Esperar a que el Arduino se inicialice después de la conexión
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    // ignore
                }
                
                output = new PrintWriter(serialPort.getOutputStream());
                input = new BufferedReader(new InputStreamReader(serialPort.getInputStream()));

                // Enviar un salto de línea para "despertar" el Arduino
                output.println();
                output.flush();

                // Hilo para leer continuamente del puerto serial
                readerThread = new Thread(this::readSerialData);
                readerThread.setDaemon(true);
                readerThread.start();
            } else {
                System.err.println("✗ No se pudo abrir el puerto serial " + portName);
            }
        } catch (Exception e) {
            System.err.println("Error inicializando puerto serial: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void readSerialData() {
        System.out.println("Escuchando mensajes del Arduino...");
        String line;
        while (true) {
            try {
                line = input.readLine();
                
                if (line == null) {
                    // Puerto cerrado
                    break;
                }
                
                line = line.trim();
                
                // Ignorar líneas vacías
                if (line.isEmpty()) continue;

                // Procesar solo líneas JSON (empiezan con {)
                if (line.startsWith("{")) {
                    try {
                        ScanMessage message = objectMapper.readValue(line, ScanMessage.class);
                        
                        // Reenviar por WebSocket a todos los clientes conectados
                        messagingTemplate.convertAndSend("/topic/scan", message);
                        
                        System.out.println("→ WS: " + message.getType());
                    } catch (Exception e) {
                        System.err.println("Error parseando JSON: " + line);
                    }
                } else {
                    // Log de líneas no-JSON (debug del Arduino)
                    System.out.println("Arduino: " + line);
                }
            } catch (java.io.IOException e) {
                // Timeout - continuar esperando sin error
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    continue;
                }
                System.err.println("Error leyendo serial: " + e.getMessage());
                break;
            } catch (Exception e) {
                System.err.println("Error procesando: " + e.getMessage());
            }
        }
        System.out.println("Lectura serial terminada");
    }

    public void sendScanCommand() {
        if (output != null) {
            System.out.println("→ Enviando comando SCAN...");
            output.print("SCAN\n");
            output.flush();
            
            // Pausa para que el Arduino inicialice red (DHCP puede tardar)
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
            
            System.out.println("✓ Comando SCAN enviado - esperando respuesta del Arduino...");
        } else {
            System.err.println("✗ Puerto serial no disponible");
        }
    }

    @PreDestroy
    public void cleanup() {
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            System.out.println("Puerto serial cerrado");
        }
    }
}
