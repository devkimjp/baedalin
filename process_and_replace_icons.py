import os
from PIL import Image, ImageDraw

def make_transparent(img_path, output_path):
    img = Image.open(img_path).convert("RGBA")
    data = img.getdata()
    
    # 코너의 색상을 배경색으로 가정 (첫 번째 픽셀)
    bg_color = data[0]
    
    new_data = []
    for item in data:
        # 배경색과 유사하면 투명하게 (오차 범위 30)
        if abs(item[0] - bg_color[0]) < 40 and abs(item[1] - bg_color[1]) < 40 and abs(item[2] - bg_color[2]) < 40:
            new_data.append((0, 0, 0, 0))
        else:
            new_data.append(item)
    
    img.putdata(new_data)
    
    # 100x100 크기로 리사이징
    img.thumbnail((100, 100), Image.Resampling.LANCZOS)
    
    # 중앙 정렬
    final_img = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    offset = ((100 - img.width) // 2, (100 - img.height) // 2)
    final_img.paste(img, offset)
    
    final_img.save(output_path)
    print(f"Processed and saved: {output_path}")

# 설정
brain_dir = r'C:\Users\sungh\.gemini\antigravity\brain\444523bb-0070-4a0f-857c-7dcef93c6994'
dest_dir = r'c:\Users\sungh\baedalin\app\src\main\res\drawable'

# 배민 아이콘 처리
make_transparent(
    os.path.join(brain_dir, 'media__1776895842042.png'),
    os.path.join(dest_dir, 'ic_toolbar_baemin.png')
)

# 쿠팡 아이콘 처리
make_transparent(
    os.path.join(brain_dir, 'media__1776895852580.png'),
    os.path.join(dest_dir, 'ic_toolbar_coupang.png')
)

# XML 파일이 남아있다면 삭제 (충돌 방지)
for name in ['ic_toolbar_baemin.xml', 'ic_toolbar_coupang.xml']:
    xml_path = os.path.join(dest_dir, name)
    if os.path.exists(xml_path):
        os.remove(xml_path)
        print(f"Removed {xml_path}")

print("Toolbar icons replacement with transparency completed.")
