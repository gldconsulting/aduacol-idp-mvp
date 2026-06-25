# Laboratorio: Control de Concurrencia

## Objetivo

Demostrar visualmente la diferencia entre procesar documentos **SIN** y **CON** control de concurrencia.

---

## Qué Contiene Este Lab

| Componente | Demo A (SIN control) | Demo B (CON control) |
|---|---|---|
| Cola SQS | lab-concurrencia-SIN-control | lab-concurrencia-CON-control |
| Dispatcher Lambda | Dispara todo directo | Verifica slots en DynamoDB |
| Step Functions | lab-concurrencia-SIN-control | lab-concurrencia-CON-control |
| Slot Releaser | No tiene | Libera slot al terminar |
| DynamoDB Semaphore | No tiene | lab-concurrencia-semaphore |

---

## Requisitos Previos

1. **AWS SAM CLI** instalado (`brew install aws-sam-cli`)
2. **AWS CLI** configurado con profile `3htp-col`
3. **Python 3.12** (para las Lambdas)

## Desplegar (Paso a Paso)

```bash
# 1. Clonar el repo y cambiar al branch
git clone https://github.com/gldconsulting/aduacol-idp-mvp.git
cd aduacol-idp-mvp
git checkout lab-step-functions

# 2. Build
sam build

# 3. Deploy (primera vez usa --guided, después solo sam deploy)
sam deploy --guided --profile 3htp-col --region us-east-1
# Stack name: lab-concurrencia
# Confirm changeset: Y
```

Stack name: `lab-concurrencia`

---

## Ejecutar el Test

### Opcion 1: Desde la consola AWS

1. Ir a Lambda → `lab-concurrencia-EJECUTAR-TEST`
2. Crear test event con:

```json
{
  "num_documents": 20,
  "mode": "both"
}
```

3. Ejecutar

### Opcion 2: Desde CLI

```bash
aws lambda invoke \
  --function-name lab-concurrencia-EJECUTAR-TEST \
  --payload '{"num_documents": 20, "mode": "both"}' \
  --profile 3htp-col \
  --region us-east-1 \
  output.json

cat output.json
```

---

## Qué Observar

### 1. Step Functions → Ejecuciones

Ir a Step Functions en la consola:

**SIN control (`lab-concurrencia-SIN-control`):**
- Las 20 ejecuciones aparecen TODAS como "Running" al mismo tiempo
- Todas empiezan en el mismo segundo
- Si fuera Bedrock real: THROTTLING

**CON control (`lab-concurrencia-CON-control`):**
- Solo 5 ejecuciones aparecen como "Running" (el max configurado)
- Las demás van apareciendo de a 5 conforme terminan las anteriores
- Tiempo total: ~20s (4 tandas de 5 × 5 segundos cada una)

### 2. DynamoDB → Semaphore Table

Ir a DynamoDB → `lab-concurrencia-semaphore`:
- Ver el campo `currentCount` subir hasta 5 y nunca pasar de ahí
- Cuando baja a 0, todos terminaron

### 3. CloudWatch → Metricas

Ir a CloudWatch → Metrics → LabConcurrencia:
- **ConcurrentExecutions (SIN_CONTROL):** Pico de 20 simultáneas
- **ConcurrentExecutions (CON_CONTROL):** Nunca pasa de 5

---

## El Punto Clave para el Cliente

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  SIN CONTROL:                                               │
│  20 docs → 20 llamadas Bedrock simultáneas → THROTTLING     │
│  Resultado: documentos FALLAN                               │
│                                                             │
│  CON CONTROL:                                               │
│  20 docs → máximo 5 llamadas Bedrock a la vez → OK          │
│  Resultado: todos los documentos se procesan EXITOSAMENTE   │
│  (solo toma un poco más de tiempo)                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Es como un semáforo en una intersección:**
- Sin semáforo: todos pasan al mismo tiempo → choque (throttling)
- Con semáforo: pasan de a grupos → todos llegan a destino

---

## Limpiar

```bash
sam delete --stack-name lab-concurrencia --profile 3htp-col --region us-east-1
```

---

## Parámetros Configurables

| Parámetro | Default | Qué cambia |
|---|---|---|
| MaxConcurrency | 5 | Slots máximos en Demo B |
| Sleep en Lambda | 5s | Simula tiempo de Bedrock |
| num_documents | 20 | Documentos a procesar |

Para cambiar max concurrency:
```bash
sam deploy --parameter-overrides MaxConcurrency=3
```

---

## Relación con el IDP de ADUACOL

| Lab | IDP Real |
|---|---|
| lab-concurrencia-semaphore (DynamoDB) | EnvironmentConcurrencyTable |
| lab-concurrencia-dispatcher-CON-control | queue_processor |
| lab-concurrencia-slot-releaser | workflow_tracker |
| lab-concurrencia-SIN-control (SQS) | Lo que pasaría SIN el queue_processor |
| MaxConcurrency: 5 | MaxConcurrentWorkflows: 100 |
| Sleep 5s | Llamada real a Bedrock (~7-15s) |

---

## Frontend Visual (sin despliegue)

Para ver la demo visual offline (simulada, no requiere AWS):

```bash
# Demo de concurrencia (animación SIN vs CON control)
open demo-visual.html

# Lab de hiperparámetros (playground interactivo)
open lab-hiperparametros.html
```

Estos HTMLs son auto-contenidos — se abren directo en el navegador sin servidor.
