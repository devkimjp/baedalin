import os
from PIL import Image

source_path = r'C:\Users\sungh\.gemini\antigravity\brain\444523bb-0070-4a0f-857c-7dcef93c6994\media__1776894260764.jpg'
res_dir = r'c:\Users\sungh\baedalin\app\src\main\res'

# Legacy icon sizes
legacy_sizes = {
    'mipmap-xxxhdpi': 192,
    'mipmap-xxhdpi': 144,
    'mipmap-xhdpi': 96,
    'mipmap-hdpi': 72,
    'mipmap-mdpi': 48
}

# Adaptive icon foreground sizes (108dp)
adaptive_sizes = {
    'mipmap-xxxhdpi': 432,
    'mipmap-xxhdpi': 324,
    'mipmap-xhdpi': 216,
    'mipmap-hdpi': 162,
    'mipmap-mdpi': 108
}

img = Image.open(source_path)

# Ensure it's square
width, height = img.size
size = min(width, height)
left = (width - size) / 2
top = (height - size) / 2
right = (width + size) / 2
bottom = (height + size) / 2
img = img.crop((left, top, right, bottom))

for folder, size in legacy_sizes.items():
    dest_path = os.path.join(res_dir, folder)
    if not os.path.exists(dest_path):
        os.makedirs(dest_path)
    
    # Save ic_launcher.png
    icon = img.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(os.path.join(dest_path, 'ic_launcher.png'))
    icon.save(os.path.join(dest_path, 'ic_launcher_round.png'))

for folder, size in adaptive_sizes.items():
    dest_path = os.path.join(res_dir, folder)
    if not os.path.exists(dest_path):
        os.makedirs(dest_path)
    
    # Save ic_launcher_foreground.png
    # For adaptive icon, we might want to add some padding so it's not cropped
    # The center 66% (72dp out of 108dp) is the safe zone.
    # So we resize the image to 72dp and paste it onto a 108dp transparent canvas.
    
    foreground_size = int(size * 0.72) # 72/108 = 0.666, but let's use 72% to be safe
    icon_content = img.resize((foreground_size, foreground_size), Image.Resampling.LANCZOS)
    
    # Create transparent background
    foreground = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    offset = (size - foreground_size) // 2
    foreground.paste(icon_content, (offset, offset))
    
    foreground.save(os.path.join(dest_path, 'ic_launcher_foreground.png'))

print("Icons replaced successfully.")
