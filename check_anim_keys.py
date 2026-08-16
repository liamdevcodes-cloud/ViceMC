import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

for anim in bb.get('animations', []):
    if anim.get('name') == 'ak_idle':
        print("Animation keys:", list(anim.keys()))
        # Check if there's a 'keyframes' or 'tracks' field
        for k in ['keyframes', 'tracks', 'channels', 'bones']:
            if k in anim:
                print(f"  Found {k}: {type(anim[k]).__name__}")
        break