import os
import json

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"
target_x, target_y = 517, 320

def find_clickable_at(node, x, y):
    results = []
    bounds = node.get("bounds", {})
    if bounds:
        left = bounds.get("left", 0)
        top = bounds.get("top", 0)
        right = bounds.get("right", 0)
        bottom = bounds.get("bottom", 0)
        
        if left <= x <= right and top <= y <= bottom and node.get("clickable") == True:
            results.append({
                "class": node.get("class"),
                "text": node.get("text"),
                "desc": node.get("desc"),
                "bounds": bounds
            })
    
    for child in node.get("children", []):
        results.extend(find_clickable_at(child, x, y))
    
    return results

all_results = []
for filename in os.listdir(snapshots_dir):
    if filename.endswith(".json"):
        path = os.path.join(snapshots_dir, filename)
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            found = find_clickable_at(data.get("nodes", {}), target_x, target_y)
            if found:
                all_results.append({
                    "file": filename,
                    "nodes": found
                })

print(json.dumps(all_results, indent=2, ensure_ascii=False))
