from datetime import datetime, timezone
import httpx
from fastapi import FastAPI, HTTPException, Query

app = FastAPI(title="Blackout UA API", version="0.3.0")

YASNO_ROOT = "https://app.yasno.ua/api/blackout-service/public/shutdowns"
YASNO_ADDRESS = f"{YASNO_ROOT}/addresses/v2"
YASNO_REGIONS = {
    "kyiv": {"region_id": 25, "dso_id": 902},
    "dnipro-dtek": {"region_id": 3, "dso_id": 301},
    "dnipro-cek": {"region_id": 3, "dso_id": 303},
}
STATUS_MAP = {"NoOutages": "ON", "WaitingForSchedule": "UNKNOWN"}

@app.get("/")
async def root():
    return {"name": "Blackout UA API", "version": "0.3.0", "docs": "/docs"}

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

async def yasno_get(path: str, params: dict | None = None):
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            response = await client.get(path, params=params)
            response.raise_for_status()
            return response.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(status_code=502, detail=f"Provider HTTP {exc.response.status_code}") from exc
    except (httpx.RequestError, ValueError) as exc:
        raise HTTPException(status_code=502, detail=f"Provider request failed: {type(exc).__name__}") from exc

@app.get("/api/v1/address/search")
async def address_search(
    region: str,
    street: str = Query(..., min_length=2),
    house: str = Query(..., min_length=1),
):
    config = YASNO_REGIONS.get(region)
    if config is None:
        raise HTTPException(status_code=404, detail=f"Unsupported region: {region}")

    common = {"regionId": config["region_id"], "dsoId": config["dso_id"]}

    streets = await yasno_get(
        f"{YASNO_ADDRESS}/streets",
        {**common, "query": street},
    )
    if not isinstance(streets, list) or not streets:
        raise HTTPException(status_code=404, detail="Street not found")

    street_item = streets[0]
    street_id = street_item.get("id")
    if street_id is None:
        raise HTTPException(status_code=502, detail="Provider returned street without id")

    houses = await yasno_get(
        f"{YASNO_ADDRESS}/houses",
        {**common, "streetId": street_id, "query": house},
    )
    if not isinstance(houses, list) or not houses:
        raise HTTPException(status_code=404, detail="House not found")

    exact = next(
        (x for x in houses if str(x.get("name", x.get("number", ""))).strip().casefold() == house.strip().casefold()),
        houses[0],
    )
    house_id = exact.get("id")
    if house_id is None:
        raise HTTPException(status_code=502, detail="Provider returned house without id")

    group_data = await yasno_get(
        f"{YASNO_ADDRESS}/group",
        {**common, "streetId": street_id, "houseId": house_id},
    )

    groups = []
    if isinstance(group_data, dict):
        if group_data.get("group") is not None and group_data.get("subgroup") is not None:
            groups.append(f'{group_data["group"]}.{group_data["subgroup"]}')
        raw_groups = group_data.get("groups")
        if isinstance(raw_groups, list):
            for item in raw_groups:
                if isinstance(item, str):
                    groups.append(item)
                elif isinstance(item, dict) and item.get("group") is not None and item.get("subgroup") is not None:
                    groups.append(f'{item["group"]}.{item["subgroup"]}')

    groups = list(dict.fromkeys(groups))
    if not groups:
        raise HTTPException(status_code=404, detail={"message": "Group not found for address", "provider_data": group_data})

    return {
        "region": region,
        "provider": "yasno",
        "street": street_item,
        "house": exact,
        "groups": groups,
    }

@app.get("/api/v1/outages/{region}/{group}")
async def outages(region: str, group: str):
    config = YASNO_REGIONS.get(region)
    if config is None:
        raise HTTPException(status_code=404, detail=f"Unsupported region: {region}")

    data = await yasno_get(
        f"{YASNO_ROOT}/regions/{config['region_id']}/dsos/{config['dso_id']}/planned-outages"
    )
    group_data = data.get(group)
    if group_data is None:
        raise HTTPException(status_code=404, detail={"message": "Group not found", "group": group})

    return {
        "region": region,
        "group": group,
        "provider": "yasno",
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "provider_updated_at": group_data.get("updatedOn"),
        "today": normalize_day(group_data.get("today") or {}),
        "tomorrow": normalize_day(group_data.get("tomorrow") or {}),
    }
