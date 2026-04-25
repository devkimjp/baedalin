import os
import json
import sys

# Ensure UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"

def find_text(node, file_path, target_text):
    results = []
    text = node.get("text", "")
    desc = node.get("desc", "")
    if target_text in text or target_text in desc:
        results.append({
            "file": file_path,
            "class": node.get("class"),
            "text": text,
            "desc": desc,
            "clickable": node.get("clickable"),
            "bounds": node.get("bounds")
        })
    
    for child in node.get("children", []):
        results.extend(find_text(child, file_path, target_text))
    
    return results

search_term = "배차"
all_results = []
for filename in os.listdir(snapshots_dir):
    if filename.endswith(".json"):
        path = os.path.join(snapshots_dir, filename)
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            all_results.extend(find_text(data.get("nodes", {}), filename, search_term))

print(json.dumps(all_results, indent=2, ensure_ascii=False))
