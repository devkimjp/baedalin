import os
from PIL import Image

def fix_png(path):
    try:
        with Image.open(path) as img:
            img = img.convert("RGBA")
            img.save(path, "PNG")
            print(f"Fixed: {path}")
    except Exception as e:
        print(f"Failed to fix {path}: {e}")

drawable_dir = r'c:\Users\sungh\AndroidStudioProjects\baedalin\app\src\main\res\drawable'
for filename in os.listdir(drawable_dir):
    if filename.endswith(".png"):
        fix_png(os.path.join(drawable_dir, filename))
