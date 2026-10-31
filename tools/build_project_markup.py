"""Compile the native description adapter into an existing local app image, offline."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
app = Path(sys.argv[1]).resolve()
jdk = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else Path(shutil.which("javac")).resolve().parent.parent
suffix = ".exe" if os.name == "nt" else ""
classes = root / "build/tmp/project-markup-classes"
classes.mkdir(parents=True, exist_ok=True)
source = root / "src/main/java/ru/aw/launcher/nativeui/ProjectMarkup.java"
subprocess.run([str(jdk / f"bin/javac{suffix}"), "--release", "21", "-encoding", "UTF-8", "-cp", str(app / "app/*"), "-d", str(classes), str(source)], check=True)
subprocess.run([str(jdk / f"bin/jar{suffix}"), "--create", "--file", str(app / "app/AWLauncher-project-markup.jar"), "-C", str(classes), "ru/aw/launcher/nativeui/ProjectMarkup.class"], check=True)
print("Project description adapter compiled into the local app image")
