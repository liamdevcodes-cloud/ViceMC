import json
import base64
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")
OUT_DIR = Path(r"C:\Dev\vicemc\modules\guns\src\main\resources\viewmodel\ak74")
OUT_DIR.mkdir(parents=True, exist_ok=True)

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

# Map element UUID -> element data
elements_by_uuid = {e.get('uuid'): e for e in bb.get('elements', [])}

# AK74 groups (from tree analysis)
AK74_ROOT_UUID = "7e79967f-9493-9fc9-eb21-78c74e01c40f"
AK74_CHILD_UUIDS = [
    "9c79fdda-4d1d-2122-768d-7f8d6a25d723",  # Stock + Recoil Pad
    "08e5a807-cd4c-34de-34c5-77679e9d8ed8",  # Iron Sight + Picitinny + Mainbody + Pistol grip
    "b4ed5f7c-0f1a-d6ba-96cb-a5f473318d22",  # Stock
    "b6751dc4-6eda-b799-6f65-5bdc80c15fd2",  # Trigger
    "6ce9e950-ec83-7201-5c73-f7de9556e94c",  # Mainbody
]
MAG_UUID = "ef7aa913-2baf-acd2-6521-57902f618794"  # mag

ALL_AK74_GROUP_UUIDS = [AK74_ROOT_UUID] + AK74_CHILD_UUIDS + [MAG_UUID]

# Collect all AK74 elements (under root + mag)
def collect_elements_under(root_uuid):
    uuids = []
    # Find the group in outliner
    def find_group(nodes, target):
        for node in nodes:
            if isinstance(node, str):
                continue
            if node.get('uuid') == target:
                return node
            found = find_group(node.get('children', []), target)
            if found:
                return found
        return None
    
    group = find_group(bb.get('outliner', []), root_uuid)
    if not group:
        return uuids
    
    def collect(node):
        if isinstance(node, str):
            e = elements_by_uuid.get(node)
            if e:
                uuids.append(node)
        else:
            for child in node.get('children', []):
                collect(child)
    
    collect(group)
    return uuids

ak74_element_uuids = collect_elements_under(AK74_ROOT_UUID)
mag_element_uuids = collect_elements_under(MAG_UUID)
print(f"AK74 elements: {len(ak74_element_uuids)}")
print(f"Mag elements: {len(mag_element_uuids)}")

# Build the vanilla model JSON for the main AK74 body
# We need to convert Blockbench elements to vanilla format
# Blockbench element: from, to, rotation, faces (with uv, texture)
# Vanilla element: from, to, rotation, faces, shade

def convert_element(bb_el):
    el = {}
    el["from"] = bb_el.get("from", [0,0,0])
    el["to"] = bb_el.get("to", [16,16,16])
    
    rot = bb_el.get("rotation")
    if rot:
        # Blockbench rotation: angle, axis, origin
        # Vanilla: x, y, z, origin (per-axis)
        # For simplicity, we'll use the rotation as-is if it's simple
        el["rotation"] = rot
    
    faces = {}
    for face_name, face_data in bb_el.get("faces", {}).items():
        tex = face_data.get("texture", 0)
        tex_str = f"#{tex}" if isinstance(tex, int) else tex
        faces[face_name] = {
            "uv": face_data.get("uv", [0,0,16,16]),
            "texture": tex_str,
            "cullface": face_data.get("cullface", "")
        }
    el["faces"] = faces
    return el

# The AK texture is at index 1 (the 64x64 one). We'll remap all to #0 in our model
# and use a single texture file.

# Build elements list for main AK74 body
ak74_elements = []
for uuid in ak74_element_uuids:
    e = elements_by_uuid.get(uuid)
    if e:
        vanilla_el = convert_element(e)
        # Remap texture indices to #0
        for face in vanilla_el["faces"].values():
            tex = face["texture"]
            if isinstance(tex, str) and tex.startswith("#"):
                face["texture"] = "#0"
        ak74_elements.append(vanilla_el)

# Also mag elements (for separate model)
mag_elements = []
for uuid in mag_element_uuids:
    e = elements_by_uuid.get(uuid)
    if e:
        vanilla_el = convert_element(e)
        for face in vanilla_el["faces"].values():
            tex = face["texture"]
            if isinstance(tex, str) and tex.startswith("#"):
                face["texture"] = "#0"
        mag_elements.append(vanilla_el)

# Model metadata
model = {
    "format_version": "1.21.11",
    "credit": "Generated from Blockbench",
    "texture_size": [64, 64],
    "textures": {
        "0": "ak74:item/ak74",
        "particle": "ak74:item/ak74"
    },
    "elements": ak74_elements
}

mag_model = {
    "format_version": "1.21.11",
    "credit": "Generated from Blockbench",
    "texture_size": [64, 64],
    "textures": {
        "0": "ak74:item/ak74_mag",
        "particle": "ak74:item/ak74_mag"
    },
    "elements": mag_elements
}

# Save models
import os
os.makedirs(OUT_DIR / "models" / "item", exist_ok=True)
with open(OUT_DIR / "models" / "item" / "ak74.json", 'w') as f:
    json.dump(model, f, indent=2)
with open(OUT_DIR / "models" / "item" / "ak74_mag.json", 'w') as f:
    json.dump(mag_model, f, indent=2)

print(f"Saved AK74 model: {len(ak74_elements)} elements")
print(f"Saved Mag model: {len(mag_elements)} elements")

# Now extract animation keyframes for the AK74 root group (7e79967f)
# Animation data: for each animation, get the keyframes for this bone
animations_data = {}
for anim in bb.get('animations', []):
    name = anim.get('name')
    animators = anim.get('animators', {})
    
    if name not in ['ak_idle', 'ak_ads', 'Shooting', 'Reloading']:
        continue
    
    bone_data = animators.get(AK74_ROOT_UUID)
    if bone_data:
        # Normalize keyframes: each keyframe has time, value, interpolation
        # Blockbench format: rotation/position/scale are arrays of [time, value..., interpolation?]
        keyframes = {
            "rotation": bone_data.get("rotation", []),
            "position": bone_data.get("position", []),
            "scale": bone_data.get("scale", [])
        }
        animations_data[name] = keyframes
        print(f"\n{name} keyframes for AK74 root:")
        for ktype, kfs in keyframes.items():
            print(f"  {ktype}: {len(kfs)} keyframes")
            for kf in kfs[:3]:
                print(f"    {kf}")

# Also get mag animation (for reload)
mag_animations = {}
for anim in bb.get('animations', []):
    name = anim.get('name')
    if name != 'Reloading':
        continue
    animators = anim.get('animators', {})
    bone_data = animators.get(MAG_UUID)
    if bone_data:
        mag_animations[name] = {
            "rotation": bone_data.get("rotation", []),
            "position": bone_data.get("position", []),
            "scale": bone_data.get("scale", [])
        }
        print(f"\nMag Reloading keyframes:")
        for ktype, kfs in mag_animations[name].items():
            print(f"  {ktype}: {len(kfs)} keyframes")

# Save animation data
anim_output = {
    "gun_root": animations_data,
    "mag": mag_animations
}
with open(OUT_DIR / "animations.json", 'w') as f:
    json.dump(anim_output, f, indent=2)

print("\nAnimation data saved.")

# Also save the bone hierarchy info for reference
hierarchy = {
    "gun_root": AK74_ROOT_UUID,
    "mag": MAG_UUID,
    "child_groups": AK74_CHILD_UUIDS,
    "element_counts": {
        "gun_body": len(ak74_element_uuids),
        "mag": len(mag_element_uuids)
    }
}
with open(OUT_DIR / "hierarchy.json", 'w') as f:
    json.dump(hierarchy, f, indent=2)

print("Done.")