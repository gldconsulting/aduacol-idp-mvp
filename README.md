# Laboratorio: Efecto de Hiperparámetros en la Extracción con Bedrock

## Objetivo

Demostrar visualmente **por qué se usa temperature=0** en el IDP. Ejecuta la MISMA extracción N veces con diferentes hiperparámetros y compara campo por campo si los resultados son consistentes.

**Punto clave:** Con temperature=0, Bedrock siempre devuelve el mismo JSON. Con temperature>0, cada ejecución puede dar valores diferentes — inaceptable para un sistema transaccional.

---

## Qué Contiene Este Lab

| Componente | Recurso | Función |
|---|---|---|
| Bucket S3 | `lab-bedrock-documents-{account}` | Almacena el PDF de prueba |
| Lambda | `lab-bedrock-EJECUTAR-TEST` | Ejecuta extracción N veces y compara resultados |
| Frontend | `lab-hiperparametros.html` | Visualización interactiva con sliders |
| Frontend | `demo-extraccion.html` | Muestra campos extraídos por tipo de documento |

---

## Requisitos Previos

1. **AWS SAM CLI** instalado (`brew install aws-sam-cli`)
2. **AWS CLI** configurado con profile `3htp-col`
3. **Acceso a Amazon Bedrock** — El modelo `us.amazon.nova-lite-v1:0` debe estar habilitado en la cuenta (Model Access en la consola de Bedrock)
4. **Python 3.12**

---

## Desplegar (Paso a Paso)

```bash
# 1. Clonar el repo y cambiar al branch
git clone https://github.com/gldconsulting/aduacol-idp-mvp.git
cd aduacol-idp-mvp
git checkout lab-bedrock

# 2. Build
sam build

# 3. Deploy
sam deploy --guided --profile 3htp-col --region us-east-1
# Stack name: lab-bedrock
# Confirm changeset: Y
```

---

## Ejecutar

### Paso 1: Subir un documento de prueba

```bash
aws s3 cp tu-factura.pdf s3://lab-bedrock-documents-$(aws sts get-caller-identity --query Account --output text)/input/sample.pdf --profile 3htp-col
```

### Paso 2: Test DETERMINÍSTICO (temperature=0)

```bash
aws lambda invoke \
  --function-name lab-bedrock-EJECUTAR-TEST \
  --payload '{"temperature": 0, "top_p": 0, "num_runs": 3}' \
  --profile 3htp-col \
  --region us-east-1 \
  output.json && cat output.json | python3 -m json.tool
```

**Resultado esperado:** `"verdict": "DETERMINISTIC"` — todos los campos iguales en las 3 ejecuciones.

### Paso 3: Test NO-DETERMINÍSTICO (temperature=0.5)

```bash
aws lambda invoke \
  --function-name lab-bedrock-EJECUTAR-TEST \
  --payload '{"temperature": 0.5, "top_p": 0.9, "num_runs": 3}' \
  --profile 3htp-col \
  --region us-east-1 \
  output.json && cat output.json | python3 -m json.tool
```

**Resultado esperado:** `"verdict": "NON-DETERMINISTIC (X fields vary)"` — campos como fechas, montos o nombres varían entre ejecuciones.

### Paso 4: Test TRUNCADO (max_tokens=200)

```bash
aws lambda invoke \
  --function-name lab-bedrock-EJECUTAR-TEST \
  --payload '{"temperature": 0, "max_tokens": 200, "num_runs": 3}' \
  --profile 3htp-col \
  --region us-east-1 \
  output.json && cat output.json | python3 -m json.tool
```

**Resultado esperado:** JSON incompleto — campos faltantes porque el modelo se quedó sin tokens.

---

## Frontend Visual

Abrir `lab-hiperparametros.html` en el navegador para la demo interactiva con sliders (simulada, no requiere AWS).

---

## Qué Observar

| Configuración | Resultado | Impacto en IDP |
|---|---|---|
| temperature=0, top_p=0 | ✅ Siempre igual | Correcto para producción |
| temperature=0.5 | ❌ Campos varían | El sistema transaccional recibiría datos diferentes cada vez |
| temperature=1.0 | ❌ Alucinaciones | Inventa datos que no están en el documento |
| max_tokens=200 | ⚠️ JSON truncado | Pierde campos, parser falla |

---

## Relación con el IDP de ADUACOL

| Lab | IDP Real (config.yaml) |
|---|---|
| temperature: 0 | `extraction.temperature: 0` |
| top_p: 0 | `extraction.top_p: 0` |
| top_k: 5 | `extraction.top_k: 5` |
| max_tokens: 16000 | `extraction.max_tokens: 16000` |
| Modelo: Nova 2 Lite | `extraction.model: us.amazon.nova-2-lite-v1:0` |

**Conclusión:** Los hiperparámetros del IDP están en 0 porque necesitamos resultados **idénticos** cada vez que procesamos el mismo documento. Esto es un requisito del sistema transaccional de ADUACOL.

---

## Limpiar

```bash
aws s3 rm s3://lab-bedrock-documents-$(aws sts get-caller-identity --query Account --output text)/ --recursive --profile 3htp-col
sam delete --stack-name lab-bedrock --profile 3htp-col --region us-east-1
```

---

## Frontend Visual (sin despliegue)

Para ver las demos offline (simuladas, no requieren AWS):

```bash
# Lab de hiperparámetros (sliders + comparación campo por campo)
open lab-hiperparametros.html

# Demo de extracción (campos extraídos de Factura, BL, Endoso)
open demo-extraccion.html
```

Estos HTMLs son auto-contenidos — se abren directo en el navegador sin servidor.
