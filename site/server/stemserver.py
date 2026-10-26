"""Stem server for the Rytm Mobile Android app.

POST /jobs (multipart: file, model)      -> {"id": ...}
GET  /jobs                               -> every job on this server, newest first
GET  /jobs/{id}                          -> status, stems
GET  /jobs/{id}/stems/{name}             -> 48 kHz mono 16-bit WAV (Rytm-ready)
GET  /jobs/{id}/stems/{name}?format=flac -> full-quality stereo FLAC at the separator's rate
GET  /jobs/{id}/zip?format=wav|flac      -> every stem of the job in one zip; stems with no file for that
                                            format are left out and named in the X-Stems-Missing header
                                            (comma-separated) and in a MISSING.txt inside the zip
DELETE /jobs/{id}                        -> removes that job's folder (404 unknown, 409 while queued or running)
GET  /models                             -> the models offered
POST /jobs answers 507 when the disk holding STEM_DIR has less than STEM_MIN_FREE_MB (default 2048) free.
Auth: header X-Token must match env STEM_TOKEN. The server refuses to start without one.
"""
import os, uuid, threading, queue, subprocess, shutil, glob, re, traceback, hmac, json, time, tempfile, zipfile
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Header, Query
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

ROOT = Path(os.environ.get("STEM_DIR", Path.home() / "rytm-mobile" / "jobs"))
ROOT.mkdir(parents=True, exist_ok=True)
TOKEN = os.environ.get("STEM_TOKEN", "").strip()
if not TOKEN:
    raise SystemExit("STEM_TOKEN is empty, so anyone on the network could use this server. "
                     "Re-run the installer, or put a token in ~/.rytm-stems/token.")
MAX_UPLOAD = int(os.environ.get("STEM_MAX_UPLOAD_MB", "2048")) * 1024 * 1024
MAX_SECONDS = int(os.environ.get("STEM_MAX_SECONDS", "1800"))  # longest input that gets split
MODEL_DIR = os.environ.get("STEM_MODELS", str(ROOT.parent / "models"))
MIN_FREE = int(os.environ.get("STEM_MIN_FREE_MB", "2048")) * 1024 * 1024  # refuse new jobs below this


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
jobs_lock = threading.Lock()
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


def to_flac(src, dst):
    # Full-quality copy: stereo, the separator's own sample rate, lossless.
    subprocess.run([FFMPEG, "-v", "error", "-y", "-i", str(src), "-vn", "-ac", "2", "-c:a", "flac",
                    str(dst)], check=True, timeout=600)


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
            stems, flac = {}, {}
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
                # The full-quality copy is a bonus: if it can't be made, the Rytm stem still ships.
                fdst = d / f"{name}.flac"
                try:
                    to_flac(p, fdst)
                    flac[name] = fdst.name
                except Exception:
                    traceback.print_exc()
                    fdst.unlink(missing_ok=True)
            j["stems"] = stems
            j["flac"] = flac
            j["status"] = "done"; save(j)
            # The Rytm stems, the FLAC copies and the original upload stay; the big intermediates go.
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
    free = shutil.disk_usage(ROOT).free
    if free < MIN_FREE:
        raise HTTPException(507, f"The computer is almost out of disk space ({free / 2**30:.1f} GB free, "
                                 f"{MIN_FREE / 2**30:.1f} GB needed). Delete old splits in History, or free some space, then try again.")
    jid = uuid.uuid4().hex[:12]
    d = ROOT / jid; d.mkdir()
    ext = Path(file.filename or "in.wav").suffix or ".wav"
    src = d / f"input{ext}"
    with open(src, "wb") as f:
        shutil.copyfileobj(file.file, f)
    jobs[jid] = {"id": jid, "model": model, "status": "queued", "src": str(src),
                 "name": Path(file.filename or "track").stem, "stems": {}, "created": int(time.time())}
    save(jobs[jid])
    q.put(jid)
    return {"id": jid}


def created_at(j):
    """Unix seconds the job was made; jobs from before "created" was recorded use job.json's date."""
    c = j.get("created")
    if isinstance(c, (int, float)):
        return int(c)
    try:
        return int((ROOT / j["id"] / "job.json").stat().st_mtime)
    except OSError:
        return 0


@app.get("/jobs")
def list_jobs(x_token: str = Header("")):
    check(x_token)
    out = [{"id": j["id"], "name": j.get("name", "track"), "model": j.get("model", ""),
            "status": j.get("status", ""), "stems": list((j.get("stems") or {}).keys()),
            "created": created_at(j)}
           for j in list(jobs.values())]
    out.sort(key=lambda r: r["created"], reverse=True)
    return {"jobs": out}


@app.get("/jobs/{jid}")
def status(jid: str, x_token: str = Header("")):
    check(x_token)
    j = jobs.get(jid) or HTTPException(404)
    if isinstance(j, HTTPException):
        raise j
    return {k: v for k, v in j.items() if k != "src"} | {"queue": q.qsize()}


@app.delete("/jobs/{jid}")
def delete_job(jid: str, x_token: str = Header("")):
    """Removes one job's folder (stems, FLAC copies, the upload). A queued or running job is refused."""
    check(x_token)
    with jobs_lock:
        j = jobs.get(jid)
        if not j:
            raise HTTPException(404, "no such job")
        if j.get("status") in ("queued", "running"):
            raise HTTPException(409, "This split is still running. Delete it when it has finished.")
        d = (ROOT / jid).resolve()
        if d.parent != ROOT.resolve():
            raise HTTPException(404, "no such job")
        jobs.pop(jid, None)
    shutil.rmtree(d, ignore_errors=True)
    return {"deleted": jid}


OLD_JOB = "no full-quality copy for this older job"


def safe_name(s, fallback):
    """A file name a phone or computer will accept: letters (any language), digits, space, dot, dash, underscore."""
    s = re.sub(r"[^\w .-]+", "_", str(s or "")).strip(" ._")
    return s[:80] or fallback


def stem_file(jid, j, name, fmt):
    """The file for one stem, looked up only through job.json's maps (never a name from the URL)."""
    if fmt == "flac":
        if "flac" not in j:
            raise HTTPException(404, OLD_JOB)
        files = j["flac"]
    else:
        files = j.get("stems") or {}
    if name not in files:
        raise HTTPException(404)
    d = (ROOT / jid).resolve()
    p = (d / files[name]).resolve()
    if p.parent != d or not p.is_file():
        raise HTTPException(404)
    return p


def get_job(jid):
    j = jobs.get(jid)
    if not j:
        raise HTTPException(404)
    return j


def check_format(fmt):
    if fmt not in ("wav", "flac"):
        raise HTTPException(400, "format must be wav or flac")


@app.get("/jobs/{jid}/stems/{name}")
def stem(jid: str, name: str, format: str = Query("wav"), x_token: str = Header("")):
    check(x_token)
    check_format(format)
    j = get_job(jid)
    if name not in (j.get("stems") or {}):
        raise HTTPException(404)
    p = stem_file(jid, j, name, format)
    if format == "flac":
        return FileResponse(p, media_type="audio/flac",
                            filename=f"{safe_name(j.get('name'), 'track')}_{safe_name(name, 'stem')}.flac")
    return FileResponse(p, media_type="audio/wav")


@app.get("/jobs/{jid}/zip")
def stems_zip(jid: str, format: str = Query("wav"), x_token: str = Header("")):
    check(x_token)
    check_format(format)
    j = get_job(jid)
    names = list((j.get("stems") or {}).keys())
    if not names:
        raise HTTPException(404, "this job has no stems yet")
    if format == "flac" and "flac" not in j:
        raise HTTPException(404, OLD_JOB)
    track = safe_name(j.get("name"), "track")
    fd, tmp = tempfile.mkstemp(prefix="rytm-stems-", suffix=".zip")
    os.close(fd)
    try:
        used, missing = set(), []
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
            for name in names:
                try:
                    p = stem_file(jid, j, name, format)
                except HTTPException:
                    missing.append(name)  # a stem whose FLAC couldn't be made: the zip carries the rest
                    continue
                arc = f"{track}_{safe_name(name, 'stem')}.{format}"
                n = 2
                while arc in used:
                    arc = f"{track}_{safe_name(name, 'stem')}_{n}.{format}"; n += 1
                used.add(arc)
                z.write(p, arc)
            if missing:
                z.writestr("MISSING.txt", f"These stems have no {format.upper()} file on the computer, so they are not in this zip:\n"
                           + "\n".join(missing) + "\n")
        if not used:
            raise HTTPException(404, "no stem files left for this job")
    except BaseException:
        os.unlink(tmp)
        raise
    headers = {"X-Stems-Missing": ",".join(missing)} if missing else None
    return FileResponse(tmp, media_type="application/zip", filename=f"{track}_stems_{format}.zip",
                        headers=headers, background=BackgroundTask(os.unlink, tmp))
