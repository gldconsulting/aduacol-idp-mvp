# Matriz de Responsabilidades — Observaciones Revisión IDP (Javier Ambuila, 23/07/2026)

**Cliente:** ADUACOL · **Programa:** AWS BRIDGE Fase 2 · **Preparado por:** 3HTP · **Actualizada:** 2026-10-30
**Evidencia:** corrida final de 57 PDF únicos (62 filas) con la configuración final. Detalle por documento en
`docs/aduacol-idp-informe-final-20261029.html`.

## Alcance y cierre

Con esta entrega se **cierra el alcance de 3HTP en el programa Bridge**. 3HTP entrega el motor de extracción
configurado y validado, su documentación y el traspaso al equipo de ADUACOL. Lo que aparece como «evolución
opcional» no es un compromiso del programa: requeriría un alcance nuevo y se deja documentado como referencia.

| Entrega de 3HTP (cerrada) | Estado |
|---|---|
| Motor de extracción configurado (esquemas, prompts, modelos, state machine de 9 estados) | ✅ Entregado |
| Configuración final respaldada (`config-final-v2-20261029.json`) para replicarla en el ambiente de ADUACOL | ✅ Entregado |
| Corrida final de validación sobre los documentos de la revisión de Javier | ✅ Entregada |
| Informe de hallazgos e informe de mejoras (antes/después de cada observación) | ✅ Entregados |
| Documentación, specs 001 y 002, scripts de medición y guía de lectura | ✅ Entregados |
| Sesión de traspaso al equipo de ADUACOL | Esta sesión |

## Principio de frontera

El IDP **extrae** lo que el documento tiene impreso. El sistema transaccional de ADUACOL **consume** el JSON, aplica
reglas de negocio, mapeos a códigos DIAN y validación cruzada. Cada observación se cruzó con el texto OCR del
propio documento:

| Veredicto | Significado |
|---|---|
| RESUELTO | El dato está en el documento y el IDP lo entrega correctamente |
| LIMITE_DOCUMENTO | El dato **no está impreso** en el documento: no hay nada que extraer |
| LIMITE_OCR | El error ya viene en el texto que lee Textract |
| REGLA_NEGOCIO | Requiere interpretar o mapear: lo evalúa el transaccional de ADUACOL |
| DIFERENCIA MENOR | Diferencia pequeña que detecta el control de coherencia (suma de ítems vs FOB) |

## Resultado: 105 observaciones sobre 61 filas revisadas

| Veredicto | Cantidad |
|---|--:|
| RESUELTO por el IDP | 66 |
| REGLA_NEGOCIO (lo evalúa ADUACOL) | 22 |
| LIMITE_DOCUMENTO (dato no impreso) | 15 |
| LIMITE_OCR | 1 |
| DIFERENCIA MENOR (control de coherencia) | 1 |

## Detalle por patrón

| # | Observación | Resultado en la corrida final | Quién lo cierra |
|---|---|---|---|
| A | Identificar OTM / DTA / traslado con planilla en el BL | **6/26 BL lo declaran literal** (OTM/DTA, incluido el nombre de empresa con «OTM» en Consignee/Notify). **Iteración 5 (pendiente de validar):** `TRASLADO_CON_PLANILLA` se extrae cuando «PARA TRASLADO» o «TRASLADO CON PLANILLA» está en el bloque del consignatario. 11 BL lo tienen ahí; 2 casos quedan para decisión de Javier (CDAA4878101_Imagen, NNBDNS26017229). Sin mención en el consignatario, el IDP devuelve null (sin inferir). | IDP: extracción literal · **ADUACOL**: si el consignatario no trae DTA, OTM ni traslado, nacionalizar en el puerto de llegada (regla de negocio indicada por la agencia) |
| B | «Aduana» → «Aduana de Llegada» | 26/26 BL extraen el puerto de descarga en `administracion_aduana` (descripción «ADUANA DE LLEGADA»). La clave JSON no se renombró para no romper la integración. | IDP ✅ · renombrar y código DIAN: **ADUACOL** |
| C | Condiciones de pago | 20/34 filas la imprimen y se extraen (`condiciones_pago` + `condiciones_pago_tipo`). **14 no la imprimen**. | IDP ✅ · política de dato faltante: **ADUACOL** |
| D | Flete, total, ítems (26LC0602MH) | Una sola sección. Flete 1150, total 66405.03, 280 ítems (suma = 99 % del FOB; diferencia de 644.40). | IDP ✅ · control de coherencia: **ADUACOL** |
| E | Término de negociación | 26LC: «CFR BUENAVENTURA». JG y MALAWY **imprimen «CFR BUN»**; el IDP lo entrega tal cual. | IDP ✅ · mapear BUN → Buenaventura: **ADUACOL** |
| F | Endoso | 3 BL con endoso real detectados, con empresa y NIT verificados contra el OCR. | IDP ✅ |
| G | Typo en el Notify / NIT del Consignee | Typo: el OCR ya lo lee así; el NIT sí es correcto. NIT del Consignee en NGP3096941: el consignatario es una empresa extranjera (RIF J502903631); el NIT colombiano está en el endoso. | IDP ✅ · validar por NIT: **ADUACOL** |
| H | País exportador y FOB (EXPO) | 2/2 extraídos. | IDP ✅ |
| I | Peso bruto / total peso | ZZ-GL250513PD-5: 28608 kg impreso, ahora se extrae. TY-FE20260313001-2: **no imprime peso**. | IDP ✅ · dato faltante: **ADUACOL** |
| J | Cantidad (JG260130-513J) | `cantidad` presente en 221/221 ítems. | IDP ✅ |

## Facturas con muchos ítems: limitación conocida

En facturas de más de ~300 ítems (AYJ, AYJ-B, MALAWY) y en ATB, el arreglo `items[]` puede salir incompleto o con
repeticiones. Los campos de cabecera (número, importador, término, valor total, FOB, flete) sí son correctos y son los
que usa el checklist del inventario de datos. **Recomendación para ADUACOL:** control de coherencia en el transaccional
(suma de ítems ≈ FOB, tolerancia sugerida 2 %) y envío a revisión humana cuando no cuadre. Con ese control se detectan
los 4 documentos afectados. Las opciones para mejorar la extracción de ítems (procesamiento por bloques de páginas,
tablas de Textract, otro modelo) se describen en `docs/specs/002-chunking-facturas-grandes.md` como **evolución opcional**.

## Qué evalúa ADUACOL (transaccional)

1. Leer `sections/1/result.json` (por robustez, iterar `sections/*` y concatenar `items[]`).
2. Regla OTM/DTA/traslado con planilla: tipo de operación + `tipo_transito` + `consignee_nombre` + `notify_party_nombre` + `endoso`.
3. Renombrar `administracion_aduana` → Aduana de Llegada y mapear a código DIAN.
4. Mapear abreviaturas de puerto del término de negociación (BUN, etc.) y validar contra Incoterms 2020.
5. Validar identidad por NIT (no por nombre exacto); comparar importador ↔ consignatario ↔ endoso.
6. Controles aritméticos: FOB + flete + seguro + otros = total; suma de ítems ≈ FOB; revisión humana si no cuadra.
7. Política para datos que el proveedor no imprime (condiciones de pago, peso): exigir en origen o derivar.
8. Integración: usuario IAM, notificación S3 → SNS/SQS y, en su propio stack, el whitelist de extensiones de `queue_sender`.
9. Definir un set de documentos de referencia validado por Javier para medir futuros cambios.

## Evolución opcional (fuera del alcance del programa Bridge)

Referencias para una eventual fase posterior; no son compromisos de este programa:

- Procesamiento por bloques de páginas o tablas de Textract para facturas muy largas (spec 002).
- Campo `tipo_transito_sugerido` con señales citadas del documento.
- Monitoreo y alarmas de tiempo por documento; política de revisión humana (HITL).
