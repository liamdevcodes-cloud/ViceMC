import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    data = json.load(f)

# Search for any field containing 'keyframe' or 'time'
def find_keyframes(obj, path=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if 'keyframe' in k.lower() or k.lower() in ['time', 'times']:
                print(f"{path}.{k}: {type(v).__name__} = {v}")
            find_keyframes(v, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            find_keyframes(v, f"{path}[{i}]")

find_keyframes(data)