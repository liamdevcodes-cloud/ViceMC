import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")
OUT_DIR = Path(r"C:\Dev\vicemc\modules\guns\src\main\resources\viewmodel\ak74")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

# AK74 groups
AK74_ROOT = "7e79967f-9493-9fc9-eb21-78c74e01c40f"
MAG_UUID = "ef7aa913-2baf-acd2-6521-57902f618794"
AK74_GROUPS = [AK74_ROOT, MAG_UUID]

ANIM_NAMES = ['ak_idle', 'ak_ads', 'Shooting', 'Reloading']

def parse_keyframes(animators, target_uuid):
    """Extract keyframes for a specific bone UUID from animators dict."""
    bone = animators.get(target_uuid)
    if not bone:
        return {"rotation": [], "position": [], "scale": []}
    
    kfs = bone.get("keyframes", [])
    result = {"rotation": [], "position": [], "scale": []}
    
    for kf in kfs:
        channel = kf.get("channel")
        time = kf.get("time", 0)
        interp = kf.get("interpolation", "linear")
        data_points = kf.get("data_points", [])
        
        if not data_points:
            continue
        
        # data_points is a list, usually with one entry per keyframe
        # Each has x, y, z as strings
        dp = data_points[0]
        x = float(dp.get("x", 0))
        y = float(dp.get("y", 0))
        z = float(dp.get("z", 0))
        
        if channel in result:
            result[channel].append({
                "time": time,
                "value": [x, y, z],
                "interpolation": interp
            })
    
    # Sort by time
    for ch in result:
        result[ch].sort(key=lambda k: k["time"])
    
    return result

# Extract for all 4 animations
animations_out = {}
for anim in bb.get('animations', []):
    name = anim.get('name')
    if name not in ANIM_NAMES:
        continue
    
    animators = anim.get('animators', {})
    animations_out[name] = {}
    
    for uuid in AK74_GROUPS:
        animations_out[name][uuid] = parse_keyframes(animators, uuid)
        # Print summary
        data = animations_out[name][uuid]
        total = sum(len(v) for v in data.values())
        if total > 0:
            print(f"{name} - {uuid[:8]}: rot={len(data['rotation'])} pos={len(data['position'])} scale={len(data['scale'])}")

# Save animation data
with open(OUT_DIR / "animations.json", 'w') as f:
    json.dump(animations_out, f, indent=2)

print("\nSaved animations.json")

# Also print the actual keyframe data for verification
print("\n=== Sample keyframes ===")
for name in ANIM_NAMES:
    if name in animations_out:
        for uuid in AK74_GROUPS:
            data = animations_out[name][uuid]
            if any(data.values()):
                print(f"\n{name} - {uuid[:8]}:")
                for ch in ['rotation', 'position', 'scale']:
                    if data[ch]:
                        print(f"  {ch}: {data[ch][:3]}...")

print("\nDone.")