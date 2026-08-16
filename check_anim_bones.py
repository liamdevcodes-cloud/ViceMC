import json
from pathlib import Path

BB_PATH = Path(r"Z:\mc_server\Blueprints\animations.bbmodel")

with open(BB_PATH, 'r', encoding='utf-8') as f:
    bb = json.load(f)

AK74_CHILD_UUIDS = [
    "9c79fdda-4d1d-2122-768d-7f8d6a25d723",  # Stock + Recoil Pad
    "08e5a807-cd4c-34de-34c5-77679e9d8ed8",  # Iron Sight + Picitinny + Mainbody + Pistol grip
    "b4ed5f7c-0f1a-d6ba-96cb-a5f473318d22",  # Stock
    "b6751dc4-6eda-b799-6f65-5bdc80c15fd2",  # Trigger
    "6ce9e950-ec83-7201-5c73-f7de9556e94c",  # Mainbody
]
MAG_UUID = "ef7aa913-2baf-acd2-6521-57902f618794"

ALL_AK74 = AK74_CHILD_UUIDS + [MAG_UUID]

print("=== Keyframe counts per bone per animation ===")
for anim in bb.get('animations', []):
    name = anim.get('name')
    if name not in ['ak_idle', 'ak_ads', 'Shooting', 'Reloading']:
        continue
    animators = anim.get('animators', {})
    print(f"\n{name}:")
    for uuid in ALL_AK74:
        bone_data = animators.get(uuid)
        if bone_data:
            rot = len(bone_data.get("rotation", []))
            pos = len(bone_data.get("position", []))
            scale = len(bone_data.get("scale", []))
            if rot or pos or scale:
                print(f"  {uuid[:8]}: rot={rot} pos={pos} scale={scale}")
                # Print first keyframe as sample
                for ktype, kfs in bone_data.items():
                    if kfs:
                        print(f"    {ktype}[0]: {kfs[0]}")

# Also check the humanoid arm bones for reference
print("\n=== Humanoid right arm bones ===")
ARM_UUIDS = [
    "dd127bdf-79aa-657c-5de5-8b71995fe07a",  # root
    "be423c94-b7b8-575f-2a8a-12db04ffea78",  # jacket + torso
    "bc8ed4fd-47b1-e80f-23e7-072fddc7e59b",  # head + headwear
    "ed4f86aa-4c3d-8f03-4045-c275b02ca405",  # right_arm
    "d26bc08b-0780-6631-872a-b935b48c6b5e",  # right_arm child
    "49768684-61f6-7a27-b023-e648d03354bc",  # left_arm
    "2417c20d-b7db-1708-9d7b-61092c785a51",  # left_arm child
    "d19ac435-b933-7245-4673-81a0ba4de297",  # right_leg
    "84cd8a83-af88-d452-d175-c7101535b6b2",  # left_leg
]
for anim in bb.get('animations', []):
    name = anim.get('name')
    if name not in ['ak_idle', 'ak_ads', 'Shooting', 'Reloading']:
        continue
    animators = anim.get('animators', {})
    print(f"\n{name} arm bones:")
    for uuid in ARM_UUIDS:
        bone_data = animators.get(uuid)
        if bone_data:
            rot = len(bone_data.get("rotation", []))
            pos = len(bone_data.get("position", []))
            scale = len(bone_data.get("scale", []))
            if rot or pos or scale:
                print(f"  {uuid[:8]}: rot={rot} pos={pos} scale={scale}")