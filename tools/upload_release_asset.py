"""Upload one GitHub release asset without Windows Schannel; credentials use stdin."""
import hashlib
import json
import pathlib
import sys
import urllib.request


def main():
    config = json.load(sys.stdin)
    url = config["url"]
    if not url.startswith("https://uploads.github.com/repos/"):
        raise ValueError("Unexpected upload destination")
    data = pathlib.Path(config["file"]).read_bytes()
    request = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Authorization": "Bearer " + config["token"],
            "Content-Type": "application/octet-stream",
            "Accept": "application/vnd.github+json",
            "User-Agent": "AWLauncher-release-publisher",
        },
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        asset = json.load(response)
    expected = "sha256:" + hashlib.sha256(data).hexdigest()
    if asset["size"] != len(data) or (asset.get("digest") and asset["digest"] != expected):
        raise ValueError("Uploaded asset checksum or size mismatch")
    print(json.dumps(asset))


if __name__ == "__main__":
    main()
