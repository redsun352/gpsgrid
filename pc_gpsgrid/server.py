from flask import Flask, request, jsonify, send_from_directory, Response
from pathlib import Path
import json, threading, time, math, csv, io

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'survey.json'
app = Flask(__name__, static_folder='web', static_url_path='')
lock = threading.Lock()
state = {'points': [], 'closed': False, 'grid': [], 'grid_spacing_m': 1.0, 'grid_angle_deg': 0.0, 'updated': 0}
clients = {}
CLIENT_TIMEOUT = 30

if DATA.exists():
    try:
        loaded = json.loads(DATA.read_text(encoding='utf-8'))
        state.update(loaded)
    except Exception:
        pass

def save():
    state['updated'] = int(time.time() * 1000)
    DATA.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')

def touch_client():
    client_id = request.headers.get('X-GPSGrid-Client', '').strip() or request.remote_addr or 'unknown'
    ip = request.headers.get('X-Tailscale-IP') or request.headers.get('CF-Connecting-IP') or request.headers.get('X-Forwarded-For', '').split(',')[0].strip() or request.remote_addr or 'unknown'
    ua = request.headers.get('User-Agent', 'unknown')
    device = request.headers.get('X-GPSGrid-Device', '')
    now = time.time()
    with lock:
        clients[client_id] = {'id': client_id, 'ip': ip, 'user_agent': ua, 'device_name': device, 'last_seen': now}
    return client_id

def active_clients():
    cutoff = time.time() - CLIENT_TIMEOUT
    with lock:
        active = [dict(c, online=True) for c in clients.values() if c['last_seen'] >= cutoff]
    active.sort(key=lambda x: x['last_seen'], reverse=True)
    return active

def xy(points):
    if not points: return [], 0, 0, 0
    lat0 = math.radians(sum(p['latitude'] for p in points) / len(points))
    r = 6371008.8
    out = [(math.radians(p['longitude']) * r * math.cos(lat0), math.radians(p['latitude']) * r) for p in points]
    return out, lat0, r, math.cos(lat0)

def polygon_area(points):
    if len(points) < 3: return 0.0
    q, _, _, _ = xy(points)
    return abs(sum(q[i][0] * q[(i+1)%len(q)][1] - q[(i+1)%len(q)][0] * q[i][1] for i in range(len(q)))) / 2

def distance(a,b):
    r=6371008.8; p1=math.radians(a['latitude']); p2=math.radians(b['latitude']); dp=math.radians(b['latitude']-a['latitude']); dl=math.radians(b['longitude']-a['longitude'])
    h=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*r*math.asin(math.sqrt(h))

def polygon_perimeter(points):
    return sum(distance(points[i-1], points[i]) for i in range(len(points))) if len(points)>1 else 0.0

def point_in_poly(x,y,poly):
    inside=False
    j=len(poly)-1
    for i in range(len(poly)):
        xi,yi=poly[i]; xj,yj=poly[j]
        if ((yi>y)!=(yj>y)) and x < (xj-xi)*(y-yi)/(yj-yi+1e-12)+xi: inside=not inside
        j=i
    return inside

def generate_grid(points, spacing, angle):
    if len(points)<3 or spacing<=0: return []
    q,_,_,_=xy(points)
    a=math.radians(angle); ca=math.cos(a); sa=math.sin(a)
    rot=[(x*ca-y*sa,x*sa+y*ca) for x,y in q]
    minx=min(x for x,y in rot); maxx=max(x for x,y in rot); miny=min(y for x,y in rot); maxy=max(y for x,y in rot)
    rows=[]; y=miny
    while y<=maxy+1e-6:
        x=minx; row=[]
        while x<=maxx+1e-6:
            if point_in_poly(x,y,rot):
                ox=x*ca+y*sa; oy=-x*sa+y*ca
                lat0=math.radians(sum(p['latitude'] for p in points)/len(points)); r=6371008.8
                lat=math.degrees(oy/r); lon=math.degrees(ox/(r*math.cos(lat0)))
                row.append({'latitude':lat,'longitude':lon})
            x+=spacing
        if row: rows.append(row)
        y+=spacing
    return [p for row in rows for p in row]

@app.before_request
def register_request():
    if request.path.startswith('/api/'): touch_client()

@app.get('/')
def index(): return send_from_directory(app.static_folder, 'index.html')

@app.get('/api/state')
def get_state():
    with lock:
        s=dict(state); s['area_m2']=polygon_area(state['points']) if state['closed'] else 0.0; s['perimeter_m']=polygon_perimeter(state['points'])
        return jsonify(s)

@app.get('/api/clients')
def get_clients():
    now=time.time(); result=[]
    for c in active_clients():
        item=dict(c); item['last_seen_seconds']=round(max(0,now-c['last_seen']),1); result.append(item)
    return jsonify({'count':len(result),'clients':result})

@app.get('/api/heartbeat')
def heartbeat(): return jsonify(ok=True, client=touch_client())
@app.post('/api/heartbeat')
def heartbeat_post(): return jsonify(ok=True, client=touch_client())

@app.post('/api/point')
def add_point():
    p=request.get_json(force=True)
    if any(k not in p for k in ('id','latitude','longitude')): return jsonify(error='missing point fields'),400
    with lock:
        p=dict(p); p['id']=int(p['id']); p['latitude']=float(p['latitude']); p['longitude']=float(p['longitude']); p['accuracy']=float(p.get('accuracy',0)); p['altitude']=float(p.get('altitude',0)); p['time']=int(p.get('time',time.time()*1000))
        state['points']=[x for x in state['points'] if x.get('id')!=p['id']]; state['points'].append(p); state['points'].sort(key=lambda x:x['id']); state['closed']=False; state['grid']=[]; save()
        return jsonify(ok=True,point=p)

@app.delete('/api/point/<int:point_id>')
def delete_point(point_id):
    with lock:
        state['points']=[p for p in state['points'] if p.get('id')!=point_id]
        for i,p in enumerate(state['points'],1): p['id']=i
        state['closed']=False; state['grid']=[]; save(); return jsonify(ok=True,points=state['points'])

@app.post('/api/polygon/open')
def open_polygon():
    with lock: state['closed']=False; save(); return jsonify(ok=True)

@app.post('/api/polygon/close')
def close_polygon():
    with lock:
        if len(state['points'])<3: return jsonify(error='at least 3 points required'),400
        state['closed']=True; save(); return jsonify(ok=True)

@app.post('/api/grid')
def make_grid():
    body=request.get_json(force=True); spacing=float(body.get('spacing',1.0)); angle=float(body.get('angle',0.0))
    with lock:
        if not state['closed'] or len(state['points'])<3: return jsonify(error='closed polygon required'),400
        state['grid_spacing_m']=spacing; state['grid_angle_deg']=angle; state['grid']=generate_grid(state['points'],spacing,angle); save()
        return jsonify(ok=True,count=len(state['grid']),grid=state['grid'])

@app.get('/api/export/csv')
def export_csv():
    out=io.StringIO(); w=csv.writer(out); w.writerow(['id','latitude','longitude','altitude','accuracy','time'])
    for p in state['points']: w.writerow([p.get('id'),p.get('latitude'),p.get('longitude'),p.get('altitude',0),p.get('accuracy',0),p.get('time',0)])
    return Response(out.getvalue(),mimetype='text/csv',headers={'Content-Disposition':'attachment; filename=gpsgrid_points.csv'})

@app.get('/api/export/xyz')
def export_xyz():
    text=''.join(f"{p['longitude']:.8f} {p['latitude']:.8f} {p.get('altitude',0):.3f}\n" for p in state['points'])
    return Response(text,mimetype='text/plain',headers={'Content-Disposition':'attachment; filename=gpsgrid_points.xyz'})

@app.get('/api/export/dat')
def export_dat():
    grid=state.get('grid',[])
    text='GPSGrid DAT\n' + ''.join(f"{p['longitude']:.8f} {p['latitude']:.8f}\n" for p in grid)
    return Response(text,mimetype='text/plain',headers={'Content-Disposition':'attachment; filename=gpsgrid_grid.dat'})

@app.post('/api/reset')
def reset():
    with lock: state.clear(); state.update(points=[],closed=False,grid=[],grid_spacing_m=1.0,grid_angle_deg=0.0,updated=0); save(); return jsonify(ok=True)

if __name__=='__main__': app.run(host='0.0.0.0',port=8765,debug=False)
