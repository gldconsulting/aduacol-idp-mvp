# Métricas IDP ADUACOL — Resumen para el equipo

**Para:** Anderson Cárdenas, Javier Cuadros (ADUACOL)
**De:** 3HTP — Programa AWS BRIDGE Fase 2
**Fecha:** 2026-09-25
**Stack medido:** GenAI-IDP-ADUACOL · us-east-1

---

## 1. Qué se midió y cómo

Todas las cifras salen de fuentes reales del stack desplegado, no de estimaciones:
- **Tiempos:** historial de AWS Step Functions (`get_execution_history`) — timestamp de entrada y
  salida de cada estado. El total es `stopDate − startDate` de la ejecución.
- **Calidad de extracción:** JSON de salida en el bucket de resultados (`sections/N/result.json`).

Reproducible con `scripts/metricas_tiempos.py` y `scripts/validar_spec001.py`.

> **Aclaración clave (respuesta a la duda de la sesión):** los tiempos de 10-16s que aparecían en el
> reporte anterior eran **solo el paso de OCR**, NO el flujo completo. Este documento muestra el
> tiempo **end-to-end** del flujo orquestado en Step Functions.

**Configuración medida (versión final):** `sectionSplitting=disabled` · State Machine podado (9
estados) · Clasificación con **Amazon Nova 2 Lite** · Extracción con **Claude Haiku 4.5** ·
Assessment / Summarization / Rule Validation = OFF.

---

## 2. Comparación 3HTP vs ADUACOL (para afinar el ambiente)

Esta es la tabla clave para comparar. Anderson: llena la columna "ADUACOL (tu stack)" con tus
tiempos por step (mismos documentos, misma región us-east-1) y comparamos las diferencias.

**Referencia por step — documento tipo BL (1-2 páginas):**

| Step | 3HTP (nuestro stack) | ADUACOL (tu stack) | Diferencia |
|---|--:|--:|--:|
| OCR (Textract) | 6.2 - 8.3 s | _____ | _____ |
| Clasificación (Nova Lite) | 3.6 - 6.1 s | _____ | _____ |
| Extracción (Haiku 4.5) | 5.5 - 9.5 s | _____ | _____ |
| ProcessResults | 1.0 - 3.2 s | _____ | _____ |
| **TOTAL BL** | **17 - 30 s** | _____ | _____ |

**Referencia por step — documento tipo Factura larga (5-12 páginas):**

| Step | 3HTP (nuestro stack) | ADUACOL (tu stack) | Diferencia |
|---|--:|--:|--:|
| OCR (Textract) | 6.5 - 7.9 s | _____ | _____ |
| Clasificación (Nova Lite) | 5.3 - 6.7 s | _____ | _____ |
| Extracción (Haiku 4.5) | 66 - 82 s | _____ | _____ |
| ProcessResults | 1.1 - 1.4 s | _____ | _____ |
| **TOTAL Factura larga** | **83 - 95 s** | _____ | _____ |

**Cómo obtener TUS tiempos (mismo método, comparable):** corre `scripts/metricas_tiempos.py --run`
en tu cuenta. Extrae los mismos steps del historial de Step Functions.

### Qué diferencias son normales y cuáles indican afinación pendiente

| Si tu tiempo es... | Interpretación | Acción |
|---|---|---|
| **Extracción** difiere mucho (>20s en BL corto) | Variabilidad normal de Bedrock entre cuentas/momentos | No es del ambiente; reintentar y promediar |
| **OCR** mucho mayor (>15s en 1 pág) | Posible: Textract en cold path, o imágenes muy pesadas | Revisar tamaño/resolución de imagen generada |
| **Clasificación** presente y >8s | Puede que no estés saltando el paso cuando el tipo ya se conoce | Ver recomendación #1 (omitir clasificación) |
| **Summarization/Evaluation** aparecen con tiempo | State Machine NO podado (13 estados) | Aplicar el podado (13→9 estados) |
| **Assessment** con tiempo alto | Assessment no está OFF | Confirmar `assessment.enabled=false` |
| **Total** muy superior en mismo documento | Diferencia de config (sectionSplitting, modelo, flags) | Comparar config: `sectionSplitting=disabled`, Haiku 4.5, pasos OFF |

---

## 3. Tiempos por step y total (nuestras mediciones, flujo end-to-end)

| Step | BL 2 pág (027G) | BL 1-2 pág (SZX) | Factura 5 pág (26LC) | Factura 12 pág (AYJ) |
|---|--:|--:|--:|--:|
| OCR (Amazon Textract) | 6.2 s | 8.3 s | 6.5 s | 7.9 s |
| Clasificación (Nova Lite) | 3.6 s | 6.1 s | 5.3 s | 6.7 s |
| **Extracción (Claude Haiku 4.5)** | 5.5 s | 9.5 s | **81.5 s** | **66.1 s** |
| Assessment (OFF, solo retorno) | 0.8 s | 3.4 s | 0.9 s | 0.8 s |
| ProcessResults (arma el JSON) | 1.0 s | 3.2 s | 1.1 s | 1.4 s |
| Choice / WorkflowComplete | 0 s | 0 s | 0 s | 0 s |
| **TOTAL FLUJO** | **17.1 s** | **30.5 s** | **95.3 s** | **83.0 s** |

**Interpretación:**
- El tiempo depende del **tamaño del documento**, y casi todo se concentra en la **Extracción**
  (Bedrock / Claude Haiku 4.5): 70-90% del total en facturas largas.
- OCR y Clasificación son **estables** (~6-8s y ~4-7s) sin importar el tamaño.
- Los pasos de control (Choice, WorkflowComplete) son **instantáneos**.
- Extracción es tiempo del modelo → no se optimiza desde el pipeline; solo con chunking o cambio de
  modelo.

### 3.1 Todas las ejecuciones medidas (transparencia completa)

Se capturaron **10 ejecuciones** en total. La tabla de arriba resume las representativas; aquí están
todas, separadas por versión del flujo. Datos crudos: `metricas-tiempos-*.json`.

**Flujo PODADO (9 estados — versión final):**

| # | Documento | OCR | Clasif | Extracción | TOTAL |
|---|---|--:|--:|--:|--:|
| 1 | Factura AYJ (12 pág) | 7.9 s | 6.7 s | 66.1 s | 83.0 s |
| 2 | Factura 26LC (5 pág) | 6.5 s | 5.3 s | 81.5 s | 95.2 s |
| 3 | BL 027G (2 pág) | 6.2 s | 3.6 s | 5.5 s | 17.1 s |
| 4 | BL SZX (1-2 pág) | 8.3 s | 6.0 s | 9.5 s | 30.5 s |
| 5 | BL SZX (repetición) | 9.0 s | 5.6 s | 10.5 s | 31.6 s |

**Flujo NO podado (13 estados — antes del podado, para referencia):**

| # | Documento | OCR | Clasif | Extracción | TOTAL |
|---|---|--:|--:|--:|--:|
| 6 | Factura AYJ (viejo) | 9.5 s | 8.3 s | 144.2 s | 166.6 s |
| 7 | Factura AYJ (viejo) | 12.3 s | 7.7 s | 83.9 s | 108.2 s |
| 8 | Documento corto | 4.6 s | 2.8 s | 7.2 s | 24.6 s |
| 9 | Documento corto | 9.3 s | 4.2 s | 7.4 s | 39.1 s |
| 10 | Factura MALAWY (7 pág, 300+ ítems) | 10.6 s | 7.4 s | **1110.5 s** | **1146.0 s** |

> **Nota sobre MALAWY (#10):** 1146 s (~19 min) es el caso extremo de facturas con cientos de ítems
> idénticos. Confirma que este tipo de documento requiere la solución de **chunking por página**
> (ver sección 8). No es representativo del flujo normal — es el peor caso conocido.

> **Variabilidad de Bedrock:** las dos corridas de AYJ (#6 vs #7: 166s vs 108s; #1 podado: 83s)
> muestran que la latencia de extracción varía entre ejecuciones por la concurrencia del modelo,
> no por el ambiente. Al comparar, **promediar varias corridas** del mismo documento.

---

## 4. Efecto del State Machine podado (13 → 9 estados)

Se eliminaron los pasos de Lambdas desactivadas para evitar el cold-start:
`SummarizationStep`, `EvaluationStep`, `SetEmptyRuleValidationResult`, `RuleValidationOrchestration`.

| | 13 estados (antes) | 9 estados (podado) |
|---|--:|--:|
| SummarizationStep | ~0.6 s | eliminado |
| EvaluationStep | ~1.7 s | eliminado |
| Otros 2 estados de control | ~0 s | eliminados |
| Cold-start de esas Lambdas | presente | evitado |

**Ahorro determinístico:** ~2-3 s por ejecución + se evita levantar Lambdas que solo retornan.
El resto de la variabilidad entre corridas la domina la latencia de Bedrock (una misma factura
puede tardar 83s o 166s en distintas ejecuciones por la concurrencia del modelo).

---

## 5. Calidad de extracción (validación funcional)

Resultados de reprocesar documentos reales con la configuración final:

| Documento | Antes | Después | Estado |
|---|---|---|---|
| Factura 26LC0602MH (5 pág) | 2 secciones, se perdían 44 ítems; flete=0, total=0 | **1 sección, 279 ítems**; flete=1150, total=66405.03, término="CFR BUENAVENTURA" | ✅ Resuelto |
| Factura AYJ 26-02-AYJ (12 pág) | 1004 ítems (alucinación) | 255-623 ítems | ⚠️ Mejorado (residual) |
| BL SZX503600200 (DTA) | — | `tipo_transito = "DTA"` (detectado literal) | ✅ Sin alucinar |
| BL WV2527TB4849 (DTA) | — | `tipo_transito = null` (no lo dice → no inventa) | ✅ Sin alucinar |

**Observaciones del reporte de Javier que quedan resueltas** (caso 26LC como ejemplo): reflejar el
flete (1), reflejar el valor total (3), condiciones de pago (4), término de negociación completo (5).

---

## 6. Estado final del motor de extracción

| Aspecto | Valor final |
|---|---|
| Partición de documentos | `sectionSplitting = disabled` (1 documento = 1 sección) |
| Modelo de clasificación | Amazon Nova 2 Lite |
| Modelo de extracción | Claude Haiku 4.5 |
| Anti-alucinación | Reglas ANCHOR THE COUNT + NO CONSECUTIVE DUPLICATES activas |
| Flag OTM/DTA | Literal (DTA / OTM / null), sin inferencia — según requisito de Javier |
| Assessment / Summarization / Rule Validation | Deshabilitados |
| State Machine | Podado a 9 estados |

---

## 7. Recomendaciones de optimización de tiempo (por impacto)

1. **Omitir la Clasificación** cuando el transaccional ya sabe el tipo de documento (idea de
   Anderson): enviar la clase en el mensaje SQS y saltar `ClassificationStep`. Ahorro ~4-8 s/doc.
2. **Chunking por página** para facturas de 300+ ítems: paraleliza la extracción (el cuello de
   botella) y ayuda con la alucinación residual.
3. **Probar la nueva versión de Haiku** (anunciada como más rápida, costo similar).
4. **State Machine podado** — ya aplicado.

---

## 8. Limitación conocida (abierta)

Facturas con cientos de ítems idénticos (cosméticos) aún generan **alucinación residual** (26LC:
racha de ~23 ítems iguales). El prompt la reduce, no la elimina. La mejora de fondo (procesamiento por bloques de páginas, spec 002) es una evolución opcional fuera del alcance
del programa Bridge. Mientras tanto se recomienda un control de coherencia en el transaccional (suma de ítems ≈ FOB).

---

## 9. Cómo reproducir estas métricas

```bash
# Tiempos por step (analiza las últimas ejecuciones SUCCEEDED)
python3 scripts/metricas_tiempos.py

# Tiempos subiendo documentos de benchmark y midiendo
python3 scripts/metricas_tiempos.py --run

# Validación de calidad (secciones, ítems, flag tipo_transito)
python3 scripts/validar_spec001.py
```

Datos crudos en JSON: `docs/resultados-facturas-nuevas/metricas-tiempos-*.json`
