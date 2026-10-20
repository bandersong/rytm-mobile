import json, base64, sys, urllib.request, urllib.error, subprocess, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
O = subprocess.check_output(["security","find-generic-password","-s","orcarouter-key","-w"]).decode().strip()
import importlib.util
spec = importlib.util.spec_from_file_location("g", pathlib.Path(__file__).parent/"gen.py")
src = open(spec.origin).read(); STYLE = src.split('STYLE = (')[1].split(')\n')[0]; STYLE = eval('(' + STYLE + ')')
jobs = eval(src.split('jobs = ')[1].split('\n}\n')[0] + '\n}')
model, name = sys.argv[1], sys.argv[2]
body = {"model": model, "prompt": jobs[name] + " " + STYLE, "n": 1}
if "gpt-image" in model: body["size"] = "1024x1024" if name.startswith("icon") else "1536x1024"
r = urllib.request.Request("https://api.orcarouter.ai/v1/images/generations", json.dumps(body).encode(),
    {"Authorization": "Bearer " + O, "Content-Type": "application/json"})
try:
    d = json.load(urllib.request.urlopen(r, timeout=400))
except urllib.error.HTTPError as e:
    print(model, name, e.code, e.read()[:300]); sys.exit(1)
it = d["data"][0]
raw = base64.b64decode(it["b64_json"]) if it.get("b64_json") else urllib.request.urlopen(it["url"]).read()
p = pathlib.Path(__file__).parent/"gen"/f"{name}-{model.split('/')[-1]}.png"; p.write_bytes(raw); print(p, d.get("usage"))
