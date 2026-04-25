import os
import json
import sys

# Ensure UTF-8 output on Windows
sys.stdout.reconfigure(encoding='utf-8')

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"

def find_clickable_with_text(node, file_path):
    results = []
    text = node.get("text", "")
    desc = node.get("desc", "")
    if node.get("clickable") == True and (text or desc):
        results.append({
            "file": file_path,
            "class": node.get("class"),
            "text": text,
            "desc": desc,
            "bounds": node.get("bounds")
        })
    
    for child in node.get("children", []):
        results.extend(find_clickable_with_text(child, file_path))
    
    return results

all_results = []
for filename in os.listdir(snapshots_dir):
    if filename.endswith(".json"):
        path = os.path.join(snapshots_dir, filename)
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                all_results.extend(find_clickable_with_text(data.get("nodes", {}), filename))
        except Exception as e:
            print(f"Error reading {filename}: {e}", file=sys.stderr)

print(json.dumps(all_results, indent=2, ensure_ascii=False))
