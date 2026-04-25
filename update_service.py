import os

file_path = 'app/src/main/java/kr/disys/baedalin/service/FloatingWidgetService.kt'

def try_read(path):
    for enc in ['utf-8', 'cp949', 'euc-kr']:
        try:
            with open(path, 'r', encoding=enc) as f:
                return f.read(), enc
        except:
            continue
    return open(path, 'r', encoding='utf-8', errors='ignore').read(), 'utf-8'

content, encoding = try_read(file_path)
print(f"Read file with {encoding} encoding.")

# 1. Variables
if 'private var btnSnapshotView: View? = null' not in content:
    content = content.replace('private var btnAutoView: View? = null', 
                              'private var btnAutoView: View? = null\n    private var btnSnapshotView: View? = null')

# 2. Visibility
if 'btnSnapshotView?.visibility = visibility' not in content:
    content = content.replace('btnAutoView?.visibility = visibility', 
                              'btnAutoView?.visibility = visibility\n        btnSnapshotView?.visibility = visibility')

# 3. onStartCommand
if 'ACTION_SNAPSHOT_COMPLETE' not in content:
    # Use a simpler anchor if ACTION_UPDATE_TRANSPARENCY fails
    if 'ACTION_UPDATE_TRANSPARENCY' in content:
         content = content.replace('ACTION_UPDATE_TRANSPARENCY -> {', 
                              '''"ACTION_SNAPSHOT_COMPLETE" -> {
                val success = intent.getBooleanExtra("success", false)
                val path = intent.getStringExtra("path")
                if (success) {
                    Toast.makeText(this, "스냅샷 저장 완료: $path", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "스냅샷 저장 실패", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_UPDATE_TRANSPARENCY -> {''')

# 4. showSettingsWidget (Icon creation)
if 'val btnSnapshot = createToolbarIcon' not in content:
    # Find btnAuto setup
    anchor = 'btnAutoView = btnAuto'
    if anchor in content:
        content = content.replace(anchor, anchor + '''

        val btnSnapshot = createToolbarIcon(R.drawable.ic_toolbar_snapshot) { _ -> 
            takeUISnapshot()
        }
        btnSnapshotView = btnSnapshot''')

# 5. Toolbar Layout
if 'toolbarContainer.addView(btnSnapshot)' not in content:
    if 'toolbarContainer.addView(btnAuto)' in content:
        content = content.replace('toolbarContainer.addView(btnAuto)', 
                              'toolbarContainer.addView(btnAuto)\n        toolbarContainer.addView(btnSnapshot)')

# 6. Helper function
if 'private fun takeUISnapshot()' not in content:
    last_brace_index = content.rfind('}')
    content = content[:last_brace_index] + '''
    private fun takeUISnapshot() {
        val intent = Intent(this, KeyMapperAccessibilityService::class.java).apply {
            action = "ACTION_UI_SNAPSHOT"
        }
        startService(intent)
        Toast.makeText(this, "현재 화면 분석 중...", Toast.LENGTH_SHORT).show()
    }
}
'''

with open(file_path, 'w', encoding=encoding) as f:
    f.write(content)

print("FloatingWidgetService.kt updated successfully.")
