from datetime import datetime, timezone
import httpx
from fastapi import FastAPI, HTTPException

app = FastAPI(title="Blackout UA API", version="0.1.0")

YASNO_BASE = "https://app.yasno.ua/api/blackout-service/public/shutdowns/addresses/v2"
YASNO_REGIONS = {
    "kyiv": {"region_id": 25, "dso_id": 902},
    "dnipro-dtek": {"region_id": 3, "dso_id": 301},
    "dnipro-cek": {"region_id": 3, "dso_id": 303},
}

@app.get("/")
async def root():
    return {"name": "Blackout UA API", "version": "0.1.0", "docs": "/docs"}

@app.get("/health")
async def health():
    return {"status": "ok", "time": datetime.now(timezone.utc).isoformat()}

@app.get("/api/v1/regions")
async def regions():
    return {"regions": [{"id": k, "provider": "yasno", **v} for k, v in YASNO_REGIONS.items()]}

@app.get("/api/v1/outages/{region}/{group}")
async def outages(region: str, group: str):
    config = YASNO_REGIONS.get(region)
    if config is None:
        raise HTTPException(status_code=404, detail=f"Unsupported region: {region}")

    url = f"{YASNO_BASE}/regions/{config['region_id']}/dsos/{config['dso_id']}/planned-outages"
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            response = await client.get(url)
            response.raise_for_status()
            data = response.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502, detail=f"Provider HTTP {exc.response.status_code}") from exc
    except (httpx.RequestError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Provider request failed: {type(exc).__name__}") from exc

    return {
        "region": region,
        "group": group,
        "provider": "yasno",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "normalized": False,
        "data": data,
    }
