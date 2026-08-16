import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

for anim in bb.get('animations', []):
    if anim.get('name') == 'ak_idle':
        animators = anim.get('animators', {})
        for uuid, data in list(animators.items())[:3]:
            print(f"Bone: {uuid}")
            print(f"  Data keys: {list(data.keys())}")
            for k, v in data.items():
                print(f"  {k}: {type(v).__name__}, len={len(v) if isinstance(v, list) else 'N/A'}")
                if isinstance(v, list) and v:
                    print(f"    First item: {v[0]}")
                    print(f"    First item type: {type(v[0]).__name__}")
                    if isinstance(v[0], list):
                        print(f"    First item len: {len(v[0])}")
        break