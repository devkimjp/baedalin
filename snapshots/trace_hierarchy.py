import os
import json

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"
filename = "ui_snapshot_20260425_232103.json"
target_text = "신규배달 1건을 수락해주세요"

def find_clickable_parent(node, target_text):
    # This is a bit tricky because we need to find if any child matches target_text
    # and then check if this node or any of its parents are clickable.
    
    def contains_text(n, t):
        if t in n.get("text", ""):
            return True
        for child in n.get("children", []):
            if contains_text(child, t):
                return True
        return False

    results = []
    if contains_text(node, target_text):
        results.append({
            "class": node.get("class"),
            "text": node.get("text"),
            "desc": node.get("desc"),
            "clickable": node.get("clickable"),
            "bounds": node.get("bounds")
        })
        
    for child in node.get("children", []):
        results.extend(find_clickable_parent(child, target_text))
    
    return results

path = os.path.join(snapshots_dir, filename)
with open(path, 'r', encoding='utf-8') as f:
    data = json.load(f)
    hierarchy = find_clickable_parent(data.get("nodes", {}), target_text)
    print(f"Hierarchy containing '{target_text}':")
    for item in hierarchy:
        if item["clickable"]:
            print(f"CLICKABLE: {item['class']} - {item['desc']} {item['bounds']}")
        else:
            print(f"not clickable: {item['class']} - {item['desc']}")
