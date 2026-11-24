"""Prepare an installer tag locally or publish it from the fork's GitHub workflow."""

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
REPO = "seionwon/Handy"
WEB = f"https://github.com/{REPO}"
MANIFEST = "packaging/fork-installers.json"
WORKFLOW = ".github/workflows/fork-installers.yml"
HOME_PAGE = ".github/README.md"
LEGACY_PAGE_MARKER = "<!-- Handy fork installer downloads -->"
DOWNLOADS_START = "<!-- installer-downloads:start -->"
DOWNLOADS_END = "<!-- installer-downloads:end -->"
VERSION = r"[0-9]+\.[0-9]+\.[0-9]+-seionwon"


def command(*args, data=None, env=None):
    return subprocess.check_output(args, input=data, cwd=ROOT, env=env).decode().strip()


def load_release():
    release = json.loads((ROOT / MANIFEST).read_text())
    if not re.fullmatch(VERSION, release["version"]) or not release["assets"]:
        raise ValueError("Expected a versioned fork installer release.")
    names = set()
    for asset in release["assets"]:
        name = asset["file"]
        if (not re.fullmatch(r"Handy[-_][A-Za-z0-9._-]+\.(apk|pkg|exe|msi)", name)
                or not re.fullmatch(VERSION, asset["version"]) or asset["version"] not in name
                or not re.fullmatch(r"[0-9a-f]{64}", asset["sha256"]) or name in names):
            raise ValueError(f"Invalid installer entry: {name}")
        names.add(name)
    return release


def homepage(release, tag, page):
    if page.count(DOWNLOADS_START) != 1 or page.count(DOWNLOADS_END) != 1:
        raise ValueError(f"Expected one download section in {HOME_PAGE}.")
    intro, _, section = page.partition(DOWNLOADS_START)
    _, end, footer = section.partition(DOWNLOADS_END)
    if not end:
        raise ValueError(f"Download section markers are out of order in {HOME_PAGE}.")
    rows = [
        "| 기기 / Device | 버전 / Version | 다운로드 / Download |",
        "| --- | --- | --- |",
    ]
    for asset in release["assets"]:
        link = f"{WEB}/releases/download/{tag}/{asset['file']}"
        rows.append(f"| {asset['device']} | {asset['version']} | **[{asset['label']}]({link})** |")
    return intro + DOWNLOADS_START + "\n\n" + "\n".join(rows) + "\n\n" + end + footer


def prepare(release, tag, page):
    local_homepage = ROOT / HOME_PAGE
    # An independent index keeps the source checkout, its index and any other
    # in-progress changes out of the distribution tag.
    with tempfile.TemporaryDirectory(prefix="handy-git-index-") as temp:
        env = {**os.environ, "GIT_INDEX_FILE": str(Path(temp) / "index")}

        def add(path, contents):
            blob = command("git", "hash-object", "-w", "--stdin", data=contents)
            command("git", "update-index", "--add", "--cacheinfo", f"100644,{blob},{path}", env=env)

        for asset in release["assets"]:
            name = asset["file"]
            path = ROOT / name
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 100 * 1024**2:
                raise ValueError(f"Expected a regular installer within GitHub's Git file limit: {name}")
            contents = path.read_bytes()
            if hashlib.sha256(contents).hexdigest() != asset["sha256"]:
                raise ValueError(f"Installer checksum mismatch: {name}")
            add(f"assets/{name}", contents)
        for path in (MANIFEST, WORKFLOW, "scripts/fork_installers.py", "LICENSE"):
            add(path, (ROOT / path).read_bytes())
        add(HOME_PAGE, page.encode())
        tree = command("git", "write-tree", env=env)

    ref = f"refs/tags/{tag}"
    existing = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{tree}}"],
                              cwd=ROOT, capture_output=True, text=True)
    if existing.returncode == 1:
        commit = command("git", "commit-tree", tree, "-m", f"chore(release): distribute {tag}")
        command("git", "update-ref", ref, commit, "")
    else:
        existing.check_returncode()
        # Documentation edits may follow a release. Keep every other path in
        # the installer snapshot identical, including its set of files.
        payload = subprocess.run(["git", "diff", "--quiet", existing.stdout.strip(), tree,
                                  "--", ".", f":(exclude){HOME_PAGE}",
                                  ":(exclude)scripts/fork_installers.py"], cwd=ROOT)
        if payload.returncode == 1:
            raise ValueError(f"The existing tag differs from this installer snapshot: {tag}")
        payload.check_returncode()
    current_page = local_homepage.read_text()
    preview = homepage(release, tag, current_page)
    if preview != current_page:
        local_homepage.write_text(preview)
    print(f"Verified {len(release['assets'])} installers and prepared {ref}")
    return tree


def push_tag(tag, prepared_tree):
    targets = command("git", "remote", "get-url", "--push", "--all", "origin").splitlines()
    github = r"(?:https://(?:[^/@]+@)?github\.com/|git@github\.com:|ssh://git@github\.com/)"
    if not targets or not all(re.fullmatch(github + REPO + r"(?:\.git)?/?", url, re.I) for url in targets):
        raise ValueError(f"The push destination of origin must be {REPO}.")
    ref = "refs/tags/" + tag
    if command("git", "rev-parse", f"{ref}^{{tree}}") != prepared_tree:
        # A retry can refer to the old homepage already on GitHub. Never
        # upload a different local publisher or move an existing tag.
        remote = command("git", "ls-remote", "--refs", "origin", ref)
        if remote != command("git", "rev-parse", ref) + "\t" + ref:
            raise ValueError(f"The local tag has different publishing files. Use a new tag: {tag}")
        print(f"{ref} is already on origin.")
        return
    subprocess.run(["git", "push", "origin", f"{ref}:{ref}"], cwd=ROOT, check=True)


def api(path, *, method="GET", payload=None, optional=False):
    args = ["gh", "api", "--method", method, f"repos/{REPO}/{path}"]
    if payload is not None:
        args.extend(["--input", "-"])
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            capture_output=True, text=True)
    if result.returncode:
        if optional and "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip())
    return json.loads(result.stdout) if result.stdout.strip() else None


def numeric_version(version):
    return tuple(map(int, version.split("-")[0].split(".")))


def remote_digest(asset):
    if asset.get("digest"):
        return asset["digest"]
    endpoint = f"repos/{REPO}/releases/assets/{asset['id']}"
    with subprocess.Popen(["gh", "api", "-H", "Accept: application/octet-stream", endpoint],
                          stdout=subprocess.PIPE) as request:
        digest = hashlib.file_digest(request.stdout, "sha256").hexdigest()
        if request.wait():
            raise ValueError(f"Could not verify the uploaded file: {asset['name']}")
    return "sha256:" + digest


def publish(release, tag, page):
    if (os.environ.get("GITHUB_REPOSITORY") != REPO
            or os.environ.get("GITHUB_REF") != f"refs/tags/{tag}"):
        raise ValueError("Publish from the matching installer tag in the fork.")
    expected = {a["file"]: a["sha256"] for a in release["assets"]}
    with tempfile.TemporaryDirectory(prefix="handy-checksums-") as temp:
        sums = Path(temp) / "SHA256SUMS.txt"
        sums.write_text("".join(f"{digest}  {name}\n" for name, digest in expected.items()))
        files = {name: ROOT / "assets" / name for name in expected}
        files[sums.name] = sums
        expected[sums.name] = hashlib.sha256(sums.read_bytes()).hexdigest()
        for name, path in files.items():
            with path.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected[name]:
                    raise ValueError(f"Installer tag has an unexpected checksum: {name}")

        # The list includes drafts, so a rerun can finish an interrupted upload.
        ids = command("gh", "api", "--paginate", f"repos/{REPO}/releases?per_page=100",
                      "--jq", f'.[] | select(.tag_name == "{tag}") | .id').splitlines()
        if len(ids) > 1:
            raise ValueError(f"Multiple releases already have tag {tag}")
        info = (api(f"releases/{ids[0]}") if ids else
                api("releases", method="POST", payload={"tag_name": tag, "name": release["title"],
                                                        "body": page, "draft": True}))
        endpoint = f"releases/{info['id']}"
        existing = {asset["name"]: asset for asset in info["assets"]}
        for name in expected.keys() & existing.keys():
            asset = existing[name]
            if info["draft"] and asset["state"] == "starter" and asset["size"] == 0 and not asset.get("digest"):
                api(f"releases/assets/{asset['id']}", method="DELETE")
                del existing[name]
            elif asset["state"] != "uploaded" or remote_digest(asset) != f"sha256:{expected[name]}":
                raise ValueError(f"A different release asset already has this name: {name}")
        missing = [str(path) for name, path in files.items() if name not in existing]
        if missing:
            command("gh", "release", "upload", tag, *missing, "--repo", REPO)
        uploaded = {a["name"]: remote_digest(a) for a in api(endpoint)["assets"]
                    if a["state"] == "uploaded" and a["name"] in expected}
        if any(uploaded.get(n) != f"sha256:{d}" for n, d in expected.items()):
            raise ValueError("GitHub has not verified the full installer set.")
        if info["draft"]:
            latest = api("releases/latest", optional=True)
            previous = re.fullmatch(rf"(?:installers-|v)({VERSION})", latest["tag_name"]) if latest else None
            promote = not previous or numeric_version(release["version"]) >= numeric_version(previous[1])
            api(endpoint, method="PATCH", payload={"draft": False, "name": release["title"],
                                                  "body": page, "make_latest": str(promote).lower()})

    if api("releases/latest")["tag_name"] != tag:
        return
    for asset in release["assets"]:
        url = f"{WEB}/releases/download/{tag}/{asset['file']}"
        with urlopen(Request(url, headers={"User-Agent": "Handy-release-check"}), timeout=60) as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != asset["sha256"]:
                raise ValueError(f"Public download checksum mismatch: {asset['file']}")

    if api("releases/latest")["tag_name"] != tag:
        return
    endpoint = f"contents/{HOME_PAGE}"
    current = api(endpoint, optional=True)
    update = {"message": f"docs: show downloads for {tag}"}
    if current:
        old_page = base64.b64decode(current["content"]).decode()
        if not old_page.startswith(LEGACY_PAGE_MARKER):
            page = homepage(release, tag, old_page)
        if old_page == page:
            return
        update["sha"] = current["sha"]
    update["content"] = base64.b64encode(page.encode()).decode()
    api(endpoint, method="PUT", payload=update)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "push", "publish"])
    args = parser.parse_args()
    release = load_release()
    tag = "installers-" + release["version"]
    page = homepage(release, tag, (ROOT / HOME_PAGE).read_text())
    if args.action == "publish":
        publish(release, tag, page)
    else:
        tree = prepare(release, tag, page)
        if args.action == "push":
            push_tag(tag, tree)
