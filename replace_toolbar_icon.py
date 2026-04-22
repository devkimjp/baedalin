import os
from PIL import Image

# 설정
brain_dir = r'C:\Users\sungh\.gemini\antigravity\brain\444523bb-0070-4a0f-857c-7dcef93c6994'
dest_dir = r'c:\Users\sungh\baedalin\app\src\main\res\drawable'

targets = [
    {
        'name': 'ic_toolbar_coupang',
        'source': os.path.join(brain_dir, 'media__1776893678613.png')
    },
    {
        'name': 'ic_toolbar_baemin',
        'source': os.path.join(brain_dir, 'media__1776894555586.jpg')
    }
]

for target in targets:
    name = target['name']
    source_path = target['source']
    
    dest_file_xml = os.path.join(dest_dir, f'{name}.xml')
    dest_file_png = os.path.join(dest_dir, f'{name}.png')

    # 1. XML 파일 삭제 (리소스 충돌 방지)
    if os.path.exists(dest_file_xml):
        os.remove(dest_file_xml)
        print(f"Removed {dest_file_xml}")

    # 2. 이미지 처리 및 저장
    img = Image.open(source_path)

    # 툴바 아이콘 크기(100x100)에 맞게 비율 유지하며 리사이징
    img.thumbnail((100, 100), Image.Resampling.LANCZOS)

    # 투명한 100x100 캔버스 중앙에 배치
    new_img = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    offset = ((100 - img.width) // 2, (100 - img.height) // 2)
    new_img.paste(img, offset)

    new_img.save(dest_file_png)
    print(f"Saved new toolbar icon to {dest_file_png}")

print("Toolbar icons replacement completed.")
