#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
====================================================================================
VULNERASOLVE 2.0 — MICROSERVICIO DE OPTIMIZACIÓN EN INVESTIGACIÓN DE OPERACIONES
Archivo: motor-io/optimizador_io.py
====================================================================================

PROPÓSITO GENERAL DE ESTE ARCHIVO:
Este módulo implementa como un microservicio independiente en Python el modelo
matemático de Programación Lineal (Investigación de Operaciones) de VulneraSolve.
Su objetivo es calcular en tiempo real la velocidad óptima de envío de paquetes TCP
por segundo hacia:
  • X1: Puertos críticos de alta severidad (FTP 21, Telnet 23, SMB 445, RDP 3389).
  • X2: Puertos comunes de servicios estándar (SSH 22, DNS 53, HTTP 80, HTTPS 443, 8080).

¿CÓMO SE CONECTA ESTE MICROSERVICIO CON EL RESTO DE VULNERASOLVE?
El flujo de comunicación de extremo a extremo funciona en 4 pasos:

  [1. Frontend Web (index.html)]
       El administrador mueve los controles deslizantes (sliders) o elige un modo
       preconfigurado (Sigiloso, Balanceado o Agresivo) en el panel web.
              │
              ▼  (Petición HTTP POST /api/io/optimizar o POST /api/scan en JSON)
  [2. Backend Java Spring Boot (OptimizacionIoController -> OptimizacionIoService)]
       Spring Boot recibe los límites deseados por el usuario y actúa como cliente
       HTTP enviando un paquete JSON a este microservicio Python en el puerto 8001.
              │
              ▼  (Petición HTTP POST http://127.0.0.1:8001/api/optimizar)
  [3. Microservicio Python (ESTE ARCHIVO: optimizador_io.py)]
       Construye las desigualdades lineales, halla todos los vértices factibles
       mediante intersección algebraica (Regla de Cramer), evalúa la función
       objetivo Z = c1*X1 + c2*X2 en cada esquina y devuelve el punto óptimo JSON.
              │
              ▼  (Respuesta JSON con X1*, X2*, Z*, consumo en KB/s y Mbps)
  [4. Regulador de Tráfico en Java (AdaptiveRateLimiter -> LocalNetworkScannerService)]
       El motor de escaneo aplica los valores óptimos X1* y X2* para espaciar en
       nanosegundos cada socket TCP saliente, logrando la máxima efectividad sin
       saturar la red LAN ni disparar bloqueos en el Firewall / IDS corporativo.

¿POR QUÉ SE USA LA LIBRERÍA ESTÁNDAR NATIVA DE PYTHON?
Se emplea `http.server.ThreadingHTTPServer`, `json` y resolución algebraica exacta
sin depender de paquetes pesados externos. Esto garantiza dos ventajas clave:
  1. Portabilidad total: Funciona de inmediato con cualquier instalación estándar
     de Python 3 (`python motor-io/optimizador_io.py`) sin errores de dependencias.
  2. Transparencia académica: Todo el método matemático de vértices extremos de
     Programación Lineal (Taha, 2017) queda explícito y legible línea por línea.
====================================================================================
"""

from __future__ import annotations

import http.client
import json
import re
import socket
import ssl
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple


# ==================================================================================
# CONFIGURACIÓN DE RED DEL MICROSERVICIO Y AUTOCALIBRACIÓN NO INTRUSIVA
# ==================================================================================
# HOST "127.0.0.1" asegura que el microservicio escuche únicamente conexiones locales
# provenientes del backend Spring Boot en la misma máquina (seguridad por diseño).
HOST_MICROSERVICIO = "127.0.0.1"
PUERTO_MICROSERVICIO = 8001

# Tolerancia numérica para comparaciones de punto flotante (evita que errores de
# redondeo binario como 100.00000000000001 descarten un vértice legítimo).
EPSILON = 1e-7

# Parámetros de la sonda híbrida de autocalibración y Regla de No Intrusión:
# Se asigna únicamente el 1.0% del ancho de banda disponible medido para que el
# escáner trabaje en segundo plano sin afectar videollamadas ni aplicaciones LAN.
PORCENTAJE_NO_INTRUSION = 1.0
FASE_SUBIDA_SEGUNDOS = 3.5     # Fase 1: superar TCP Slow-Start hasta alcanzar el pico máximo
FASE_PROMEDIO_SEGUNDOS = 2.3   # Fase 2: promediar el caudal ya estabilizado (~5.8 s totales)
MIN_SLIDER_BW_KBS = 40.0
MAX_SLIDER_BW_KBS = 800.0


# ==================================================================================
# BLOQUE 1: ESTRUCTURAS DE DATOS DEL MODELO DE PROGRAMACIÓN LINEAL
# ==================================================================================

@dataclass
class ParametrosOptimizacion:
    """
    Representa los parámetros de entrada enviados desde Spring Boot / Frontend.
    Si algún valor no se especifica en el JSON, se utilizan por defecto los valores
    oficiales del modelo base documentado en el informe metodológico:
      • limite_ids_pps         = 100.0 paquetes/segundo (Restricción R1 de sigilo)
      • ancho_banda_kbs        = 150.0 KB/s (~1.20 Mbps) (Restricción R2 de canal)
      • porcentaje_critico_min = 0.40  (40% mínimo hacia puertos críticos, R3)
      • peso_critico (c1)      = 1.5   (Valor estratégico de auditar un puerto crítico)
      • peso_comun   (c2)      = 1.0   (Valor estratégico de auditar un puerto común)
    """
    limite_ids_pps: float = 100.0
    ancho_banda_kbs: float = 150.0
    porcentaje_critico_min: float = 0.40
    peso_critico: float = 1.5
    peso_comun: float = 1.0
    costo_kb_critico: float = 2.0
    costo_kb_comun: float = 1.0


@dataclass
class RestriccionLineal:
    """
    Modela una desigualdad lineal en su forma estándar:
        a1 * X1 + a2 * X2 <= b

    Atributos:
      • codigo: Identificador corto (ej. "R1", "R2", "R3", "R4", "R5").
      • nombre: Explicación legible de qué protege esa restricción en la red.
      • a1: Coeficiente que multiplica a X1 (tasa de puertos críticos).
      • a2: Coeficiente que multiplica a X2 (tasa de puertos comunes).
      • b:  Límite disponible del recurso (lado derecho de la desigualdad).
    """
    codigo: str
    nombre: str
    a1: float
    a2: float
    b: float


# ==================================================================================
# BLOQUE 2: CONSTRUCCIÓN ALGEBRAICA DE LAS RESTRICCIONES DEL ESCÁNER
# ==================================================================================

def construir_restricciones(params: ParametrosOptimizacion) -> List[RestriccionLineal]:
    """
    Traduce las condiciones técnicas de la red LAN a desigualdades matemáticas
    en la forma canónica `a1 * X1 + a2 * X2 <= b`.

    Explicación detallada de cada restricción:
      1) R1 - Sigilo frente al IDS/IPS:
             X1 + X2 <= limite_ids_pps
         La suma total de paquetes enviados por segundo no debe superar el umbral
         en el que un Firewall o sistema Snort/Suricata bloquearía nuestra IP.

      2) R2 - Capacidad de ancho de banda asignado:
             2.0 * X1 + 1.0 * X2 <= ancho_banda_kbs
         Sondear un puerto crítico consume ~2.0 KB (incluye lectura de cabeceras
         SMB/Telnet/RDP), mientras que un puerto común consume ~1.0 KB.

      3) R3 - Balance y cobertura mínima de puertos críticos (proporción alfa):
         Exigimos que X1 represente al menos una fracción `alfa` del total enviado:
             X1 >= alfa * (X1 + X2)
         Despejando algebraicamente para pasar todo al formato `<= 0`:
             X1 - alfa*X1 - alfa*X2 >= 0
             (1 - alfa)*X1 - alfa*X2 >= 0
         Multiplicando por -1 (lo cual invierte el signo de >= a <=):
             -(1 - alfa)*X1 + alfa*X2 <= 0
         Nota pedagógica: Cuando alfa = 0.40 (40%), esta expresión queda:
             -0.60*X1 + 0.40*X2 <= 0  --->  0.40*X2 <= 0.60*X1  --->  X2 <= 1.5*X1,
         ¡que es exactamente la ecuación simplificada del informe escrito!

      4) R4 y R5 - No negatividad física:
             X1 >= 0   equivale a   -1.0 * X1 + 0.0 * X2 <= 0
             X2 >= 0   equivale a    0.0 * X1 - 1.0 * X2 <= 0
    """
    alfa = params.porcentaje_critico_min

    return [
        RestriccionLineal(
            codigo="R1",
            nombre="Sigilo frente a IDS/Firewall (límite de paquetes/seg)",
            a1=1.0,
            a2=1.0,
            b=params.limite_ids_pps,
        ),
        RestriccionLineal(
            codigo="R2",
            nombre="Presupuesto de ancho de banda LAN (KB/s)",
            a1=params.costo_kb_critico,
            a2=params.costo_kb_comun,
            b=params.ancho_banda_kbs,
        ),
        RestriccionLineal(
            codigo="R3",
            nombre=f"Prioridad mínima del {round(alfa * 100)}% en puertos críticos",
            a1=-(1.0 - alfa),
            a2=alfa,
            b=0.0,
        ),
        RestriccionLineal(
            codigo="R4",
            nombre="No negatividad de puertos críticos (X1 >= 0)",
            a1=-1.0,
            a2=0.0,
            b=0.0,
        ),
        RestriccionLineal(
            codigo="R5",
            nombre="No negatividad de puertos comunes (X2 >= 0)",
            a1=0.0,
            a2=-1.0,
            b=0.0,
        ),
    ]


# ==================================================================================
# BLOQUE 3: MOTOR DE RESOLUCIÓN EXACTA (REGLA DE CRAMER + TEOREMA DE VÉRTICES)
# ==================================================================================

def intersecar_rectas(r_i: RestriccionLineal, r_j: RestriccionLineal) -> Optional[Tuple[float, float]]:
    """
    Calcula el punto exacto (X1, X2) donde se cruzan las rectas frontera de dos
    restricciones convirtiendo sus desigualdades en igualdades:
        Ec. 1:  r_i.a1 * X1 + r_i.a2 * X2 = r_i.b
        Ec. 2:  r_j.a1 * X1 + r_j.a2 * X2 = r_j.b

    Se aplica la Regla de Cramer para sistemas lineales de 2x2:
        Determinante principal:  Delta = (a1_i * a2_j) - (a2_i * a1_j)
        Si Delta == 0, las dos rectas son paralelas y nunca se cruzan.
        Si Delta != 0:
            X1 = ((b_i * a2_j) - (a2_i * b_j)) / Delta
            X2 = ((a1_i * b_j) - (b_i * a1_j)) / Delta
    """
    determinante = (r_i.a1 * r_j.a2) - (r_i.a2 * r_j.a1)

    # Si el determinante es prácticamente cero, las rectas son paralelas o colineales
    if abs(determinante) < EPSILON:
        return None

    x1 = ((r_i.b * r_j.a2) - (r_i.a2 * r_j.b)) / determinante
    x2 = ((r_i.a1 * r_j.b) - (r_i.b * r_j.a1)) / determinante

    # Limpieza estética de ceros negativos (-0.0 -> 0.0) propios de coma flotante
    if abs(x1) < EPSILON:
        x1 = 0.0
    if abs(x2) < EPSILON:
        x2 = 0.0

    return (x1, x2)


def es_punto_factible(x1: float, x2: float, restricciones: List[RestriccionLineal]) -> bool:
    """
    Verifica si una coordenada candidata (X1, X2) pertenece a la Región Factible.
    Un punto solo es válido si cumple TODAS las restricciones al mismo tiempo:
        a1 * X1 + a2 * X2 <= b + EPSILON
    Si viola aunque sea una sola desigualdad (por ejemplo, excede el ancho de banda
    o cae en valores negativos), queda descartado inmediatamente.
    """
    for r in restricciones:
        valor_izquierdo = (r.a1 * x1) + (r.a2 * x2)
        if valor_izquierdo > r.b + EPSILON:
            return False
    return True


def clasificar_modo_operacion(limite_ids: float, ancho_banda_kbs: float) -> str:
    """
    Etiqueta de forma descriptiva el perfil operativo según los límites elegidos
    para mostrar retroalimentación clara en la interfaz de usuario y en los logs.
    """
    if limite_ids <= 65 and ancho_banda_kbs <= 100:
        return "Modo Sigiloso (Red sensible)"
    if abs(limite_ids - 100.0) < 1.0 and abs(ancho_banda_kbs - 150.0) < 1.0:
        return "Modo Balanceado (Óptimo estándar)"
    if limite_ids >= 160 or ancho_banda_kbs >= 250:
        return "Modo Agresivo (Alto rendimiento)"
    return "Modo Personalizado (Adaptativo)"


def formatear_numero_limpio(valor: float) -> str:
    """
    Formatea números evitando decimales '.0' innecesarios para que el mensaje
    explicativo destinado al usuario sea limpio y natural (ej. 50 en vez de 50.0).
    """
    if abs(valor - round(valor)) < 0.05:
        return str(int(round(valor)))
    return f"{valor:.1f}"


def traducir_interseccion_amigable(interseccion: str) -> str:
    """
    Traduce el código algebraico del vértice óptimo (ej. 'R1 ∩ R2') a una frase
    cotidiana fácil de interpretar en el panel del usuario.
    """
    partes = {p.strip() for p in interseccion.split("∩")}
    if partes == {"R1", "R2"}:
        return "Equilibrio ideal entre firewall y red"
    if partes == {"R1", "R3"}:
        return "Equilibrio entre firewall y prioridad crítica"
    if partes == {"R2", "R3"}:
        return "Equilibrio entre red y prioridad crítica"
    if "R2" in partes and ("R5" in partes or "X2>=0" in partes):
        return "Máximo enfoque crítico permitido por tu red"
    if "R1" in partes and ("R5" in partes or "X2>=0" in partes):
        return "Máximo enfoque crítico permitido por el firewall"
    return "Punto óptimo dentro de tus límites seguros"


def resolver_programacion_lineal(params: ParametrosOptimizacion) -> Dict[str, Any]:
    """
    NÚCLEO MATEMÁTICO DEL MICROSERVICIO:
    Aplica el Teorema Fundamental de la Programación Lineal (Taha, 2017), el cual
    demuestra que en toda región factible convexa y acotada, el valor máximo de la
    función objetivo Z = c1*X1 + c2*X2 se alcanza siempre en al menos uno de los
    vértices (esquinas) del polígono factible.

    Pasos algorítmicos ejecutados:
      1. Construir las 5 restricciones lineales con los parámetros recibidos.
      2. Intersecar todos los pares posibles de rectas frontera (5 sobre 2 = 10 pares).
      3. Conservar únicamente los puntos que cumplen las 5 desigualdades (factibles)
         eliminando coordenadas duplicadas.
      4. Evaluar Z = peso_critico * X1 + peso_comun * X2 en cada vértice factible.
      5. Elegir el vértice ganador (X1*, X2*) que produce el Z* más alto.
      6. Calcular indicadores de ingeniería de red (conversión a Mbps, % de uso).
    """
    restricciones = construir_restricciones(params)
    vertices_unicos: List[Tuple[float, float, str]] = []

    # Paso 2 y 3: Recorrer cada par (i, j) de restricciones para hallar sus cruces
    total_restricciones = len(restricciones)
    for i in range(total_restricciones):
        for j in range(i + 1, total_restricciones):
            cruce = intersecar_rectas(restricciones[i], restricciones[j])
            if cruce is None:
                continue

            x1_cand, x2_cand = cruce
            if not es_punto_factible(x1_cand, x2_cand, restricciones):
                continue

            # Verificar que este vértice no haya sido registrado ya por otro cruce equivalente
            duplicado = False
            for vx1, vx2, _ in vertices_unicos:
                if abs(vx1 - x1_cand) < 1e-4 and abs(vx2 - x2_cand) < 1e-4:
                    duplicado = True
                    break

            if not duplicado:
                origen_cruce = f"{restricciones[i].codigo} ∩ {restricciones[j].codigo}"
                vertices_unicos.append((x1_cand, x2_cand, origen_cruce))

    # Respaldo defensivo en caso extremo de parámetros degenerados: el origen (0, 0)
    if not vertices_unicos:
        vertices_unicos.append((0.0, 0.0, "Origen"))

    # Ordenar los vértices por X1 ascendente y luego X2 ascendente para lectura limpia
    vertices_unicos.sort(key=lambda v: (round(v[0], 4), round(v[1], 4)))

    # Paso 4 y 5: Evaluar la función objetivo Z en cada esquina y hallar el máximo
    detalle_vertices: List[Dict[str, Any]] = []
    mejor_vertice: Optional[Dict[str, Any]] = None

    for idx, (vx1, vx2, cruce_lbl) in enumerate(vertices_unicos):
        valor_z = (params.peso_critico * vx1) + (params.peso_comun * vx2)
        info_v = {
            "indice": idx + 1,
            "x1": round(vx1, 2),
            "x2": round(vx2, 2),
            "z": round(valor_z, 2),
            "interseccion": cruce_lbl,
        }
        detalle_vertices.append(info_v)

        # Criterio de selección: mayor Z; en caso de empate en Z (recta objetivo paralela
        # a una restricción), se prefiere el vértice con mayor cobertura crítica X1.
        if (
            mejor_vertice is None
            or valor_z > mejor_vertice["_z_exacto"] + EPSILON
            or (
                abs(valor_z - mejor_vertice["_z_exacto"]) <= EPSILON
                and vx1 > mejor_vertice["_x1_exacto"]
            )
        ):
            mejor_vertice = {
                **info_v,
                "_x1_exacto": vx1,
                "_x2_exacto": vx2,
                "_z_exacto": valor_z,
            }

    assert mejor_vertice is not None
    x1_opt = mejor_vertice["_x1_exacto"]
    x2_opt = mejor_vertice["_x2_exacto"]
    z_max = mejor_vertice["_z_exacto"]

    # Paso 6: Cálculo de telemetría de red y conversión física de unidades
    # Conversión estándar de telecomunicaciones: 1 Byte = 8 bits -> Mbps = (KB/s * 8) / 1000
    paquetes_totales_pps = x1_opt + x2_opt
    consumo_kbs = (params.costo_kb_critico * x1_opt) + (params.costo_kb_comun * x2_opt)
    consumo_mbps = (consumo_kbs * 8.0) / 1000.0
    limite_mbps = (params.ancho_banda_kbs * 8.0) / 1000.0

    uso_ids_pct = (paquetes_totales_pps / params.limite_ids_pps * 100.0) if params.limite_ids_pps > 0 else 0.0
    uso_canal_pct = (consumo_kbs / params.ancho_banda_kbs * 100.0) if params.ancho_banda_kbs > 0 else 0.0
    proporcion_critica_real = (x1_opt / paquetes_totales_pps * 100.0) if paquetes_totales_pps > 0 else 0.0

    # Identificar qué restricciones quedaron activas (al 100% de su capacidad) en el óptimo
    restricciones_activas: List[str] = []
    for r in restricciones[:3]:  # R1, R2, R3 (omitimos ejes de no negatividad)
        lhs = (r.a1 * x1_opt) + (r.a2 * x2_opt)
        if abs(lhs - r.b) <= 1e-3:
            restricciones_activas.append(r.codigo)

    modo_operacion = clasificar_modo_operacion(params.limite_ids_pps, params.ancho_banda_kbs)
    interseccion_amigable = traducir_interseccion_amigable(mejor_vertice["interseccion"])

    x1_txt = formatear_numero_limpio(x1_opt)
    x2_txt = formatear_numero_limpio(x2_opt)
    tot_txt = formatear_numero_limpio(paquetes_totales_pps)
    z_txt = formatear_numero_limpio(z_max)
    kbs_txt = formatear_numero_limpio(consumo_kbs)
    mbps_txt = f"{consumo_mbps:.2f}"
    ids_pct_txt = formatear_numero_limpio(uso_ids_pct)

    if x2_opt < 0.5 and params.ancho_banda_kbs >= (params.costo_kb_critico * params.limite_ids_pps):
        explicacion = (
            f"En resumen: tu red tiene ancho de banda de sobra, por lo que el único tope activo es el firewall: "
            f"el modelo dedica los {x1_txt} pps (paquetes por segundo) a priorizar puertos peligrosos "
            f"(revisando los comunes con ritmo ligero de respaldo) para lograr {z_txt} puntos usando solo "
            f"{kbs_txt} KB/s ({mbps_txt} Mbps). Si tu firewall lo permite, sube la barra 1 para aprovechar aún más tu red."
        )
    else:
        explicacion = (
            f"En resumen: el escáner enviará {x1_txt} pps (paquetes por segundo) a puertos peligrosos "
            f"y {x2_txt} pps a puertos normales ({tot_txt} pps en total). Así consigue el mejor aprovechamiento "
            f"posible ({z_txt} puntos de efectividad) ocupando apenas {kbs_txt} KB/s ({mbps_txt} Mbps) de tu red local, "
            f"sin poner lento el internet ni provocar bloqueos del firewall ({ids_pct_txt}% del margen seguro)."
        )

    # Diccionario final que se serializará a JSON para entregarlo a Spring Boot
    return {
        "exito": True,
        "fuenteMotor": "MICROSERVICIO_PYTHON",
        "microservicioActivo": True,
        "modoOperacion": modo_operacion,
        "x1Optimo": round(x1_opt, 2),
        "x2Optimo": round(x2_opt, 2),
        "paquetesTotalesPps": round(paquetes_totales_pps, 2),
        "zMaximo": round(z_max, 2),
        "consumoAnchoBandaKbs": round(consumo_kbs, 2),
        "consumoAnchoBandaMbps": round(consumo_mbps, 3),
        "limiteAnchoBandaKbs": round(params.ancho_banda_kbs, 2),
        "limiteAnchoBandaMbps": round(limite_mbps, 3),
        "limiteIdsPps": round(params.limite_ids_pps, 2),
        "porcentajeCriticoMin": round(params.porcentaje_critico_min * 100.0, 1),
        "proporcionCriticaReal": round(proporcion_critica_real, 1),
        "usoIdsPorcentaje": round(uso_ids_pct, 1),
        "usoCanalPorcentaje": round(uso_canal_pct, 1),
        "pesoCritico": round(params.peso_critico, 2),
        "pesoComun": round(params.peso_comun, 2),
        "interseccionOptima": mejor_vertice["interseccion"],
        "interseccionAmigable": interseccion_amigable,
        "restriccionesActivas": restricciones_activas,
        "verticesFactibles": detalle_vertices,
        "explicacion": explicacion,
    }


# ==================================================================================
# BLOQUE 4: VALIDACIÓN DE ENTRADA Y CAPA DE TRANSPORTE HTTP / REST JSON
# ==================================================================================

def extraer_parametros_seguros(cuerpo_json: Dict[str, Any]) -> ParametrosOptimizacion:
    """
    Lee el JSON enviado por Spring Boot, convierte tipos numéricos y aplica límites
    de seguridad (clamping) para impedir divisiones por cero o valores fuera de rango.
    """
    def leer_float(clave: str, por_defecto: float, minimo: float, maximo: float) -> float:
        valor_bruto = cuerpo_json.get(clave, por_defecto)
        try:
            valor = float(valor_bruto)
        except (TypeError, ValueError):
            valor = por_defecto
        return max(minimo, min(maximo, valor))

    # Nota: `porcentajeCriticoMin` puede llegar como fracción (0.40) o como porcentaje (40).
    # Normalizamos automáticamente ambos formatos al rango decimal [0.05, 0.95].
    raw_pct = cuerpo_json.get("porcentajeCriticoMin", cuerpo_json.get("porcentaje_critico_min", 0.40))
    try:
        pct_val = float(raw_pct)
    except (TypeError, ValueError):
        pct_val = 0.40
    if pct_val > 1.0:
        pct_val = pct_val / 100.0
    pct_val = max(0.05, min(0.95, pct_val))

    limite_ids = leer_float(
        "limiteIdsPps",
        leer_float("limite_ids_pps", 100.0, 10.0, 2000.0),
        10.0,
        2000.0,
    )
    ancho_banda = leer_float(
        "anchoBandaKbs",
        leer_float("ancho_banda_kbs", 150.0, 20.0, 5000.0),
        20.0,
        5000.0,
    )
    peso_c1 = leer_float(
        "pesoCritico",
        leer_float("peso_critico", 1.5, 0.1, 20.0),
        0.1,
        20.0,
    )
    peso_c2 = leer_float(
        "pesoComun",
        leer_float("peso_comun", 1.0, 0.1, 20.0),
        0.1,
        20.0,
    )

    return ParametrosOptimizacion(
        limite_ids_pps=limite_ids,
        ancho_banda_kbs=ancho_banda,
        porcentaje_critico_min=pct_val,
        peso_critico=peso_c1,
        peso_comun=peso_c2,
    )


class ManejadorMicroservicioIO(BaseHTTPRequestHandler):
    """
    Controlador HTTP que atiende las solicitudes provenientes de Java Spring Boot.

    Endpoints disponibles:
      • GET  /api/salud     -> Confirma que el microservicio Python está vivo y listo.
      • POST /api/optimizar -> Recibe restricciones en JSON y responde el óptimo lineal.
    """

    def _enviar_json(self, codigo_http: int, datos: Dict[str, Any]) -> None:
        """Serializa un diccionario Python a UTF-8 JSON y añade cabeceras HTTP estándar."""
        cuerpo_bytes = json.dumps(datos, ensure_ascii=False).encode("utf-8")
        self.send_response(codigo_http)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(cuerpo_bytes)))
        # Cabeceras CORS por si se consulta en pruebas locales directas
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(cuerpo_bytes)

    def do_OPTIONS(self) -> None:
        """Responde peticiones preflight CORS de navegadores."""
        self._enviar_json(200, {"estado": "OK"})

    def do_GET(self) -> None:
        """
        Endpoint de verificación de salud (Health Check).
        Spring Boot llama a `GET http://127.0.0.1:8001/api/salud` para mostrar en el
        panel web el indicador verde 'Microservicio Python • Activo'.
        """
        if self.path.startswith("/api/salud") or self.path == "/":
            self._enviar_json(
                200,
                {
                    "estado": "ACTIVO",
                    "servicio": "VulneraSolve Motor I.O. (Programación Lineal)",
                    "lenguaje": f"Python {sys.version.split()[0]}",
                    "puerto": PUERTO_MICROSERVICIO,
                },
            )
        else:
            self._enviar_json(404, {"exito": False, "error": f"Ruta no encontrada: {self.path}"})

    def do_POST(self) -> None:
        """
        Atiende el cálculo de optimización lineal instantáneo:
          • POST /api/optimizar -> Calcula el óptimo lineal (X1*, X2*, Z*) al instante.
        """
        if not self.path.startswith("/api/optimizar"):
            self._enviar_json(404, {"exito": False, "error": f"Ruta POST no válida: {self.path}"})
            return

        try:
            longitud = int(self.headers.get("Content-Length", "0"))
            texto_cuerpo = self.rfile.read(longitud).decode("utf-8") if longitud > 0 else "{}"
            datos_entrada = json.loads(texto_cuerpo) if texto_cuerpo.strip() else {}

            parametros = extraer_parametros_seguros(datos_entrada)
            resultado = resolver_programacion_lineal(parametros)

            print(
                f"[MOTOR-IO] Petición resuelta -> IDS<={parametros.limite_ids_pps} pps, "
                f"BW<={parametros.ancho_banda_kbs} KB/s, Crit>={round(parametros.porcentaje_critico_min*100)}% "
                f"| Óptimo: X1*={resultado['x1Optimo']} pps, X2*={resultado['x2Optimo']} pps, Z*={resultado['zMaximo']}"
            )
            self._enviar_json(200, resultado)

        except json.JSONDecodeError as err_json:
            self._enviar_json(400, {"exito": False, "error": f"JSON mal formado: {err_json}"})
        except Exception as err_gen:
            self._enviar_json(500, {"exito": False, "error": f"Error interno en solver I.O.: {err_gen}"})

    def log_message(self, format: str, *args: Any) -> None:
        """Silencia el log HTTP verboso por defecto para mantener limpia la consola."""
        return


# ==================================================================================
# BLOQUE 5: PUNTO DE ENTRADA Y AUTOVERIFICACIÓN DE LOS PERFILES DEL ESCÁNER
# ==================================================================================

def ejecutar_autoprueba_consola() -> None:
    """
    Modo de verificación rápida (`python optimizador_io.py --test`).
    Comprueba matemáticamente los 3 modos preconfigurados del Frontend sin abrir red.
    """
    escenarios = [
        ("Modo Sigiloso", ParametrosOptimizacion(limite_ids_pps=50, ancho_banda_kbs=80, porcentaje_critico_min=0.50)),
        ("Modo Balanceado (Informe)", ParametrosOptimizacion(limite_ids_pps=100, ancho_banda_kbs=150, porcentaje_critico_min=0.40)),
        ("Modo Agresivo", ParametrosOptimizacion(limite_ids_pps=200, ancho_banda_kbs=300, porcentaje_critico_min=0.40)),
    ]
    print("=" * 78)
    print("AUTOPRUEBA MATEMÁTICA — MOTOR DE INVESTIGACIÓN DE OPERACIONES VULNERASOLVE")
    print("=" * 78)
    for nombre, p in escenarios:
        res = resolver_programacion_lineal(p)
        print(f"\n▶ Escenario: {nombre}")
        print(f"  Entradas : IDS <= {p.limite_ids_pps} pps | Canal <= {p.ancho_banda_kbs} KB/s ({res['limiteAnchoBandaMbps']} Mbps) | Críticos >= {int(p.porcentaje_critico_min*100)}%")
        print(f"  Óptimo   : X1* = {res['x1Optimo']} pps | X2* = {res['x2Optimo']} pps | Z* = {res['zMaximo']} pts ({res['interseccionOptima']})")
        print(f"  Vértices : {res['verticesFactibles']}")


def iniciar_servidor() -> None:
    """
    Arranca el servidor HTTP multihilo en `http://127.0.0.1:8001` y calcula al inicio
    el caso base del informe (X1*=50, X2*=50, Z*=125.0) para confirmar operatividad.
    """
    caso_base = resolver_programacion_lineal(ParametrosOptimizacion())
    servidor = ThreadingHTTPServer((HOST_MICROSERVICIO, PUERTO_MICROSERVICIO), ManejadorMicroservicioIO)

    print("=" * 78)
    print("  VULNERASOLVE 2.0 — MICROSERVICIO PYTHON DE INVESTIGACIÓN DE OPERACIONES")
    print("=" * 78)
    print(f"  • URL activa       : http://{HOST_MICROSERVICIO}:{PUERTO_MICROSERVICIO}")
    print(f"  • Health Check     : GET  http://{HOST_MICROSERVICIO}:{PUERTO_MICROSERVICIO}/api/salud")
    print(f"  • Endpoint Solver  : POST http://{HOST_MICROSERVICIO}:{PUERTO_MICROSERVICIO}/api/optimizar")
    print(
        f"  • Verificación base: X1* = {caso_base['x1Optimo']} pps | "
        f"X2* = {caso_base['x2Optimo']} pps | Z* = {caso_base['zMaximo']} pts"
    )
    print("=" * 78)
    print("Esperando peticiones de optimización desde Spring Boot... (Ctrl+C para detener)")
    sys.stdout.flush()

    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\n[MOTOR-IO] Apagando microservicio Python de Investigación de Operaciones...")
    finally:
        servidor.server_close()


if __name__ == "__main__":
    if "--test" in sys.argv:
        ejecutar_autoprueba_consola()
    else:
        iniciar_servidor()

