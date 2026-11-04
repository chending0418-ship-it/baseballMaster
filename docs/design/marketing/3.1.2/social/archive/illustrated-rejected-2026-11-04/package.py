"""Package public social deliverables with UTF-8 ZIP filenames."""
import sys
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
folder = Path(sys.argv[1]).resolve()
with ZipFile(folder / '宣传图片与文案.zip', 'w', compression=ZIP_DEFLATED) as archive:
    for file in sorted((folder / 'images').glob('*')):
        if file.suffix in {'.png', '.jpg'}:
            archive.write(file, file.relative_to(folder))
    archive.write(folder / '文案与使用说明.md', '文案与使用说明.md')
