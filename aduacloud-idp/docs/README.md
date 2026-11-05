# Documentación y Métricas — IDP ADUACOL

Esta carpeta contiene los entregables de análisis y las herramientas para medir el motor de
extracción IDP. Preparado por 3HTP para el equipo de ADUACOL.

## Contenido

| Archivo | Qué es |
|---|---|
| `metricas-idp-para-aduacol.md` | Reporte de métricas: tiempos por step del flujo, calidad de extracción, estado final del motor y recomendaciones de optimización. |
| `matriz-responsabilidades-observaciones.md` | Qué observaciones del reporte de revisión resuelve 3HTP (extracción) y qué corresponde al sistema transaccional de ADUACOL. |
| `../scripts/metricas_tiempos.py` | Extrae los tiempos por step del flujo desde el historial de Step Functions. |
| `../scripts/validar_spec001.py` | Valida calidad de extracción (secciones, ítems, flag `tipo_transito`). |

## Cómo se miden los tiempos

Los tiempos salen del **historial de ejecución de AWS Step Functions**, no de estimaciones. Por
cada estado del flujo se calcula `StateExited − StateEntered`, y el total es
`stopDate − startDate` de la ejecución.

> **Importante:** los tiempos de 10-16s que aparecían en reportes anteriores eran **solo el paso de
> OCR**, no el flujo completo. El reporte de métricas muestra el tiempo end-to-end.

## Cómo ejecutar los scripts

Los scripts NO tienen credenciales ni datos de cuenta hardcodeados. Se configuran por variables de
entorno antes de correr:

```bash
# Autenticación (usa tu perfil/SSO de AWS CLI)
export AWS_PROFILE=<tu-perfil>
export AWS_REGION=us-east-1

# Recursos del stack IDP (obtenlos de los outputs de tu stack)
export IDP_STATE_MACHINE_ARN="arn:aws:states:REGION:ACCOUNT:stateMachine:NOMBRE   # ARN del state machine (ver outputs del stack)"
export IDP_INPUT_BUCKET="<input-bucket>"
export IDP_OUTPUT_BUCKET="<output-bucket>"
```

### Medir tiempos por step

```bash
# Analiza las últimas ejecuciones SUCCEEDED del flujo
python3 aduacloud-idp/scripts/metricas_tiempos.py

# Sube documentos de benchmark (BL corto, factura larga) y luego mide
python3 aduacloud-idp/scripts/metricas_tiempos.py --run
```

Salida: tabla por step + total en consola, y un JSON con los datos crudos.

### Validar calidad de extracción

```bash
python3 aduacloud-idp/scripts/validar_spec001.py
```

Sube documentos de prueba y reporta: número de secciones (debe ser 1 por documento con
`sectionSplitting=disabled`), total de ítems, y el valor de `tipo_transito` (DTA/OTM/null).

## Cómo comparar granularmente ADUACOL vs 3HTP (paso a paso)

Para determinar si hay diferencias de tiempo por el ambiente y qué afinar:

1. **Corre el script en tu stack** con los MISMOS documentos de benchmark:
   ```bash
   python3 aduacloud-idp/scripts/metricas_tiempos.py --run
   ```
2. El script genera automáticamente un **CSV comparativo** (`*.comparacion.csv`) con, por cada
   ejecución y cada step: tu tiempo (ADUACOL) al lado del **baseline de 3HTP**. Ábrelo en Excel.
3. **Compara step por step:**
   - Si tu **OCR** o **Clasificación** difieren mucho del baseline → revisar imagen/config (no es Bedrock).
   - Si tu **Extracción** difiere → es variabilidad de Bedrock; promedia varias corridas antes de concluir.
   - Si aparecen steps **Summarization/Evaluation** con tiempo → tu State Machine no está podado.
4. **Regla de oro:** compara el **mismo documento** y **promedia 3+ corridas** por documento. Una
   sola corrida no es concluyente por la variabilidad del modelo.

El baseline de 3HTP (por step, rango min-max) está embebido en el script y en el reporte
`metricas-idp-para-aduacol.md` (sección "Comparación 3HTP vs ADUACOL").

## Estado final del motor (referencia rápida)

| Aspecto | Valor |
|---|---|
| Partición de documentos | `sectionSplitting = disabled` (1 documento = 1 sección) |
| Clasificación | Amazon Nova Lite |
| Extracción | Claude Haiku 4.5 |
| Anti-alucinación | Reglas ANCHOR THE COUNT + NO CONSECUTIVE DUPLICATES |
| Flag OTM/DTA | Literal (DTA / OTM / null), sin inferencia |
| Assessment / Summarization / Rule Validation | Deshabilitados |
| State Machine | Podado a 9 estados |
