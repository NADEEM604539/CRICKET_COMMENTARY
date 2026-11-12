"""
Scoreboard watcher (external, no game-process access).

Takes screenshots over adb, OCRs the bottom scoreboard strip with Windows OCR,
and turns changes in (runs, wickets, over.ball) into per-ball events.

Usage:  python scoreboard_watcher.py [seconds]
Output: prints events and appends JSON lines to captures/screen_events.jsonl
"""
import asyncio, io, json, os, re, subprocess, sys, time
from PIL import Image
from winrt.windows.media.ocr import OcrEngine
from winrt.windows.graphics.imaging import BitmapDecoder
from winrt.windows.storage import StorageFile, FileAccessMode

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.abspath(os.path.join(HERE, "..", "..", "captures"))
os.makedirs(OUT_DIR, exist_ok=True)
STRIP_PNG = os.path.join(OUT_DIR, "_strip.png")
LOG = os.path.join(OUT_DIR, "screen_events.jsonl")

# bottom strip of a 1600x720 landscape screen (scoreboard sits around y=630..700)
STRIP = (0, 560, 1600, 720)

SCORE_RE = re.compile(r"\b([A-Z]{2,4})\s*(\d{1,3})\s*[-\u2013]\s*(\d{1,2})\b")
OVER_RE = re.compile(r"\b(\d{1,2})\.(\d)\s*\(\s*(\d{1,3})\s*\)")


def grab():
    raw = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    return Image.open(io.BytesIO(raw)).convert("RGB")


async def ocr_text(eng, img):
    strip = img.crop(STRIP)
    strip = strip.resize((strip.width * 2, strip.height * 2))
    strip.save(STRIP_PNG)
    f = await StorageFile.get_file_from_path_async(STRIP_PNG)
    st = await f.open_async(FileAccessMode.READ)
    dec = await BitmapDecoder.create_async(st)
    bmp = await dec.get_software_bitmap_async()
    res = await eng.recognize_async(bmp)
    return " | ".join(l.text for l in res.lines)


def parse(text):
    s, o = SCORE_RE.search(text), OVER_RE.search(text)
    if not s or not o:
        return None
    return {
        "team": s.group(1), "runs": int(s.group(2)), "wkts": int(s.group(3)),
        "overs": int(o.group(1)), "balls": int(o.group(2)),
        "legal_balls": int(o.group(1)) * 6 + int(o.group(2)),
    }


def classify(prev, cur):
    dr = cur["runs"] - prev["runs"]
    dw = cur["wkts"] - prev["wkts"]
    db = cur["legal_balls"] - prev["legal_balls"]
    if dr < 0 or dw < 0 or db < 0 or dw > 1 or db > 6:
        return None  # OCR glitch or innings change
    if dw == 1:
        return "WICKET" + (f"+{dr}" if dr else "")
    if db == 0 and dr > 0:
        return "EXTRA_NO_BALL_OR_WIDE"  # runs added, ball count did not advance
    if db == 0:
        return None
    names = {0: "DOT", 1: "SINGLE", 2: "DOUBLE", 3: "TRIPLE", 4: "FOUR", 6: "SIX"}
    ev = names.get(dr, f"RUNS_{dr}")
    if cur["balls"] == 0 and db >= 1:
        ev += "+OVER_COMPLETED"
    return ev


async def main(seconds):
    eng = OcrEngine.try_create_from_user_profile_languages()
    t_end = time.time() + seconds
    accepted, pending = None, None
    print(f"watching for {seconds}s ...")
    while time.time() < t_end:
        try:
            text = await ocr_text(eng, grab())
        except Exception as e:  # capture hiccup
            print("capture error:", e)
            continue
        st = parse(text)
        stamp = time.strftime("%H:%M:%S")
        if st is None:
            print(f"{stamp} (no scoreboard read) raw: {text[:110]}")
            continue
        key = (st["runs"], st["wkts"], st["legal_balls"])
        if accepted is None:
            accepted = st
            print(f"{stamp} baseline: {st['team']} {st['runs']}-{st['wkts']} {st['overs']}.{st['balls']}")
            continue
        akey = (accepted["runs"], accepted["wkts"], accepted["legal_balls"])
        if key == akey:
            pending = None
            continue
        # debounce: need the same changed reading twice in a row
        if pending is not None and pending[0] == key:
            ev = classify(accepted, st)
            rec = {"t": stamp, "event": ev, "from": akey, "to": key, "raw": text[:160]}
            print(f"{stamp} EVENT {ev}  {akey} -> {key}")
            with open(LOG, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(rec) + "\n")
            accepted, pending = st, None
        else:
            pending = (key, st)


if __name__ == "__main__":
    asyncio.run(main(int(sys.argv[1]) if len(sys.argv) > 1 else 60))
