import gzip, urllib.request
for sid in ["58362", "58367", "48698", "72434", "94610"]:
    for url in ["https://bulk.meteostat.net/v2/normals/%s.csv.gz" % sid, "https://bulk.meteostat.net/v2/monthly/%s.csv.gz" % sid]:
        try:
            raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "x"}), timeout=30).read()
            txt = gzip.decompress(raw).decode()
            lines = txt.splitlines()
            print(url, "OK", len(lines), "lines")
            for l in lines[:3] + lines[-2:]: print("   ", l)
        except Exception as e:
            print(url, "ERR", e)
