# CHANGELOG – Iteración 5 (2026-10-30): traslado con planilla

- `tipo_transito`: nuevo valor `TRASLADO_CON_PLANILLA` además de `DTA` y `OTM`. Se activa con "PARA TRASLADO",
  "TRASLADO CON PLANILLA", "TRASLADO POR PLANILLA" o "PLANILLA DE TRASLADO".
- Ubicación: DTA y OTM siguen válidos en Consignee o Notify. `TRASLADO_CON_PLANILLA` solo cuenta si está en el bloque
  del consignatario. Según la agencia (revisión 30-oct-2026), el puerto y la DIAN no lo aceptan desde el Notify.
- Pie de página (`*`, `**`) y cláusulas legales ("el traslado a un depósito...") no cuentan como mención literal.
- Evidencia OCR (corrida 29-oct-2026): 11 BL con la leyenda en el bloque del consignatario (HBL0366163, HBL0366164,
  HBL0366165, HBL0366084A/B/C/E/F, EGLV143653813415A/B/C). Dos casos fuera de regla, para decisión de Javier:
  CDAA4878101_Imagen (la leyenda aparece bajo Notify "SAME AS CONSIGNEE") y NNBDNS26017229 (la leyenda aparece
  en nota con asteriscos).
- Estado: cambio en repo. Pendiente de aplicar en la configuración viva y validar con corrida de los 24 BL.

# CHANGELOG – Iteración 4 (2026-10-29): peso bruto, NIT del consignee y tipo de pago

- `peso_bruto_kg`: busca el total en la fila de totales/columna G.W./KG, convierte MT/LBS, null si no está impreso.
- `consignee_nit`: busca en Consignee, Notify Party y bloque de endoso.
- Nuevo `condiciones_pago_tipo` (CONTADO/CREDITO/null) derivado SOLO del texto de `condiciones_pago`.
- Motivo: evidencia de la corrida de agosto (ZZ-GL250513PD-5 imprime 28608 KG y no se tomó; NGP3096941 sin NIT).
- Aplicado a la config viva el 2026-10-29; respaldo previo en `docs/backups/config-final-disabled-20261029.json`.

# CHANGELOG – Iteración 3 (FINAL): Corrección de omisión/duplicación de ítems + convergencia

**Fecha:** 2026-09-25
**Responsable:** 3HTP
**Spec:** `docs/specs/001-fragmentacion-items-facturas-largas.md`

## Causa raíz encontrada
`classification.sectionSplitting` estaba en `single_document`, valor **inválido** (válidos:
`disabled|page|llm_determined`). El sistema lo degradaba a `llm_determined` (confirmado en el stack
vía DynamoDB). El clasificador página-a-página marcaba `document_boundary=start` de más y **partía
una factura larga en varias secciones**; el transaccional leía solo `sections/1` → se perdían
ítems, flete y total. Evidencia: `26LC0602MH` partido en 2 secciones (219+44 ítems).

## Decisión de diseño (alineada a ADUACOL)
El sistema transaccional envía **un documento por mensaje** (confirmado por el equipo de ADUACOL,
sesión 2026-09-23). Por eso se usa **`sectionSplitting: disabled`** (todo el PDF = 1 sección),
que elimina la fragmentación de raíz. No se requiere prompt de clasificación personalizado.

## Cambios finales en config
1. `classification.sectionSplitting`: `single_document` → **`disabled`**.
2. `classification`: prompt **default** del accelerator (sin custom; `disabled` no decide boundary).
3. `extraction.task_prompt` regla 15 reforzada: (i) **ANCHOR THE COUNT FIRST** (tope MAX_LINE por
   el mayor nº de línea impreso) y (j) **NO CONSECUTIVE DUPLICATES**.
4. `extraction` regla 14 + `tipo_transito`: **flag literal DTA/OTM/null** (requisito de Javier
   Ambuila: solo el flag, sin alucinar). Sin `CONTINUACION_VIAJE` ni inferencia por zona franca.
5. `queue_sender`: whitelist de extensiones (`.pdf/.png/.jpg/.jpeg/.gif`) — buena práctica de la
   config de Anderson. **Recomendación para el stack de ADUACOL** (verificado 2026-10-29: la Lambda de pruebas no la tiene).

## State Machine podado (13 → 9 estados, alineado a Anderson)
Se eliminaron los pasos de Lambdas desactivadas para evitar cold-start:
`SetEmptyRuleValidationResult`, `RuleValidationOrchestration`, `SummarizationStep`,
`EvaluationStep`. Flujo final: OCR → Classification → ProcessSections → ProcessResults →
CheckRuleValidationEnabled → [ProcessRuleValidationSections] → CheckHITLRequired →
[MarkHITLPending] → WorkflowComplete. Aplicado vía `update_state_machine` (entorno de pruebas).
Backup de 13 estados en `docs/backups/`.

## Validación contra stack (final)
| Doc | Antes | Ahora | Resultado |
|---|---|---|---|
| 26LC0602MH | 2 secciones (219+44), se perdían 44 | **1 sección, 279 ítems**; flete=1150, total=66405.03, término="CFR BUENAVENTURA" | ✅ No fragmenta; resuelve obs. 1,3,4,5 |
| AYJ 26-02-AYJ | 1004 ítems (alucinación) | **255-623 ítems** | ✅ menos alucinación (ANCHOR/DUP) |
| SZX503600200 (BL DTA) | — | `tipo_transito="DTA"` (literal) | ✅ detecta sin inventar |
| WV2527TB4849 (BL DTA) | — | `tipo_transito=null` | ✅ no alucina el flag |
| Flujo podado (9 estados) | — | ejecución **SUCCEEDED** end-to-end | ✅ |

## Convergencia con el repositorio de Anderson (PR aparte)
La config de Anderson (repo `aduacloud-idp`, estructura modular) ya tenía la v2 idéntica. 3HTP
aportó sobre su estructura (rama `feat/3htp-antialucinacion-flag-literal`): reglas 15.i/15.j y flag
`tipo_transito` literal en `idp_extraction.yaml` / `idp_classes.yaml`. 3HTP adopta de Anderson: la
estructura modular de config, el state machine podado y el whitelist de extensiones.

## Limitación conocida (sin cerrar)
Alucinación residual en facturas con cientos de ítems idénticos (26LC: racha de ~23 ítems iguales).
El prompt la reduce, no la elimina. Solución de fondo: Lambda de post-proceso con chunking por
página (evolución opcional, spec 002). MALAWY (300+ ítems) puede exceder tiempos.

---

# CHANGELOG – Iteración 2: Correcciones Post-Revisión Aduacol

**Fecha:** 2026-08-04 al 2026-08-12
**Responsable:** Sebastian De La Vega (3HTP)
**Modelo:** Amazon Nova 2 Lite → Claude Haiku 4.5

---

## Contexto

El equipo de Aduacol (Javier Ambuila) revisó 56 documentos procesados con el IDP y reportó observaciones en `Control_Ejecucion_IDP_Aduacol.xlsx - Control IDP.csv`. Esta iteración corrige todos los issues reportados.

## Cambios en config.yaml

### 1. Modelo de Extracción
- **Antes:** `us.amazon.nova-2-lite-v1:0` (max_tokens: 51200)
- **Después:** `us.anthropic.claude-haiku-4-5-20251001-v1:0` (max_tokens: 64000)
- **Motivo:** Nova 2 Lite fallaba en facturas de 5+ páginas y alucinaba items. Haiku 4.5 es más rápido en docs cortos (5.2s vs 7.5s), procesa docs largos correctamente, y el costo adicional es ~$2.62/mes.

### 2. Término de Negociación (Fix #1 — afectaba 94% facturas)
- **Antes:** `"Extract only the code without the location"`
- **Después:** `"Extract the FULL incoterm including the delivery place/port"` con ejemplos (FOB Ningbo, CIF Cartagena, etc.)
- **Resultado:** 94% de facturas ahora extraen término completo.

### 3. Condiciones de Pago (Fix #2 — campo nuevo)
- Campo `condiciones_pago` agregado como semi-requerido en Factura Comercial.
- **Resultado:** 58% de facturas lo extraen (las que lo tienen impreso).

### 4. Tipo de Tránsito OTM/DTA (Fix #3 — campo nuevo)
- Campo `tipo_transito` agregado en Bill of Lading.
- Busca frases exactas: "mercancía en continuación de viaje", "mercancía en OTM", "mercancía en tránsito nacional", "mercancía en DTA".
- Retorna: "OTM", "DTA", "CONTINUACION_VIAJE", o null.
- **Resultado:** 9/24 BLs detectados correctamente (solo los que lo mencionan explícitamente).

### 5. Aduana de Llegada (Fix #4 — renombramiento)
- Description de `administracion_aduana` ahora dice "ADUANA DE LLEGADA" explícitamente.
- Referencia al mapeo con códigos DIAN.
- **Resultado:** 100% extraída correctamente.

### 6. Endoso Reforzado (Fix #5)
- Extraction guidelines expandidas con instrucciones detalladas de búsqueda de endoso.
- Schema del campo marcado como CRITICAL.
- Incluye variantes: overlay, segunda página, sellos, formatos abreviados.
- **Resultado:** 3/3 endosos detectados con empresa + NIT.

### 7. Valores Mejorados (Fix #6)
- `valor_total_usd`: description indica GRAND TOTAL incluyendo todos los cargos.
- `valor_fob`: clarificado como valor antes de flete/seguro.
- `otros_gastos`: campo nuevo para THC, handling, gastos origen.
- **Resultado:** 100% de facturas extraen valor total (antes 77%).

### 8. Clasificación Modo Transporte (nuevo)
- `tipo_bl` expandido: MASTER_BL, HOUSE_BL, GUIA_AEREA, CARTA_PORTE.
- Permite determinar si la carga es marítima, aérea o terrestre.

### 9. Contenedores (fix puntual)
- Description mejorada para extraer contenedores que aparecen en texto corrido dentro de "Description of Goods".
- Instrucción de cross-check con conteo indicado (e.g., "40HQ*4" = 4 contenedores).
- **Resultado:** BL APS20260200602 ahora extrae 4/4 contenedores (antes 3/4).

### 10. Anti-Alucinación (guideline #15)
- Instrucciones explícitas de no fabricar items repetidos.
- Self-check: "si estás repitiendo, STOP".
- **Limitación conocida:** No es 100% efectivo en facturas de 7+ páginas con 300+ items (MALAWY). Requiere chunking por página en siguiente iteración.

### 11. Extraction Guidelines
- Expandidas de 11 a 15 reglas.
- Final-instructions actualizadas con verificaciones.

## Archivo Nuevo: incoterms-2020.md
- Base de conocimiento de Incoterms 2020.
- Clasificación por modo de transporte (marítimo vs cualquiera).
- Reglas de validación Incoterm ↔ modo transporte.
- Tabla de responsabilidades (quién paga qué).
- Implicaciones para factura (cuándo debe desglosar flete/seguro).
- Puertos marítimos de Colombia con código DIAN.
- Fuente: LegisComex / ICC.

## Resultados Comparativos

| Métrica | v1 (Jul, Nova) | v2 FINAL (Ago, Haiku) |
|---|---|---|
| Documentos procesados | 56 (2 fallaron) | **60/60** |
| Término completo | 0% | **94%** |
| Condiciones pago | 0% | **58%** |
| Valor total | 77% | **100%** |
| Aduana de llegada | 100% (mal nombre) | **100%** |
| Endosos | 0/4 | **3/3** |
| Tipo tránsito | 0% | **9/24 detectados** |
| Docs largos (5+ págs) | FALLA | **OK** |
| Velocidad mediana | 7.5s | **5.2s** |
| Costo Bedrock/mes | ~$0.18 | ~$2.80 |

## Limitación Conocida

- Factura MALAWY260206-848H (7 páginas, ~300 items de cosméticos): Haiku 4.5 alucina ~836 items repetidos. Afecta 1/60 documentos (1.6%).
- **Solución propuesta:** Lambda de post-procesamiento con chunking por página. Siguiente iteración.

## Stack Desplegado

- Stack: `GenAI-IDP-ADUACOL`
- Región: us-east-1
- Modelo extracción: Claude Haiku 4.5
- Modelo clasificación: Amazon Nova 2 Lite (sin cambios)
