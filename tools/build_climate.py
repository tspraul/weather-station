"""Builds app/src/main/assets/climate.json: monthly climate normals for every city in
tools/cities.csv. Runs on GitHub Actions (needs open internet).

Source: Meteostat station normals (based on national weather services' station records),
nearest station within 60 km with a full 12-month record. Fallback: 30 years (1991-2020)
of Open-Meteo ERA5 daily data averaged by month."""
import csv, gzip, io, json, math, sys, time, urllib.request

def get(url, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "weather-station-classroom/1.0"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except Exception as e:
            err = e
            time.sleep(3 * (i + 1))
    raise err

def km(a, b, c, d):
    p = math.pi / 180
    x = math.sin((c - a) * p / 2) ** 2 + math.cos(a * p) * math.cos(c * p) * math.sin((d - b) * p / 2) ** 2
    return 12742 * math.asin(math.sqrt(x))

stations = json.loads(gzip.decompress(get("https://bulk.meteostat.net/v2/stations/lite.json.gz")))
print("stations:", len(stations), file=sys.stderr)

def meteostat(lat, lon):
    near = []
    for s in stations:
        loc = s.get("location") or {}
        if loc.get("latitude") is None: continue
        d = km(lat, lon, loc["latitude"], loc["longitude"])
        if d <= 60: near.append((d, s))
    near.sort(key=lambda x: x[0])
    for d, s in near[:8]:
        try:
            raw = gzip.decompress(get("https://bulk.meteostat.net/v2/normals/%s.csv.gz" % s["id"])).decode()
        except Exception:
            continue
        rows = list(csv.reader(io.StringIO(raw)))
        # columns: start,end,month,tavg,tmin,tmax,prcp,wspd,pres,tsun
        best = {}
        for r in rows:
            if len(r) < 7: continue
            try:
                start, end, m = int(r[0]), int(r[1]), int(r[2])
                tmin, tmax, prcp = float(r[4]), float(r[5]), float(r[6])
            except ValueError:
                continue
            best.setdefault((start, end), {})[m] = (tmax, tmin, prcp)
        full = [k for k, v in best.items() if len(v) == 12]
        if not full: continue
        period = max(full, key=lambda k: (k[1], k[0]))
        v = best[period]
        return {"months": [[round(v[m][0], 1), round(v[m][1], 1), round(v[m][2])] for m in range(1, 13)],
                "source": "Meteostat station normals %d-%d" % period,
                "station": "%s (%s, %.0f km away)" % ((s.get("name") or {}).get("en", s["id"]), s["id"], d)}
    return None

def openmeteo(lat, lon):
    url = ("https://archive-api.open-meteo.com/v1/archive?latitude=%s&longitude=%s&start_date=1991-01-01&end_date=2020-12-31"
           "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=auto" % (lat, lon))
    d = json.loads(get(url))["daily"]
    acc = [[0, 0, 0, 0] for _ in range(12)]
    for t, hi, lo, pr in zip(d["time"], d["temperature_2m_max"], d["temperature_2m_min"], d["precipitation_sum"]):
        if hi is None or lo is None or pr is None: continue
        m = int(t[5:7]) - 1
        acc[m][0] += hi; acc[m][1] += lo; acc[m][2] += pr; acc[m][3] += 1
    years = 30
    return {"months": [[round(a[0] / a[3], 1), round(a[1] / a[3], 1), round(a[2] / years)] for a in acc],
            "source": "Open-Meteo ERA5 1991-2020", "station": "grid point"}

out = []
with open("tools/cities.csv", encoding="utf-8") as f:
    for c in csv.DictReader(f):
        lat, lon = float(c["lat"]), float(c["lon"])
        data = None
        try:
            data = meteostat(lat, lon)
        except Exception as e:
            print("meteostat failed", c["id"], e, file=sys.stderr)
        if data is None:
            data = openmeteo(lat, lon)
        print("%-12s %-40s %s" % (c["id"], data["source"], data["station"]), file=sys.stderr)
        out.append({"id": c["id"], "en": c["en"], "zh": c["zh"], "flag": c["flag"], "region": c["region"],
                    "lat": lat, "lon": lon, **data})

with open("app/src/main/assets/climate.json", "w", encoding="utf-8") as f:
    json.dump({"note": "Each month: [average high C, average low C, rain/snow mm]", "cities": out}, f, ensure_ascii=False, indent=0)
print("wrote", len(out), "cities", file=sys.stderr)
