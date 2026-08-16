import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

for anim in bb.get('animations', []):
    if anim.get('name') == 'ak_idle':
        animators = anim.get('animators', {})
        uuid = list(animators.keys())[0]
        data = animators[uuid]
        print(json.dumps(data, indent=2))
        break