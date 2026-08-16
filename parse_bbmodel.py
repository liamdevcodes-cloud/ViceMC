import json
import base64
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")
OUT_DIR = Path(r"C:\Dev\vicemc\modules\guns\src\main\resources\viewmodel\ak74")
OUT_DIR.mkdir(parents=True, exist_ok=True)

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

print(f"Model: {bb.get('name')}")
print(f"Resolution: {bb.get('resolution')}")
print(f"Elements: {len(bb.get('elements', []))}")
print(f"Textures: {len(bb.get('textures', []))}")
print(f"Animations: {len(bb.get('animations', []))}")

# Map element UUID -> element data
elements_by_uuid = {e.get('uuid'): e for e in bb.get('elements', [])}

# Build outliner tree
def build_tree(nodes, depth=0):
    result = []
    for node in nodes:
        if isinstance(node, str):
            # UUID reference to element
            e = elements_by_uuid.get(node)
            if e:
                result.append(('element', e.get('name'), e.get('uuid'), node))
        else:
            # Group node
            uuid = node.get('uuid')
            children = node.get('children', [])
            group_elements = build_tree(children, depth+1)
            result.append(('group', node.get('name', f'group_{uuid[:8]}'), uuid, group_elements))
    return result

roots = bb.get('outliner', [])
tree = build_tree(roots)

# Print tree with AK74/Glock/Humanoid classification
def print_tree(nodes, indent=0):
    for typ, name, uuid_or_el, children in nodes:
        prefix = "  " * indent
        if typ == 'element':
            print(f"{prefix}[E] {name} (uuid={uuid_or_el[:8]})")
        else:
            print(f"{prefix}[G] {name} (uuid={uuid_or_el[:8]}, children={len(children)})")
            print_tree(children, indent+1)

print("\n=== FULL OUTLINER TREE ===")
print_tree(tree)

# Identify which bones are AK74 vs Glock vs Humanoid
# AK74 elements: Stock, Recoil Pad, Mainbody, Pistol grip, Trigger, Picitinny rail, Iron Sight, mag
# Glock elements: "cube" (repeated)
# Humanoid: head, headwear, torso, jacket, right_arm, left_arm, right_leg, left_leg

def collect_element_uuids(nodes, target_names):
    uuids = []
    for typ, name, uuid_or_el, children in nodes:
        if typ == 'element':
            if any(tn in name for tn in target_names):
                uuids.append(uuid_or_el)
        else:
            uuids.extend(collect_element_uuids(children, target_names))
    return uuids

ak74_names = ['Stock', 'Recoil Pad', 'Mainbody', 'Pistol grip', 'Trigger', 'Picitinny rail', 'Iron Sight', 'mag']
glock_names = ['cube']
humanoid_names = ['head', 'headwear', 'torso', 'jacket', 'right_arm', 'left_arm', 'right_leg', 'left_leg']

ak74_uuids = collect_element_uuids(tree, ak74_names)
glock_uuids = collect_element_uuids(tree, glock_names)
humanoid_uuids = collect_element_uuids(tree, humanoid_names)

print(f"\nAK74 element UUIDs ({len(ak74_uuids)}): {ak74_uuids}")
print(f"Glock element UUIDs ({len(glock_uuids)}): {glock_uuids}")
print(f"Humanoid element UUIDs ({len(humanoid_uuids)}): {humanoid_uuids}")

# Now find bone groups for AK74 - we need the GROUP hierarchy, not just elements
# Animations are keyed by bone UUID (group UUID), not element UUID
# Let's find which groups contain AK74 elements

def find_groups_with_elements(nodes, target_uuids):
    groups = []
    for typ, name, uuid, children in nodes:
        if typ == 'group':
            contained = collect_element_uuids([(typ, name, uuid, children)], target_uuids)
            if contained:
                groups.append((name, uuid, contained, children))
            groups.extend(find_groups_with_elements(children, target_uuids))
    return groups

ak74_groups = find_groups_with_elements(tree, set(ak74_uuids))
print(f"\n=== AK74 GROUPS ({len(ak74_groups)}) ===")
for name, uuid, elements, children in ak74_groups:
    print(f"  📁 {name} (uuid={uuid[:8]}) -> {len(elements)} elements")

# Now parse animations
print("\n=== ANIMATIONS ===")
for anim in bb.get('animations', []):
    name = anim.get('name')
    animators = anim.get('animators', {})
    # animators keys are bone UUIDs (group UUIDs), values are dict with 'rotation', 'position', 'scale' arrays of keyframes
    bone_channels = list(animators.keys())
    print(f"  {name}: {len(bone_channels)} bones")
    if name in ['ak_idle', 'ak_ads', 'Shooting', 'Reloading']:
        for bone_uuid in bone_channels:
            if bone_uuid in [g[1] for g in ak74_groups]:
                ch = animators[bone_uuid]
                print(f"    Bone {bone_uuid[:8]}: rot={len(ch.get('rotation', []))} pos={len(ch.get('position', []))} scale={len(ch.get('scale', []))}")

# Extract texture (the 64x64 one, not the steve skin)
print("\n=== TEXTURES ===")
for i, tex in enumerate(bb.get('textures', [])):
    print(f"  {i}: name={tex.get('name')}, source_len={len(tex.get('source', ''))}")

# Find the AK texture (64x64, not 'steve')
ak_tex = None
for tex in bb.get('textures', []):
    if tex.get('name') == 'texture':
        src = tex.get('source', '')
        if src.startswith('data:image/png;base64,'):
            b64 = src.split(',', 1)[1]
            ak_tex = base64.b64decode(b64)
            print(f"  Found AK texture: {len(ak_tex)} bytes")
            break

if ak_tex:
    tex_path = OUT_DIR / 'ak74.png'
    tex_path.write_bytes(ak_tex)
    print(f"  Saved to {tex_path}")

# Save full tree for reference
import pprint
with open(OUT_DIR / 'tree.json', 'w') as f:
    json.dump(tree, f, indent=2)

print("\nDone. Tree saved.")