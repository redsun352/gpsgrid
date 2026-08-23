from flask import Flask, request, jsonify, send_from_directory
from pathlib import Path
import json, threading, time

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'survey.json'
app = Flask(__name__, static_folder='web', static_url_path='')
lock = threading.Lock()
state = {'points': [], 'closed': False}
clients = {}
CLIENT_TIMEOUT = 30

if DATA.exists():
    try: state = json.loads(DATA.read_text(encoding='utf-8'))
    except Exception: pass

def save():
    DATA.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')

def touch_client():
    client_id = request.headers.get('X-GPSGrid-Client', '').strip() or request.remote_addr or 'unknown'
    ip = request.headers.get('CF-Connecting-IP') or request.headers.get('X-Forwarded-For', '').split(',')[0].strip() or request.remote_addr or 'unknown'
    ua = request.headers.get('User-Agent', 'unknown')
    now = time.time()
    with lock:
        clients[client_id] = {'id': client_id, 'ip': ip, 'user_agent': ua, 'last_seen': now}
    return client_id

def active_clients():
    cutoff = time.time() - CLIENT_TIMEOUT
    with lock:
        active = [dict(c, online=(c['last_seen'] >= cutoff)) for c in clients.values() if c['last_seen'] >= cutoff]
    active.sort(key=lambda x: x['last_seen'], reverse=True)
    return active

@app.before_request
def register_request():
    if request.path.startswith('/api/'):
        touch_client()

@app.get('/')
def index(): return send_from_directory(app.static_folder, 'index.html')

@app.get('/api/state')
def get_state():
    with lock: return jsonify(state)

@app.get('/api/clients')
def get_clients():
    now = time.time()
    result = []
    for c in active_clients():
        item = dict(c)
        item['last_seen_seconds'] = round(max(0, now - c['last_seen']), 1)
        result.append(item)
    return jsonify({'count': len(result), 'clients': result})

@app.get('/api/heartbeat')
def heartbeat():
    return jsonify(ok=True, client=touch_client())

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
