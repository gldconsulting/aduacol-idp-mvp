# Reportes IDP — Resultados de Procesamiento

## ¿Qué es esto?

Este directorio contiene el reporte visual de **todos los documentos procesados** por el pipeline de Intelligent Document Processing (IDP) desplegado para Aduacol en AWS.

El archivo `aduacol-idp-resultados.html` es una aplicación web auto-contenida que permite:

- 📊 Ver un **Dashboard** con métricas generales de procesamiento
- 📋 Consultar la **lista completa de ejecuciones** (63 documentos procesados)
- 🔍 Ver el **detalle de cada documento**: imagen original vs. datos extraídos automáticamente por el modelo
- 📄 Navegar documentos **multipágina** (hasta 12 páginas) con controles de paginación
- 📈 Consultar **métricas** de rendimiento del pipeline

## Documentos procesados

| Categoría | Cantidad | Tipos |
|---|---|---|
| IMPO (Importación) | 42 | Facturas comerciales + Bills of Lading |
| EXPO (Exportación) | 5 | Facturas comerciales + Bills of Lading |
| DTA (Declaración de Tránsito Aduanero) | 8 | Facturas + Bills of Lading |
| ENDOSO | 4 | Bills of Lading endosados |
| **Total** | **63** | |

## Cómo visualizar

### Opción 1: Abrir directamente en el navegador

1. Descargar o clonar este repositorio
2. Abrir el archivo `aduacol-idp-resultados.html` directamente en cualquier navegador moderno (Chrome, Firefox, Safari, Edge)
3. No requiere servidor web ni dependencias externas

```bash
# macOS
open reportes/aduacol-idp-resultados.html

# Linux
xdg-open reportes/aduacol-idp-resultados.html

# Windows
start reportes/aduacol-idp-resultados.html
```

### Opción 2: Servidor local (opcional)

Si desea servir el archivo por HTTP:

```bash
cd reportes/
python3 -m http.server 8080
# Abrir http://localhost:8080/aduacol-idp-resultados.html
```

## Funcionalidades del visor

### Vista Dashboard
- Total de documentos procesados
- Tasa de éxito (100%)
- Tiempo promedio de procesamiento
- Desglose por categoría y tipo de documento

### Vista Ejecuciones
- Lista de todos los documentos con filtros:
  - Por categoría (IMPO, EXPO, DTA, ENDOSO)
  - Por tipo (FACTURA_COMERCIAL, BILL_OF_LADING)
  - Búsqueda por nombre
  - Ordenamiento por nombre, fecha, tiempo de procesamiento
- Badge indicando número de páginas en documentos multipágina

### Vista Detalle (al hacer clic en un documento)
- **Panel izquierdo**: Imagen del documento original
  - Navegación de páginas (◀ ▶) para documentos multipágina
  - Indicador "Página X de Y"
  - Miniaturas clickeables
  - Navegación con flechas del teclado (← →)
- **Panel derecho**: Resultado de extracción
  - Campos clave extraídos automáticamente
  - JSON completo colapsable con toda la data estructurada

### Campos extraídos por tipo de documento

**FACTURA_COMERCIAL:**
- Número de factura, fecha de emisión
- Exportador (nombre, país)
- Importador (nombre, NIT)
- Valores: FOB, flete, seguro, total USD
- Término de negociación, moneda
- Ítems detallados (descripción, cantidad, precio unitario, total)

**BILL_OF_LADING:**
- Número de BL, tipo, fecha de emisión
- Shipper, Consignee, Notify Party
- Puerto de embarque, nave, estado del flete
- Contenedores (número, tipo, sello)
- Peso bruto, número de bultos
- Descripción de mercancía
- Estado de endoso

## Stack técnico

- **Pipeline**: AWS Step Functions + Amazon Bedrock (Claude) + Amazon Textract
- **Infraestructura**: CDK Stack `GenAI-IDP-ADUACOL`
- **Región**: us-east-1
- **Tiempo promedio de procesamiento**: ~11 segundos por documento

## Nota sobre el tamaño del archivo

El archivo HTML pesa ~43MB porque incluye las imágenes de todas las páginas de todos los documentos embebidas en base64. Esto es intencional para que sea 100% portable y auto-contenido, sin dependencias externas.

---

*Generado el 2026-07-08 por el equipo 3HTP como parte del Bridge Program AWS.*
