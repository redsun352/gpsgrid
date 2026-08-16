from flask import Flask, request, jsonify, send_from_directory
from pathlib import Path
import json, math, time

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
app = Flask(__name__, static_folder="web", static_url_path="")


def load_state():
    p = DATA / "survey.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {"points": [], "closed": False, "updated": 0}


def save_state(state):
    state["updated"] = int(time.time() * 1000)
    (DATA / "survey.json").write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def polygon_area(points):
    if len(points) < 3:
        return 0.0
    r = 6371008.8
    lat0 = math.radians(sum(p["latitude"] for p in points) / len(points))
    xy = [(math.radians(p["longitude"]) * r * math.cos(lat0), math.radians(p["latitude"]) * r) for p in points]
    return abs(sum(xy[i][0] * xy[(i + 1) % len(xy)][1] - xy[(i + 1) % len(xy)][0] * xy[i][1] for i in range(len(xy)))) / 2


@app.get("/")
def index():
    return send_from_directory(app.static_folder, "index.html")


@app.get("/api/state")
def state():
    s = load_state()
    s["area_m2"] = polygon_area(s["points"]) if s["closed"] else 0
    return jsonify(s)


@app.post("/api/point")
def point():
    body = request.get_json(force=True)
    for k in ("latitude", "longitude"):
        if k not in body:
            return jsonify({"error": f"missing {k}"}), 400
    s = load_state()
    if s["closed"]:
        return jsonify({"error": "polygon is closed"}), 409
    p = {"id": len(s["points"]) + 1, "latitude": float(body["latitude"]), "longitude": float(body["longitude"]), "altitude": float(body.get("altitude", 0)), "accuracy": float(body.get("accuracy", 0)), "time": int(body.get("time", time.time() * 1000))}
    s["points"].append(p)
    save_state(s)
    return jsonify(p), 201


@app.post("/api/polygon/close")
def close_polygon():
    s = load_state()
    if len(s["points"]) < 3:
        return jsonify({"error": "at least 3 points required"}), 400
    s["closed"] = True
    save_state(s)
    return jsonify(s)


@app.post("/api/reset")
def reset():
    s = {"points": [], "closed": False, "updated": 0}
    save_state(s)
    return jsonify(s)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8765, debug=False)
