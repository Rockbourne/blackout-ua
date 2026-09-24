from datetime import datetime, timezone
import httpx
from fastapi import FastAPI, HTTPException

app = FastAPI(title="Blackout UA API", version="0.2.0")

YASNO_BASE = "https://app.yasno.ua/api/blackout-service/public/shutdowns"
YASNO_REGIONS = {
    "kyiv": {"region_id": 25, "dso_id": 902},
    "dnipro-dtek": {"region_id": 3, "dso_id": 301},
    "dnipro-cek": {"region_id": 3, "dso_id": 303},
}

STATUS_MAP = {
    "NoOutages": "ON",
    "WaitingForSchedule": "UNKNOWN",
}

@app.get("/")
async def root():
    return {"name": "Blackout UA API", "version": "0.2.0", "docs": "/docs"}

@app.get("/health")
async def health():
    return {"status": "ok", "time": datetime.now(timezone.utc).isoformat()}

@app.get("/api/v1/regions")
async def regions():
    return {"regions": [{"id": k, "provider": "yasno", **v} for k, v in YASNO_REGIONS.items()]}

def normalize_day(day: dict) -> dict:
    raw_status = day.get("status")
    date = day.get("date")
    return {
        "date": date[:10] if isinstance(date, str) else date,
        "status": STATUS_MAP.get(raw_status, raw_status or "UNKNOWN"),
        "outages": day.get("slots") or [],
    }

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

    group_data = data.get(group)
    if group_data is None:
        raise HTTPException(
            status_code=404,
            detail={"message": "Group not found", "group": group, "available_groups": list(data.keys())},
        )

    return {
        "region": region,
        "group": group,
        "provider": "yasno",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "provider_updated_at": group_data.get("updatedOn"),
        "today": normalize_day(group_data.get("today") or {}),
        "tomorrow": normalize_day(group_data.get("tomorrow") or {}),
    }
