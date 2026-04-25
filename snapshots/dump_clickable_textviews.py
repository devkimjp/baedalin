import os
import json

snapshots_dir = r"c:\Users\sungh\AndroidStudioProjects\baedalin\snapshots"

def dump_textviews(node, file_path):
    results = []
    if node.get("class") == "TextView":
        results.append({
            "file": file_path,
            "text": node.get("text"),
            "clickable": node.get("clickable"),
            "bounds": node.get("bounds")
        })
    
    for child in node.get("children", []):
        results.extend(dump_textviews(child, file_path))
    
    return results

for filename in os.listdir(snapshots_dir):
    if filename.endswith(".json"):
        path = os.path.join(snapshots_dir, filename)
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
            textviews = dump_textviews(data.get("nodes", {}), filename)
            for tv in textviews:
                if tv["clickable"]:
                    print(f"FOUND CLICKABLE TEXTVIEW in {filename}:")
                    print(json.dumps(tv, indent=2, ensure_ascii=False))
