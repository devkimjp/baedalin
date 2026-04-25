import os
import json

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"
target_x, target_y = 517, 320

def find_node_at(node, x, y):
    results = []
    bounds = node.get("bounds", {})
    if bounds:
        left = bounds.get("left", 0)
        top = bounds.get("top", 0)
        right = bounds.get("right", 0)
        bottom = bounds.get("bottom", 0)
        
        if left <= x <= right and top <= y <= bottom:
            results.append({
                "class": node.get("class"),
                "text": node.get("text"),
                "desc": node.get("desc"),
                "clickable": node.get("clickable"),
                "bounds": bounds
            })
    
    for child in node.get("children", []):
        results.extend(find_node_at(child, x, y))
    
    return results

for filename in ["ui_snapshot_20260425_232052.json"]:
    path = os.path.join(snapshots_dir, filename)
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
        nodes = find_node_at(data.get("nodes", {}), target_x, target_y)
        print(f"Nodes at ({target_x}, {target_y}) in {filename}:")
        print(json.dumps(nodes, indent=2, ensure_ascii=False))
