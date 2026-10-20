"""Stem server for the Rytm Mobile Android app.

POST /jobs (multipart: file, model)      -> {"id": ...}
GET  /jobs/{id}                          -> status, stems
GET  /jobs/{id}/stems/{name}             -> 48 kHz mono 16-bit WAV (Rytm-ready)
GET  /models                             -> the models offered
Auth: header X-Token must match env STEM_TOKEN. The server refuses to start without one.
"""
import os, uuid, threading, queue, subprocess, shutil, glob, re, traceback, hmac, json
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Header
from fastapi.responses import FileResponse

ROOT = Path(os.environ.get("STEM_DIR", Path.home() / "rytm-mobile" / "jobs"))
ROOT.mkdir(parents=True, exist_ok=True)
TOKEN = os.environ.get("STEM_TOKEN", "").strip()
if not TOKEN:
    raise SystemExit("STEM_TOKEN is empty, so anyone on the network could use this server. "
                     "Re-run the installer, or put a token in ~/.rytm-stems/token.")
MAX_UPLOAD = int(os.environ.get("STEM_MAX_UPLOAD_MB", "2048")) * 1024 * 1024
MAX_SECONDS = int(os.environ.get("STEM_MAX_SECONDS", "1800"))  # longest input that gets split
MODEL_DIR = os.environ.get("STEM_MODELS", str(ROOT.parent / "models"))


def _ffmpeg():
    """System ffmpeg if there is one, else the binary bundled with imageio-ffmpeg."""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


FFMPEG = _ffmpeg()

# name -> [(audio-separator model file, description), ...], best first. Intel Macs can't get
# a PyTorch newer than 2.2.2, so they get an older audio-separator (0.24.x) that doesn't know
# the newer models; there the next model that version does know is used instead.
CANDIDATES = {
    "stems": [("BS-Roformer-SW.ckpt", "Drums, bass, guitar, piano, other, vocals. BS-RoFormer SW, the best quality."),
              ("htdemucs_6s.yaml", "Drums, bass, guitar, piano, other, vocals. HTDemucs 6-stem (BS-RoFormer SW needs a newer PyTorch than this computer can run).")],
    "demucs": [("htdemucs_ft.yaml", "Drums, bass, other, vocals. HTDemucs, the fallback.")],
    "vocals": [("vocals_mel_band_roformer.ckpt", "Vocal and instrumental. Mel-Band RoFormer."),
               ("model_bs_roformer_ep_317_sdr_12.9755.ckpt", "Vocal and instrumental. BS-RoFormer ViperX 1297.")],
    "drumkit": [("MDX23C-DrumSep-aufr33-jarredou.ckpt", "Kick, snare, toms, hats, ride, crash, split from the drum stem.")],
}


def _supported_files():
    """File names this audio-separator version can download, or None if the list can't be read."""
    try:
        import logging
        from audio_separator.separator import Separator
        sup = Separator(output_dir=str(ROOT), model_file_dir=MODEL_DIR,
                        log_level=logging.WARNING).list_supported_model_files()
    except Exception:
        traceback.print_exc()
        return None
    names = set()

    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                names.add(k); walk(v)
        elif isinstance(o, (list, tuple)):
            for v in o:
                walk(v)
        elif isinstance(o, str):
            names.add(o)
    walk(sup)
    return names


def _pick_models():
    sup = _supported_files()
    picked = {}
    for mode, cands in CANDIDATES.items():
        ok = [c for c in cands if sup is None or c[0] in sup]
        if ok:
            picked[mode] = ok[0]
    if "drumkit" in picked and "stems" not in picked:
        del picked["drumkit"]  # the drum kit split starts from the stems split
    return picked


# name -> (audio-separator model file, description), only the modes this computer can run
MODELS = _pick_models()

jobs = {}
q = queue.Queue()


def save(j):
    tmp = ROOT / j["id"] / "job.json.tmp"
    tmp.write_text(json.dumps(j))
    tmp.replace(ROOT / j["id"] / "job.json")


def load_jobs():
    """Jobs survive a server restart; ones cut off mid-run are marked failed."""
    for f in ROOT.glob("*/job.json"):
        try:
            j = json.loads(f.read_text())
        except Exception:
            continue
        if j.get("status") in ("queued", "running"):
            j["status"] = "error"; j["error"] = "The computer restarted during this job. Split it again."
        jobs[j["id"]] = j


load_jobs()
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
_sep_cache = {}


def check(tok):
    if not hmac.compare_digest(tok.encode(), TOKEN.encode()):
        raise HTTPException(401, "bad token")


class Guard:
    """Checks the token and the upload size before any of the body is read or spooled to disk."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        h = {k.decode().lower(): v.decode() for k, v in scope["headers"]}

        async def deny(code, msg):
            body = json.dumps({"detail": msg}).encode()
            await send({"type": "http.response.start", "status": code,
                        "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
            await send({"type": "http.response.body", "body": body})

        if not hmac.compare_digest(h.get("x-token", "").encode(), TOKEN.encode()):
            return await deny(401, "bad token")
        if int(h.get("content-length", "0") or 0) > MAX_UPLOAD:
            return await deny(413, f"file too large (limit {MAX_UPLOAD // 2**20} MB)")
        seen = 0

        async def counted():
            nonlocal seen
            m = await receive()
            if m["type"] == "http.request":
                seen += len(m.get("body", b""))
                if seen > MAX_UPLOAD:
                    raise HTTPException(413, "file too large")
            return m
        return await self.app(scope, counted, send)


app.add_middleware(Guard)


def separator(model_file):
    from audio_separator.separator import Separator
    if model_file not in _sep_cache:
        s = Separator(output_dir=str(ROOT), output_format="WAV", model_file_dir=MODEL_DIR)
        s.load_model(model_filename=model_file)
        _sep_cache.clear()  # keep one model in memory
        _sep_cache[model_file] = s
    return _sep_cache[model_file]


def run_sep(model_file, src, outdir):
    s = separator(model_file)
    s.output_dir = str(outdir)
    if getattr(s, "model_instance", None):
        s.model_instance.output_dir = str(outdir)
    files = s.separate(str(src))
    return [Path(f) if os.path.isabs(f) else Path(outdir) / f for f in files]


def stem_label(p):
    m = re.findall(r"_\(([^)]+)\)_", p.name)
    return (m[-1] if m else p.stem).lower().replace(" ", "_")


def to_rytm(src, dst):
    # Rytm sample format: 48 kHz, mono, 16-bit PCM
    subprocess.run([FFMPEG, "-v", "error", "-y", "-i", str(src), "-ac", "1", "-ar", "48000",
                    "-sample_fmt", "s16", str(dst)], check=True)


def worker():
    while True:
        jid = q.get()
        j = jobs[jid]
        d = ROOT / jid
        wav = d / "input_audio.wav"
        raw = d / "raw"
        try:
            j["status"] = "running"; save(j)
            raw = d / "raw"; raw.mkdir(exist_ok=True)
            # Videos (screen recordings) and phone recorder formats: pull the audio out first.
            subprocess.run([FFMPEG, "-v", "error", "-y", "-i", j["src"], "-t", str(MAX_SECONDS), "-vn", "-ac", "2",
                            "-ar", "44100", "-c:a", "pcm_f32le", str(wav)], check=True, timeout=600)
            j["src"] = str(wav)
            mode = j["model"]
            if mode == "drumkit":
                outs = run_first = run_sep(MODELS["stems"][0], j["src"], raw)
                drums = [p for p in outs if stem_label(p) == "drums"][0]
                outs = outs + run_sep(MODELS["drumkit"][0], drums, raw)
            else:
                run_first = None
                outs = run_sep(MODELS[mode][0], j["src"], raw)
            stems = {}
            first = len(outs) if mode != "drumkit" else len(run_first)
            for i, p in enumerate(outs):
                name = stem_label(p)
                if mode == "drumkit" and i < first and name == "drums":
                    name = "drums_full"
                if mode == "vocals" and name == "other":
                    name = "instrumental"
                dst = d / f"{name}.wav"
                to_rytm(p, dst)
                stems[name] = dst.name
            j["stems"] = stems
            j["status"] = "done"; save(j)
            # The Rytm-ready stems and the original upload stay; the big intermediates go.
            shutil.rmtree(raw, ignore_errors=True)
            wav.unlink(missing_ok=True)
        except Exception as e:
            traceback.print_exc()
            j["status"] = "error"; j["error"] = str(e)
            try:
                save(j)
            except Exception:
                traceback.print_exc()  # disk full etc.: the job stays failed in memory; keep serving
        finally:
            q.task_done()


threading.Thread(target=worker, daemon=True).start()


@app.get("/models")
def models(x_token: str = Header("")):
    check(x_token)
    return {k: v[1] for k, v in MODELS.items()}


@app.post("/jobs")
async def create(file: UploadFile = File(...), model: str = Form("stems"), x_token: str = Header("")):
    check(x_token)
    if model not in MODELS:
        raise HTTPException(400, "unknown model" if model not in CANDIDATES else "this computer can't run that model")
    jid = uuid.uuid4().hex[:12]
    d = ROOT / jid; d.mkdir()
    ext = Path(file.filename or "in.wav").suffix or ".wav"
    src = d / f"input{ext}"
    with open(src, "wb") as f:
        shutil.copyfileobj(file.file, f)
    jobs[jid] = {"id": jid, "model": model, "status": "queued", "src": str(src),
                 "name": Path(file.filename or "track").stem, "stems": {}}
    save(jobs[jid])
    q.put(jid)
    return {"id": jid}


@app.get("/jobs/{jid}")
def status(jid: str, x_token: str = Header("")):
    check(x_token)
    j = jobs.get(jid) or HTTPException(404)
    if isinstance(j, HTTPException):
        raise j
    return {k: v for k, v in j.items() if k != "src"} | {"queue": q.qsize()}


@app.get("/jobs/{jid}/stems/{name}")
def stem(jid: str, name: str, x_token: str = Header("")):
    check(x_token)
    j = jobs.get(jid)
    if not j or name not in j["stems"]:
        raise HTTPException(404)
    return FileResponse(ROOT / jid / j["stems"][name], media_type="audio/wav")
