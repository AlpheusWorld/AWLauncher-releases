"""Exercise the installed Rust launcher with an isolated profile on Ubuntu."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time


def rpc(binary, env, op, timeout=90, **args):
    result = subprocess.run(
        [binary, "--native-task"], env=env, encoding="utf-8", capture_output=True,
        input=json.dumps(dict(op=op, **args)) + "\n", timeout=timeout, check=True,
    )
    events = [json.loads(line[7:]) for line in result.stdout.splitlines()
              if line.startswith("AW_RPC ")]
    assert events and events[-1]["type"] == "done", (op, events[-12:], result.stderr)
    return events[-1]["data"]


def exercise(binary, folder, start_minecraft):
    home = folder / "home"
    home.mkdir()
    env = dict(os.environ, HOME=str(home), AW_NATIVE_DATA_HOME=str(home), AW_NATIVE_ENGINE="0")
    root = home / ".aw"
    source = folder / "Prism" / "Test"
    game = source / ".minecraft"
    world = game / "saves" / "TestWorld"
    world.mkdir(parents=True)
    (source / "instance.cfg").write_text("name=Linux CI\ntotalTimePlayed=15\n")
    (source / "mmc-pack.json").write_text(json.dumps({
        "components": [{"uid": "net.minecraft", "version": "1.21.1"}],
    }))
    (world / "level.dat").write_bytes(b"fixture world")
    (game / "options.txt").write_text("fullscreen:false\n")
    (game / "launcher_accounts.json").write_text("test secret")
    assert rpc(binary, env, "ping")["protocol"] == 1
    rpc(binary, env, "importProfile", file=str(source))
    rpc(binary, env, "accountOffline", title="LinuxSmoke")
    state = rpc(binary, env, "launcherState")
    build, = state["builds"]
    assert state["accounts"][0]["name"] == "LinuxSmoke"
    assert build["importedMs"] == 15000
    key, dest = build["key"], Path(build["dir"])
    assert not (dest / "launcher_accounts.json").exists()
    rpc(binary, env, "saveOptions", target=key, options={"memoryMb": 1024, "favorite": True})
    rpc(binary, env, "snapshot", target=key)
    snapshot = rpc(binary, env, "history", target=key)[0]["id"]
    (dest / "options.txt").write_text("fullscreen:true\n")
    rpc(binary, env, "restore", target=key, snapshot=snapshot)
    assert (dest / "options.txt").read_text() == "fullscreen:false\n"
    assert (dest / "saves/TestWorld/level.dat").read_bytes() == b"fixture world"
    print("Installed executable: profile import, account, settings, snapshot and restore passed")

    # Keep unrelated Discord sessions untouched and disable auto-update network calls.
    settings = root / "launcher.json"
    settings.write_text(json.dumps({"discordPresence": False, "language": "EN"}))
    gui_log = folder / "gui.log"
    with gui_log.open("w+") as output:
        gui = subprocess.Popen([binary], env=dict(env, AW_SMOKE_READY="1", AW_ENGINE_OFFLINE="1"),
                               stdout=output, stderr=output)
        try:
            end = time.monotonic() + 45
            while time.monotonic() < end and gui.poll() is None:
                if "AW_SMOKE: launcher state returned to WebView" in gui_log.read_text(errors="replace"):
                    break
                time.sleep(0.25)
            else:
                raise AssertionError(gui_log.read_text(errors="replace"))
        finally:
            if gui.poll() is None:
                gui.terminate()
                try:
                    gui.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    gui.kill()
                    gui.wait()
    print("Tauri WebView started and received launcher state in Rust mode")
    if not start_minecraft:
        return
    print("Installing and starting Minecraft 1.21.1 using Mojang's Linux Java runtime")
    result = rpc(binary, env, "launch", target=key, timeout=900)
    pid = result["pid"]
    try:
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
        assert os.fsencode(dest) in cmdline and b"--gameDir" in cmdline
        assert list((root / "runtimes").glob("*/.aw-runtime")), "Mojang Java runtime not installed"
        log = dest / "latest-game.log"
        end = time.monotonic() + 120
        while time.monotonic() < end:
            text = log.read_text(errors="replace") if log.exists() else ""
            if re.search(r"Created: .*textures/atlas/gui\.png", text):
                print("Minecraft loaded the GUI texture atlas under Xvfb")
                return
            if not Path(f"/proc/{pid}/cmdline").exists() or not Path(f"/proc/{pid}/cmdline").read_bytes():
                raise AssertionError(text[-8000:])
            time.sleep(1)
        raise AssertionError(text[-8000:])
    finally:
        command = Path(f"/proc/{pid}/cmdline")
        try:
            if os.fsencode(dest) in command.read_bytes().split(b"\0"):
                os.kill(pid, signal.SIGTERM)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="aw-linux-ci-") as scratch:
        exercise(str(Path(sys.argv[1]).resolve()), Path(scratch), "--minecraft" in sys.argv)
