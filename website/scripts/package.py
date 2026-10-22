"""Package only the website's explicit public directory for the existing server."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

repo = Path(__file__).resolve().parents[2]
public = repo / "website" / "public"
destination = repo / "output" / "deployments" / "website" / "baseballmaster-home" / "baseballmaster-home.zip"
destination.parent.mkdir(parents=True, exist_ok=True)
files = sorted(path for path in public.rglob("*") if path.is_file() and not any(part.startswith(".") for part in path.relative_to(public).parts))
with ZipFile(destination, "w", compression=ZIP_DEFLATED) as archive:
    for path in files:
        archive.write(path, path.relative_to(public))
print(f"Packaged {len(files)} public files: {destination}")
