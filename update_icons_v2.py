
import os
from PIL import Image

# Paths
input_path = r'C:\Users\sungh\.gemini\antigravity\brain\8312617e-5c6d-40d4-ac72-d046a730b67b\media__1776705451242.jpg'
res_dir = r'c:\Users\sungh\AndroidStudioProjects\baedalin\app\src\main\res'

densities = {
    'mdpi': (48, 108),
    'hdpi': (72, 162),
    'xhdpi': (96, 216),
    'xxhdpi': (144, 324),
    'xxxhdpi': (192, 432)
}

def process_icons():
    img = Image.open(input_path)
    
    for density, (legacy_size, foreground_size) in densities.items():
        folder = os.path.join(res_dir, f'mipmap-{density}')
        if not os.path.exists(folder):
            os.makedirs(folder)
            
        # Legacy icons
        legacy_img = img.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        legacy_img.save(os.path.join(folder, 'ic_launcher.png'))
        legacy_img.save(os.path.join(folder, 'ic_launcher_round.png'))
        
        # Adaptive foreground
        foreground_img = Image.new('RGBA', (foreground_size, foreground_size), (0, 0, 0, 0))
        content_size = int(foreground_size * 0.8)
        scaled_img = img.resize((content_size, content_size), Image.Resampling.LANCZOS)
        
        offset = (foreground_size - content_size) // 2
        foreground_img.paste(scaled_img, (offset, offset))
        foreground_img.save(os.path.join(folder, 'ic_launcher_foreground.png'))

    print("Icons updated successfully with new image.")

if __name__ == "__main__":
    process_icons()
