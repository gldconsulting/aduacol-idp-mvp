#!/usr/bin/env python3
"""
Validación Spec 001 — reprocesa facturas largas tras corregir sectionSplitting + prompts.

Sube los PDFs de prueba al stack GenAI-IDP-ADUACOL, espera el resultado y cuenta:
- número de secciones generadas (CA-1: 26LC debe dar 1 sección)
- total de ítems SUMANDO TODAS las secciones (estrategia correcta del consumidor)
- detección de duplicados consecutivos (CA-3: anti-alucinación)

Lee sections/N/result.json para N=1..K hasta agotar (no solo sections/1).
"""
import boto3, json, time, sys
from pathlib import Path
from datetime import datetime, timezone

import os

# Configuracion por variables de entorno (no hardcodear infraestructura ni cuentas).
#   export AWS_PROFILE=<tu-perfil>  AWS_REGION=us-east-1
#   export IDP_INPUT_BUCKET=<input-bucket>  IDP_OUTPUT_BUCKET=<output-bucket>
PROFILE = os.environ.get("AWS_PROFILE")
REGION = os.environ.get("AWS_REGION", "us-east-1")
INPUT_BUCKET = os.environ["IDP_INPUT_BUCKET"]
OUTPUT_BUCKET = os.environ["IDP_OUTPUT_BUCKET"]
SAMPLES = Path("samples/Documentos BL - Facturas")
MAX_WAIT, POLL = 420, 15
RUN_ID = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
PREFIX = f"validacion-spec001-{RUN_ID}"

DOCS = [
    {"id": "26LC0602MH", "file": "IMPO/FACTURAS/26LC0602MH.pdf"},
    {"id": "AYJ_26-02-AYJ", "file": "IMPO/FACTURAS/AYJ 26-02-AYJ.pdf"},
    {"id": "WV2527TB4849_BL_DTA", "file": "DTA/BL/WV2527TB4849.pdf"},
    {"id": "SZX503600200_BL_DTA", "file": "DTA/BL/SZX503600200.pdf"},
]

s3 = boto3.Session(profile_name=PROFILE, region_name=REGION).client("s3")


def upload(doc):
    p = SAMPLES / doc["file"]
    key = f"{PREFIX}/{doc['id']}.pdf"
    s3.upload_file(str(p), INPUT_BUCKET, key)
    return key


def read_all_sections(key):
    """Lee sections/1..K/result.json hasta que no exista la siguiente. Devuelve lista de (sec, json)."""
    out, n = [], 1
    while n <= 50:
        rk = f"{key}/sections/{n}/result.json"
        try:
            body = json.loads(s3.get_object(Bucket=OUTPUT_BUCKET, Key=rk)["Body"].read())
            out.append((n, body))
            n += 1
        except Exception:
            break
    return out


def wait(key):
    start = time.time()
    while time.time() - start < MAX_WAIT:
        secs = read_all_sections(key)
        if secs:
            time.sleep(POLL)  # dar tiempo a que terminen todas las secciones
            secs = read_all_sections(key)
            return secs, round(time.time() - start, 1)
        time.sleep(POLL)
    return [], MAX_WAIT


def dup_run(items):
    """máxima racha de ítems consecutivos idénticos (desc+cant+precio)."""
    mx = run = 1
    prev = None
    for it in items:
        k = (it.get("descripcion"), it.get("cantidad"), it.get("valor_unitario"))
        if k == prev:
            run += 1; mx = max(mx, run)
        else:
            run = 1
        prev = k
    return mx if items else 0


def main():
    print(f"RUN {RUN_ID}  (config: sectionSplitting=llm_determined + prompts reforzados)\n")
    keys = {}
    for d in DOCS:
        keys[d["id"]] = upload(d)
        print(f"  subido {d['id']}")
    print("\nesperando resultados...\n")
    print(f"{'DOC':22s} {'SECC':>5s} {'ITEMS_TOT':>10s} {'MAX_DUP':>8s} {'TIME':>7s}  CLASES")
    results = {}
    for d in DOCS:
        secs, t = wait(keys[d["id"]])
        if not secs:
            print(f"{d['id']:22s} {'--':>5s}  TIMEOUT")
            results[d["id"]] = {"status": "timeout"}
            continue
        total_items, clases, maxdup, transito = 0, [], 0, []
        for n, body in secs:
            ir = body.get("inference_result", {})
            items = ir.get("items", []) or []
            total_items += len(items)
            clases.append(body.get("document_class", {}).get("type", "?"))
            maxdup = max(maxdup, dup_run(items))
            if "tipo_transito" in ir:
                transito.append(ir.get("tipo_transito"))
        print(f"{d['id']:22s} {len(secs):>5d} {total_items:>10d} {maxdup:>8d} {t:>6.1f}s  {clases} transito={transito}")
        results[d["id"]] = {"status": "ok", "secciones": len(secs),
                            "items_total": total_items, "max_dup_run": maxdup,
                            "clases": clases, "tipo_transito": transito, "time": t}
    Path("docs/resultados-facturas-nuevas").mkdir(parents=True, exist_ok=True)
    outf = f"docs/resultados-facturas-nuevas/validacion-spec001-{RUN_ID}.json"
    json.dump(results, open(outf, "w"), indent=2, ensure_ascii=False)
    print(f"\nguardado: {outf}")


if __name__ == "__main__":
    sys.exit(main())
