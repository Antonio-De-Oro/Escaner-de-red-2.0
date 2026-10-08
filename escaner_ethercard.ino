/*
 * 🔍 ESCÁNER DE RED - Arduino UNO + ENC28J60
 * Librería: EtherCard (instalar desde Library Manager)
 * 
 * ✨ Funcionalidad:
 * - Conexión DHCP o IP estática
 * - Escaneo ARP de toda la subred
 * - Detección de IPs activas con sus MACs REALES
 * - Interfaz visual llamativa en Serial Monitor (115200 baud)
 * - Barra de progreso animada
 * - Detección del fabricante por OUI
 */

#include <EtherCard.h>

// ===== CONFIGURACIÓN =====
static byte mymac[] = { 0x74, 0x69, 0x69, 0x2D, 0x30, 0x31 };  // MAC del Arduino
static byte myip[] = { 192, 168, 1, 100 };   // IP estática (si DHCP falla)
static byte gwip[] = { 192, 168, 1, 254 };     // Gateway

byte Ethernet::buffer[400];  // Reducido para ahorrar RAM

// Configuración de escaneo
byte scanBaseIP[] = { 192, 168, 1, 0 };
int scanStart = 1;
int scanEnd = 254;
int scanDelay = 150;  // Más lento para mejor detección

// Variables globales
unsigned long scanStartTime = 0;
bool scanRequested = false;

// Estructura simplificada
struct Device {
  byte ip[4];
  byte mac[6];
};

Device devices[50];  // Aumentado a 50 dispositivos
int deviceCount = 0;
int currentScanIP = 0;

// ===== FUNCIONES AUXILIARES =====
// Banner compacto
void printBanner() {
  Serial.println(F("\n=== ESCANER DE RED ==="));
  Serial.println(F("Arduino UNO + ENC28J60\n"));
}

// Imprimir MAC con formato
void printMAC(byte* mac) {
  for (int i = 0; i < 6; i++) {
    if (mac[i] < 0x10) Serial.print("0");
    Serial.print(mac[i], HEX);
    if (i < 5) Serial.print(":");
  }
}

// Imprimir IP con formato
void printIP(byte* ip) {
  for (int i = 0; i < 4; i++) {
    Serial.print(ip[i]);
    if (i < 3) Serial.print(".");
  }
}



// Progreso en JSON puro
void printProgress(int current, int total) {
  int percent = (current * 100) / total;
  
  // Solo JSON para backend
  Serial.print(F("{"));
  Serial.print(F("\"type\":\"progress\","));
  Serial.print(F("\"percent\":")); Serial.print(percent);
  Serial.print(F(",\"current\":")); Serial.print(current);
  Serial.print(F(",\"total\":")); Serial.print(total);
  Serial.print(F(",\"found\":")); Serial.print(deviceCount);
  Serial.println(F("}"));
}

// Agregar dispositivo y JSON
void addDevice(byte* ip, byte* mac) {
  if (deviceCount >= 50) return;
  // Verificar si ya existe
  for (int i = 0; i < deviceCount; i++) {
    if (memcmp(devices[i].ip, ip, 4) == 0) return;
  }
  // Agregar nuevo
  memcpy(devices[deviceCount].ip, ip, 4);
  memcpy(devices[deviceCount].mac, mac, 6);
  deviceCount++;
  // JSON para backend (sin vendor)
  Serial.print(F("\n{"));
  Serial.print(F("\"type\":\"device\","));
  Serial.print(F("\"ip\":\""));  printIP(ip); Serial.print(F("\","));
  Serial.print(F("\"mac\":\""));  printMAC(mac); Serial.print(F("\""));
  Serial.println(F("}"));
}

// Callback de EtherCard para capturar respuestas ARP
void arpCallback(byte status, word off, word len) {
  // Esta función se llama cuando llega un paquete Ethernet
  // Analizamos si es una respuesta ARP
  
  // Estructura del frame Ethernet:
  // [0-5]: MAC destino
  // [6-11]: MAC origen (la que queremos)
  // [12-13]: EtherType (0x0806 = ARP)
  // [14+]: Datos ARP
  
  byte* packet = Ethernet::buffer + off;
  
  // Verificar si es ARP (EtherType = 0x0806)
  if (len >= 42 && packet[12] == 0x08 && packet[13] == 0x06) {
    // Es ARP, verificar si es respuesta (opcode = 2)
    if (packet[21] == 0x02) {
      // Extraer IP origen (sender IP)
      byte senderIP[4];
      memcpy(senderIP, &packet[28], 4);
      
      // Extraer MAC origen (sender MAC)
      byte senderMAC[6];
      memcpy(senderMAC, &packet[22], 6);
      
      // Agregar a la lista
      addDevice(senderIP, senderMAC);
    }
  }
}

// Enviar petición ARP
void sendARPRequest(byte* targetIP) {
  // Construir paquete ARP request directamente en Ethernet::buffer
  
  // MAC destino: broadcast (FF:FF:FF:FF:FF:FF)
  memset(Ethernet::buffer, 0xFF, 6);
  
  // MAC origen: nuestra MAC
  memcpy(&Ethernet::buffer[6], mymac, 6);
  
  // EtherType: ARP (0x0806)
  Ethernet::buffer[12] = 0x08;
  Ethernet::buffer[13] = 0x06;
  
  // Hardware type: Ethernet (0x0001)
  Ethernet::buffer[14] = 0x00;
  Ethernet::buffer[15] = 0x01;
  
  // Protocol type: IPv4 (0x0800)
  Ethernet::buffer[16] = 0x08;
  Ethernet::buffer[17] = 0x00;
  
  // Hardware size: 6
  Ethernet::buffer[18] = 0x06;
  
  // Protocol size: 4
  Ethernet::buffer[19] = 0x04;
  
  // Opcode: Request (0x0001)
  Ethernet::buffer[20] = 0x00;
  Ethernet::buffer[21] = 0x01;
  
  // Sender MAC
  memcpy(&Ethernet::buffer[22], mymac, 6);
  
  // Sender IP
  memcpy(&Ethernet::buffer[28], ether.myip, 4);
  
  // Target MAC: 00:00:00:00:00:00
  memset(&Ethernet::buffer[32], 0x00, 6);
  
  // Target IP
  memcpy(&Ethernet::buffer[38], targetIP, 4);
  
  // Enviar paquete (solo la longitud)
  ether.packetSend(42);
}

// ===== SETUP =====
void setup() {
  Serial.begin(115200);
  delay(1000);  // Esperar a que serial esté listo
  
  pinMode(10, OUTPUT);
  digitalWrite(10, HIGH);
  
  Serial.println(F("{\"type\":\"ready\"}"));
}

// ===== LOOP =====
void loop() {
  // Esperar comando SCAN por Serial
  if (!scanRequested) {
    // Reenviar "ready" cada 5 segundos para que el backend sepa que estamos vivos
    static unsigned long lastReady = 0;
    if (millis() - lastReady > 5000) {
      Serial.println(F("{\"type\":\"ready\"}"));
      lastReady = millis();
    }
    
    if (Serial.available()) {
      String cmd = Serial.readStringUntil('\n');
      cmd.trim();
      
      // Debug: confirmar que recibimos algo
      Serial.print(F("{\"type\":\"debug\",\"cmd\":\""));
      Serial.print(cmd);
      Serial.println(F("\"}"));
      
      if (cmd.equalsIgnoreCase("SCAN")) {
        scanRequested = true;
        
        // Inicializar red AHORA (cuando se pide escaneo)
        byte result = ether.begin(sizeof Ethernet::buffer, mymac, 10);
        if (result == 0) {
          Serial.println(F("{\"type\":\"error\",\"msg\":\"ENC28J60 init failed\"}"));
          scanRequested = false;
          return;
        }
        
        // Intentar DHCP con timeout corto
        if (!ether.dhcpSetup(5000)) {  // 5 segundos timeout
          ether.staticSetup(myip, gwip);
        }
        
        scanBaseIP[0] = ether.myip[0];
        scanBaseIP[1] = ether.myip[1];
        scanBaseIP[2] = ether.myip[2];
        
        // Enviar info de IP
        Serial.print(F("{\"type\":\"ip\",\"ip\":\"")); 
        printIP(ether.myip); 
        Serial.print(F("\",\"gw\":\"")); 
        printIP(ether.gwip); 
        Serial.println(F("\"}"));
        
        // Iniciar escaneo
        Serial.println(F("{\"type\":\"scan_start\"}"));
        currentScanIP = scanStart;
        deviceCount = 0;
        scanStartTime = millis();
      }
    }
    delay(50);
    return;
  }
  // Procesar paquetes entrantes
  word len = ether.packetReceive();
  if (len > 0) {
    arpCallback(0, 0, len);
  }
  // Continuar escaneo
  if (currentScanIP <= scanEnd) {
    byte targetIP[4];
    memcpy(targetIP, scanBaseIP, 3);
    targetIP[3] = currentScanIP;
    if (memcmp(targetIP, ether.myip, 4) != 0) {
      sendARPRequest(targetIP);
      // Esperar más tiempo para respuestas (mejor detección)
      unsigned long t0 = millis();
      while (millis() - t0 < 50) {
        word l2 = ether.packetReceive();
        if (l2 > 0) arpCallback(0, 0, l2);
      }
    }
    printProgress(currentScanIP - scanStart + 1, scanEnd - scanStart + 1);
    currentScanIP++;
    delay(scanDelay);
  } else if (currentScanIP == scanEnd + 1) {
    currentScanIP++;
    unsigned long elapsed = (millis() - scanStartTime) / 1000;
    Serial.print(F("{\"type\":\"scan_complete\",\"time\":")); Serial.print(elapsed); Serial.print(F(",\"found\":")); Serial.print(deviceCount); Serial.println(F("}"));
    scanRequested = false;
  }
}
