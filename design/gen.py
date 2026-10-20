import json, base64, sys, urllib.request, urllib.error, subprocess, pathlib, time
K = subprocess.check_output(["security","find-generic-password","-s","semif-openrouter","-w"]).decode().strip()
STYLE = ("Risograph print illustration, two-ink riso on deep charcoal-teal background #12181a, inks: coral #f07a63 and amber #e3a33a, "
         "visible grain, slight misregistration, halftone speckle, flat shapes, thick confident strokes, minimal, no text, no letters, no numbers, no logos.")
def call(model, content):
    body = {"model": model, "modalities": ["image", "text"], "messages": [{"role": "user", "content": content}]}
    r = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", json.dumps(body).encode(),
        {"Authorization": "Bearer " + K, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=300))

def gen(name, prompt, ref=None):
    content = [{"type": "text", "text": prompt + " " + STYLE}]
    if ref:
        content.append({"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(open(ref, "rb").read()).decode()}})
    for model in ["google/gemini-nano-banana-2.1", "google/gemini-nano-banana-2.1", "google/gemini-3-pro-image"]:
        try:
            d = call(model, content); break
        except urllib.error.HTTPError as e:
            print(name, model, "HTTP", e.code); time.sleep(20)
    else:
        return
    imgs = d["choices"][0]["message"].get("images") or []
    for i, im in enumerate(imgs):
        b = im["image_url"]["url"].split(",", 1)[1]
        p = pathlib.Path(__file__).parent / "gen" / f"{name}-{i}.png"; p.write_bytes(base64.b64decode(b)); print(p)
    print(name, model, "cost", d.get("usage", {}).get("cost"))

REF = "/Users/creative/synthjam-bot/site/banner8-1360x480.png"
jobs = {
 "icon_a": "Square app icon, 1:1. One big chunky coral square drum pad (like a drum machine trigger pad, rounded corners, glossy dot LED above it) with a thick amber patch cable plugging into it from the lower left, cable ending in a 3.5mm jack plug. Centered, large, fills 70% of the frame, legible at 48px.",
 "icon_b": "Square app icon, 1:1. A coral sound waveform that splits into three parallel amber patch cables which fan out to the right, each ending in a jack plug. Bold, centered, fills 70% of the frame, legible at 48px.",
 "empty_connect": "Wide 16:9 spot illustration: a compact drum machine seen from above with a 4x3 grid of square pads (two pads coral, rest outlined amber), a thick amber USB cable curling across the frame to a phone outline on the right. Lots of empty dark space.",
 "empty_stems": "Wide 16:9 spot illustration: a single coral audio waveform on the left that splits into four separate patch cables (two amber, two coral) which run right and plug into four round jacks in a vertical row. Lots of empty dark space.",
}
for n in (sys.argv[1:] or jobs):
    gen(n, jobs[n], REF)
