import os, glob, re

rose_class = 'px-4 py-2 rounded-lg text-xs font-semibold bg-rose-50 hover:bg-rose-100 dark:bg-rose-500/10 dark:hover:bg-rose-500/20 border border-rose-200 dark:border-rose-500/30 text-rose-600 dark:text-rose-400 transition-colors'

files = [
    'frontend/src/components/ConnectionDialog.tsx',
    'frontend/src/components/ScheduleManagerView.tsx',
    'frontend/src/components/ScheduleMappingModal.tsx',
    'frontend/src/components/TableMappingModal.tsx',
    'frontend/src/components/TemplateManager.tsx',
    'frontend/src/components/ExcelMappingModal.tsx',
    'frontend/src/components/NotificationChannelsModal.tsx'
]

pattern = re.compile(r'(<button[^>]*?className=")([^"]*)("[^>]*?>\s*Cancel\s*</button>)', re.IGNORECASE)

def replacer(match):
    return match.group(1) + rose_class + match.group(3)

for file in files:
    with open(file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    new_content = pattern.sub(replacer, content)
    
    with open(file, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print(f'Updated {file}')
