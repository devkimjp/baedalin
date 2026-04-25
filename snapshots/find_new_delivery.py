import os
import json
import sys

# Ensure UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"
search_term = "신규배달"

def find_text_in_node(node, file_path, target_text):
    results = []
    text = node.get("text", "")
    desc = node.get("desc", "")
    
    if target_text in text or target_text in desc:
        results.append({
            "file": file_path,
            "class": node.get("class"),
            "text": text,
            "desc": desc,
            "bounds": node.get("bounds"),
            "clickable": node.get("clickable")
        })
    
    for child in node.get("children", []):
        results.extend(find_text_in_node(child, file_path, target_text))
    
    return results

all_results = []
for filename in os.listdir(snapshots_dir):
    if filename.endswith(".json"):
        path = os.path.join(snapshots_dir, filename)
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                all_results.extend(find_text_in_node(data.get("nodes", {}), filename, search_term))
        except Exception as e:
            pass

# Group by unique text to avoid too much redundancy but show which files they are in
unique_results = {}
for res in all_results:
    key = (res["text"], res["desc"])
    if key not in unique_results:
        unique_results[key] = {
            "text": res["text"],
            "desc": res["desc"],
            "files": [res["file"]],
            "bounds": res["bounds"]
        }
    else:
        if res["file"] not in unique_results[key]["files"]:
            unique_results[key]["files"].append(res["file"])

print(json.dumps(list(unique_results.values()), indent=2, ensure_ascii=False))
