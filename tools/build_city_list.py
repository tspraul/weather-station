"""Builds tools/cities_more.csv: every national capital plus every city over 1.5 million people,
with Chinese names, for the Climate page's city search. Runs on GitHub Actions.

Sources: GeoNames (cities15000, countryInfo) for places and populations; Wikidata for Chinese names."""
import csv, io, json, sys, time, urllib.parse, urllib.request, zipfile

def get(url, data=None, headers=None):
    h = {"User-Agent": "weather-station-classroom/1.0 (github.com/tspraul/weather-station)"}
    h.update(headers or {})
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=180) as r:
                return r.read()
        except Exception as e:
            err = e
            time.sleep(70 if "429" in str(e) else 5)   # Wikidata may allow only 1 request a minute
    raise err

CONT = {"AF": "Africa", "AS": "Asia", "EU": "Europe", "NA": "North America", "SA": "South America", "OC": "Oceania", "AN": "Antarctica"}
countries = {}
for line in get("https://download.geonames.org/export/dump/countryInfo.txt").decode().splitlines():
    if line.startswith("#"): continue
    f = line.split("\t")
    if len(f) > 8: countries[f[0]] = {"name": f[4], "cont": CONT.get(f[8], f[8]), "pop": int(f[7] or 0)}

z = zipfile.ZipFile(io.BytesIO(get("https://download.geonames.org/export/dump/cities15000.zip")))
picked = []
for line in z.read("cities15000.txt").decode().splitlines():
    f = line.split("\t")
    gid, name, lat, lon, fcode, cc, pop = f[0], f[2], float(f[4]), float(f[5]), f[7], f[8], int(f[14] or 0)
    capital = fcode == "PPLC" and countries.get(cc, {}).get("pop", 0) >= 50000
    if capital or pop >= 1500000:
        picked.append({"gid": gid, "en": name, "lat": lat, "lon": lon, "cc": cc, "pop": pop, "capital": capital})
print("picked", len(picked), file=sys.stderr)

# Chinese names from Wikidata, matched by GeoNames ID
zh = {}
ids = [p["gid"] for p in picked]
for i in range(0, len(ids), 600):
    values = " ".join('"%s"' % g for g in ids[i:i + 600])
    q = """SELECT ?gid ?hans ?cn ?zh WHERE { VALUES ?gid { %s } ?item wdt:P1566 ?gid .
      OPTIONAL { ?item rdfs:label ?hans FILTER(lang(?hans) = "zh-hans") }
      OPTIONAL { ?item rdfs:label ?cn FILTER(lang(?cn) = "zh-cn") }
      OPTIONAL { ?item rdfs:label ?zh FILTER(lang(?zh) = "zh") } }""" % values
    res = json.loads(get("https://query.wikidata.org/sparql", data=urllib.parse.urlencode({"query": q, "format": "json"}).encode(),
                         headers={"Accept": "application/sparql-results+json", "Content-Type": "application/x-www-form-urlencoded"}))
    for b in res["results"]["bindings"]:
        name = (b.get("cn") or b.get("hans") or b.get("zh") or {}).get("value")
        if name and b["gid"]["value"] not in zh: zh[b["gid"]["value"]] = name
    time.sleep(65)
print("chinese names", len(zh), file=sys.stderr)

def flag(cc):
    if cc == "TW": return "📍"
    return "".join(chr(0x1F1E6 + ord(c) - 65) for c in cc)

with open("tools/cities_more.csv", "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["id", "en", "zh", "flag", "region", "lat", "lon", "country"])
    for p in sorted(picked, key=lambda p: -p["pop"]):
        c = countries.get(p["cc"], {})
        if c.get("cont") == "Antarctica": continue
        name_zh = zh.get(p["gid"], "")
        w.writerow(["g" + p["gid"], p["en"], name_zh, flag(p["cc"]), c.get("cont", ""), p["lat"], p["lon"], c.get("name", p["cc"])])
print("done", file=sys.stderr)
