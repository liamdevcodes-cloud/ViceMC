import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

# Check all animator keys for ak_idle
for anim in bb.get('animations', []):
    if anim.get('name') == 'ak_idle':
        animators = anim.get('animators', {})
        print(f"Total bones in ak_idle: {len(animators)}")
        for uuid, data in animators.items():
            rot = len(data.get("rotation", []))
            pos = len(data.get("position", []))
            scale = len(data.get("scale", []))
            if rot or pos or scale:
                print(f"  {uuid}: rot={rot} pos={pos} scale={scale}")
                for ktype, kfs in data.items():
                    if kfs:
                        print(f"    {ktype}[0]: {kfs[0]}")
        break