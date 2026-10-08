# Network Scanner Backend - Spring Boot

Backend en Spring Boot para controlar el escáner de red Arduino UNO + ENC28J60.

## 📋 Requisitos

- Java 17 o superior
- Maven
- Arduino UNO conectado por USB

## 🚀 Instalación y Ejecución

### 1. Configurar Puerto Serial

Edita `src/main/resources/application.properties` y ajusta el puerto COM de tu Arduino:

```properties
arduino.serial.port=COM3
arduino.serial.baudrate=115200
```

Para saber qué puerto usa tu Arduino:
- Windows: Abre "Administrador de dispositivos" → "Puertos (COM y LPT)"
- El Arduino aparece como "Arduino Uno (COMx)"

### 2. Compilar y Ejecutar

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

El servidor arrancará en `http://localhost:8080`

### 3. Abrir la Interfaz Web

Abre tu navegador en: `http://localhost:8080`

## 🎯 Funcionalidad

### Endpoints REST

- `POST /api/scan` - Envía comando "SCAN" al Arduino
- `GET /api/status` - Verifica estado del backend

### WebSocket

- Endpoint: `/ws`
- Topic: `/topic/scan`
- Mensajes en tiempo real del Arduino

### Tipos de Mensajes JSON

**Desde Arduino → Backend:**
```json
{"type":"ready"}
{"type":"scan_start"}
{"type":"ip","ip":"192.168.1.100","gw":"192.168.1.254"}
{"type":"progress","percent":45,"current":115,"total":254,"found":3}
{"type":"device","ip":"192.168.1.1","mac":"00:11:22:33:44:55","vendor":"TP-Link"}
{"type":"scan_complete","time":28,"found":5}
{"type":"error","msg":"Module not responding"}
```

**Backend → Frontend (WebSocket):**
- Retransmite todos los mensajes del Arduino en tiempo real

## 🔧 Estructura del Proyecto

```
backend/
├── src/main/java/com/escaner/
│   ├── NetworkScannerApplication.java   # Main Spring Boot
│   ├── config/
│   │   └── WebSocketConfig.java         # Config WebSocket
│   ├── controller/
│   │   └── ScanController.java          # REST endpoints
│   ├── model/
│   │   └── ScanMessage.java             # Modelo de datos
│   └── service/
│       └── ArduinoSerialService.java    # Comunicación Serial
├── src/main/resources/
│   ├── application.properties           # Configuración
│   └── static/
│       └── index.html                   # Frontend
└── pom.xml                              # Dependencias Maven
```

## 📚 Dependencias Principales

- **Spring Boot Web** - REST API
- **Spring Boot WebSocket** - Comunicación en tiempo real
- **jSerialComm** - Lectura/escritura puerto serial
- **Jackson** - Parsing JSON

## 🐛 Troubleshooting

### El backend no encuentra el Arduino

1. Verifica que el Arduino esté conectado por USB
2. Revisa el puerto COM en `application.properties`
3. En Windows, asegúrate que el puerto no esté siendo usado por otro programa (cierra Arduino IDE)

### WebSocket desconectado

- Asegúrate que el backend esté corriendo en `localhost:8080`
- Revisa la consola del navegador (F12) para ver errores de conexión

### No aparecen dispositivos

- Verifica que el Arduino tenga el sketch actualizado con salida JSON
- Revisa la consola del backend: debe mostrar mensajes "Arduino: ..."
- Comprueba que el cable de red esté conectado al ENC28J60

## 📝 Notas

- El backend se conecta automáticamente al Arduino al iniciar
- Los mensajes del Arduino se muestran en la consola del backend
- El frontend se actualiza en tiempo real vía WebSocket
- Puedes reiniciar el escaneo cuantas veces quieras sin reiniciar el backend
