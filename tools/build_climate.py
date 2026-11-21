"""Builds app/src/main/assets/climate.json: monthly climate normals for every city in
tools/cities.csv. Runs on GitHub Actions (needs open internet).

Source: Meteostat station normals (based on national weather services' station records),
nearest station within 60 km with a full 12-month record. Fallback: 30 years (1991-2020)
of Open-Meteo ERA5 daily data averaged by month."""
import csv, gzip, io, json, math, sys, time, urllib.request

def get(url, tries=2):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "weather-station-classroom/1.0"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return r.read()
        except Exception as e:
            err = e
            time.sleep(2)
    raise err

def km(a, b, c, d):
    p = math.pi / 180
    x = math.sin((c - a) * p / 2) ** 2 + math.cos(a * p) * math.cos(c * p) * math.sin((d - b) * p / 2) ** 2
    return 12742 * math.asin(math.sqrt(x))

stations = json.loads(gzip.decompress(get("https://bulk.meteostat.net/v2/stations/lite.json.gz")))
print("stations:", len(stations), file=sys.stderr)

def meteostat(lat, lon):
    near = []
    for st in stations:
        loc = st.get("location") or {}
        if loc.get("latitude") is None: continue
        d = km(lat, lon, loc["latitude"], loc["longitude"])
        if d <= 60: near.append((d, st))
    near.sort(key=lambda x: x[0])
    best_t, best_r = None, None   # (score, data, station label)
    for d, st in near[:6]:
        try:
            raw = gzip.decompress(get("https://bulk.meteostat.net/v2/normals/%s.csv.gz" % st["id"])).decode()
        except Exception:
            continue
        # columns: start,end,month,tmin,tmax,prcp,wspd,pres,tsun
        temps, rain = {}, {}
        for r in csv.reader(io.StringIO(raw)):
            if len(r) < 6: continue
            try:
                period, m = (int(r[0]), int(r[1])), int(r[2])
            except ValueError:
                continue
            if r[3] and r[4]: temps.setdefault(period, {})[m] = (float(r[4]), float(r[3]))
            if r[5]: rain.setdefault(period, {})[m] = float(r[5])
        label = "%s (%s, %.0f km)" % ((st.get("name") or {}).get("en", st["id"]), st["id"], d)
        for per, v in temps.items():
            if len(v) == 12:
                score = (per[1], -d)   # newest period first, then nearest
                if best_t is None or score > best_t[0]: best_t = (score, per, v, label)
        for per, v in rain.items():
            if len(v) == 12:
                score = (per[1], -d)
                if best_r is None or score > best_r[0]: best_r = (score, per, v, label)
    if best_t is None: return None
    _, tper, t, tlabel = best_t
    months = [[round(t[m][0], 1), round(t[m][1], 1), round(best_r[2][m]) if best_r else None] for m in range(1, 13)]
    src = "Meteostat station normals %d-%d" % tper
    if best_r and best_r[3] != tlabel: src += "; rain %d-%d from %s" % (best_r[1][0], best_r[1][1], best_r[3])
    elif best_r and best_r[1] != tper: src += "; rain %d-%d" % best_r[1]
    return {"months": months, "source": src, "station": tlabel}

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
todo = []
with open("tools/cities.csv", encoding="utf-8") as f:
    for c in csv.DictReader(f): c["curated"] = True; todo.append(c)
try:
    with open("tools/cities_more.csv", encoding="utf-8") as f:
        for c in csv.DictReader(f):
            if not c["zh"]: continue
            if any(km(float(c["lat"]), float(c["lon"]), float(t["lat"]), float(t["lon"])) < 25 for t in todo): continue
            todo.append(c)
except FileNotFoundError:
    pass
print("cities to look up:", len(todo), flush=True)
for c in todo:
        lat, lon = float(c["lat"]), float(c["lon"])
        data = None
        try:
            data = meteostat(lat, lon)
        except Exception as e:
            print("meteostat failed", c["id"], e, file=sys.stderr)
        if data is None and not c.get("curated"):
            continue   # big-list city with no nearby station: leave it out
        if data is None:
            data = openmeteo(lat, lon)
        elif data["months"][0][2] is None:
            om = openmeteo(lat, lon)
            for m in range(12): data["months"][m][2] = om["months"][m][2]
            data["source"] += "; rain from Open-Meteo ERA5"
        if not c.get("curated"): print(c["en"], "|", data["station"], flush=True)
        else: print("%-12s %-40s %s" % (c["id"], data["source"], data["station"]), flush=True)
        zh = c["zh"][:-1] if c["zh"].endswith("市") and len(c["zh"]) > 2 else c["zh"]
        entry = {"id": c["id"], "en": c["en"], "zh": zh, "flag": c["flag"], "region": c["region"], "lat": lat, "lon": lon, **data}
        if c.get("country"): entry["country"] = c["country"]
        out.append(entry)

doc = {"note": "Each month: [average high C, average low C, rain/snow mm]. Built by tools/build_climate.py.", "cities": out}
with open("app/src/main/assets/climate.js", "w", encoding="utf-8") as f:
    f.write("// Climate normals for the Climate page. Built by tools/build_climate.py (GitHub Actions: Climate data).\n")
    f.write("var CLIMATE = " + json.dumps(doc, ensure_ascii=False) + ";\n")
print("wrote", len(out), "cities", file=sys.stderr)
