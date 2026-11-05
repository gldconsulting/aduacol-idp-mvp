#!/usr/bin/env python3
"""
Metricas de tiempo del IDP ADUACOL — desglose por step de Step Functions.

Para cada ejecucion SUCCEEDED reciente (o los docs que se suban), extrae del historial
de Step Functions el tiempo exacto que toma cada estado y el total del flujo orquestado.
Genera tabla en consola + JSON para compartir con Anderson / ADUACOL.

Uso:
  python3 scripts/metricas_tiempos.py            # analiza ultimas N ejecuciones SUCCEEDED
  python3 scripts/metricas_tiempos.py --run       # sube docs de prueba y luego mide
"""
import boto3, json, sys
from datetime import datetime, timezone
from pathlib import Path

import os

# Configuracion por variables de entorno (no hardcodear infraestructura ni cuentas).
# Exporta antes de correr:
#   export AWS_PROFILE=<tu-perfil>  AWS_REGION=us-east-1
#   export IDP_STATE_MACHINE_ARN=arn:aws:states:REGION:ACCOUNT:stateMachine:NOMBRE  (ver outputs del stack)
#   export IDP_INPUT_BUCKET=<input-bucket>  IDP_OUTPUT_BUCKET=<output-bucket>
PROFILE = os.environ.get("AWS_PROFILE")
REGION = os.environ.get("AWS_REGION", "us-east-1")
SM_ARN = os.environ["IDP_STATE_MACHINE_ARN"]
INPUT_BUCKET = os.environ["IDP_INPUT_BUCKET"]
OUTPUT_BUCKET = os.environ["IDP_OUTPUT_BUCKET"]
SAMPLES = Path("samples/Documentos BL - Facturas")

# Docs representativos: BL 1 pag, factura corta, factura larga
BENCH_DOCS = [
    {"id": "BL_1pag_SZX503600200", "file": "DTA/BL/SZX503600200.pdf", "tipo": "BL 1-2 pag"},
    {"id": "BL_1pag_027G532916", "file": "IMPO/BL/027G532916.pdf", "tipo": "BL 2 pag"},
    {"id": "FAC_larga_26LC0602MH", "file": "IMPO/FACTURAS/26LC0602MH.pdf", "tipo": "Factura 5 pag"},
    {"id": "FAC_larga_AYJ", "file": "IMPO/FACTURAS/AYJ 26-02-AYJ.pdf", "tipo": "Factura 12 pag"},
]

sess = boto3.Session(profile_name=PROFILE, region_name=REGION)
sf = sess.client("stepfunctions")
s3 = sess.client("s3")


def step_durations(execution_arn):
    """Devuelve {estado: segundos} y total, desde el historial de la ejecucion."""
    events = []
    tok = None
    while True:
        kw = {"executionArn": execution_arn, "maxResults": 1000}
        if tok:
            kw["nextToken"] = tok
        r = sf.get_execution_history(**kw)
        events += r["events"]
        tok = r.get("nextToken")
        if not tok:
            break
    entered, exited = {}, {}
    for ev in events:
        t = ev["type"]
        if t.endswith("StateEntered"):
            entered[ev["stateEnteredEventDetails"]["name"]] = ev["timestamp"]
        elif t.endswith("StateExited"):
            exited[ev["stateExitedEventDetails"]["name"]] = ev["timestamp"]
    durs = {nm: round((exited[nm] - entered[nm]).total_seconds(), 2)
            for nm in entered if nm in exited}
    return durs


def analyze_recent(n=10):
    execs = sf.list_executions(stateMachineArn=SM_ARN, statusFilter="SUCCEEDED",
                               maxResults=n)["executions"]
    rows = []
    for e in execs:
        total = round((e["stopDate"] - e["startDate"]).total_seconds(), 2)
        durs = step_durations(e["executionArn"])
        rows.append({"name": e["name"], "total_s": total, "steps": durs,
                     "start": e["startDate"].isoformat()})
    return rows


def upload_and_wait(doc):
    run = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    key = f"metricas-{run}/{doc['id']}.pdf"
    s3.upload_file(str(SAMPLES / doc["file"]), INPUT_BUCKET, key)
    import time
    rk = f"{key}/sections/1/result.json"
    start = time.time()
    while time.time() - start < 400:
        try:
            s3.get_object(Bucket=OUTPUT_BUCKET, Key=rk)
            time.sleep(5)
            return True
        except Exception:
            time.sleep(10)
    return False


def print_table(rows):
    # union de steps en orden de aparicion
    order = []
    for r in rows:
        for k in r["steps"]:
            if k not in order:
                order.append(k)
    print(f"\n{'STEP':32s}" + "".join(f"{r['name'][:14]:>16s}" for r in rows))
    print("-" * (32 + 16 * len(rows)))
    for st in order:
        print(f"{st:32s}" + "".join(f"{r['steps'].get(st,0):>16.2f}" for r in rows))
    print("-" * (32 + 16 * len(rows)))
    print(f"{'TOTAL FLUJO (s)':32s}" + "".join(f"{r['total_s']:>16.2f}" for r in rows))


def main():
    if "--run" in sys.argv:
        print("Subiendo docs de benchmark y esperando...")
        for d in BENCH_DOCS:
            ok = upload_and_wait(d)
            print(f"  {d['id']} ({d['tipo']}): {'OK' if ok else 'TIMEOUT'}")
    rows = analyze_recent(10)
    print_table(rows)
    out = Path("docs/resultados-facturas-nuevas") / f"metricas-tiempos-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    json.dump(rows, open(out, "w"), indent=2, ensure_ascii=False, default=str)
    print(f"\nJSON guardado: {out}")
    export_comparison_csv(rows, out.with_suffix(".comparacion.csv"))


# Baseline 3HTP (flujo podado, us-east-1) — referencia para comparar granularmente por step.
# Rango observado en nuestras mediciones (min-max de varias corridas).
BASELINE_3HTP = {
    "BL (1-2 pag)":      {"OCRStep": "6.2-8.3", "ClassificationStep": "3.6-6.1", "ExtractionStep": "5.5-9.5",  "ProcessResultsStep": "1.0-3.2", "TOTAL": "17-31"},
    "Factura (5-12 pag)":{"OCRStep": "6.5-7.9", "ClassificationStep": "5.3-6.7", "ExtractionStep": "66-82",    "ProcessResultsStep": "1.1-1.4", "TOTAL": "83-95"},
}


def export_comparison_csv(rows, path):
    """Genera un CSV comparable: por cada ejecucion medida, el tiempo por step junto al
    baseline de 3HTP, para que ADUACOL vea la diferencia granular por step."""
    import csv
    steps = ["OCRStep", "ClassificationStep", "ExtractionStep", "ProcessResultsStep"]
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Ejecucion", "Step", "Tiempo ADUACOL (s)", "Baseline 3HTP BL (s)", "Baseline 3HTP Factura (s)"])
        for r in rows:
            for st in steps:
                w.writerow([
                    r["name"][:14], st, round(r["steps"].get(st, 0), 2),
                    BASELINE_3HTP["BL (1-2 pag)"].get(st, ""),
                    BASELINE_3HTP["Factura (5-12 pag)"].get(st, ""),
                ])
            w.writerow([r["name"][:14], "TOTAL", round(r["total_s"], 2),
                        BASELINE_3HTP["BL (1-2 pag)"]["TOTAL"], BASELINE_3HTP["Factura (5-12 pag)"]["TOTAL"]])
    print(f"CSV comparativo (ADUACOL vs baseline 3HTP): {path}")


if __name__ == "__main__":
    sys.exit(main())
