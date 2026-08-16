from flask import Flask, request, jsonify, send_from_directory
from pathlib import Path
import json, threading

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'survey.json'
app = Flask(__name__, static_folder='web', static_url_path='')
lock = threading.Lock()
state = {'points': [], 'closed': False}

if DATA.exists():
    try: state = json.loads(DATA.read_text(encoding='utf-8'))
    except Exception: pass

def save():
    DATA.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')

@app.get('/')
def index(): return send_from_directory(app.static_folder, 'index.html')

@app.get('/api/state')
def get_state():
    with lock: return jsonify(state)

@app.post('/api/point')
def add_point():
    p = request.get_json(force=True)
    required = ('id','latitude','longitude')
    if any(k not in p for k in required): return jsonify(error='missing point fields'), 400
    with lock:
        state['points'] = [x for x in state['points'] if x.get('id') != p.get('id')]
        state['points'].append(p); state['closed'] = False; save()
        return jsonify(ok=True, point=p)

@app.post('/api/polygon/close')
def close_polygon():
    with lock:
        if len(state['points']) < 3: return jsonify(error='at least 3 points required'), 400
        state['closed'] = True; save(); return jsonify(ok=True)

@app.post('/api/reset')
def reset():
    with lock:
        state.clear(); state.update(points=[], closed=False); save(); return jsonify(ok=True)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8765, debug=False)
