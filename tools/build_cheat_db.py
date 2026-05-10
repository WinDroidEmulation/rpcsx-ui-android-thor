#!/usr/bin/env python3
import argparse
import hashlib
import html
import json
import re
import sqlite3
from pathlib import Path


TITLE_ID_RE = re.compile(r"\b[A-Z]{4}\d{5}\b")
PPU_HASH_RE = re.compile(r"^PPU-([0-9A-Fa-f]{40}):\s*$")
PATCH_OP_RE = re.compile(r"-\s*\[\s*([^,\]]+)\s*,\s*([^,\]]+)\s*,\s*([^\]]+)\]")
LOAD_RE = re.compile(r"-\s*\[\s*load\s*,\s*\*([A-Za-z0-9_]+)\s*\]")
IGNORED_PATCH_KEYS = {
    "Games",
    "Author",
    "Notes",
    "Patch Version",
    "Group",
    "Patch",
}


def clean_scalar(value):
    value = value.strip().strip('"').strip("'")
    return html.unescape(value).strip()


def yaml_key(value):
    clean = re.sub(r"\s+", " ", value.replace("\\", "\\\\").replace('"', '\\"')).strip()
    return f'"{clean}"'


def safe_asset_name(value):
    return re.sub(r"[^A-Za-z0-9._/ -]", "_", value).strip()


def patch_op_line(op):
    patch_type, address, value = (part.strip() for part in op)
    return f"      - [ {patch_type}, {address}, {value} ]"


def codelist_title_ids(entry):
    ids = []
    for value in entry.get("titleIds") or []:
        normalized = value.upper()
        if TITLE_ID_RE.fullmatch(normalized) and normalized not in ids:
            ids.append(normalized)
    return ids


def hash_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def init_schema(conn):
    conn.executescript(
        """
        PRAGMA foreign_keys = ON;

        CREATE TABLE meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE sources (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            url TEXT,
            source_type TEXT NOT NULL,
            fetched_at TEXT
        );

        CREATE TABLE games (
            id INTEGER PRIMARY KEY,
            title_id TEXT NOT NULL,
            name TEXT NOT NULL,
            version TEXT,
            UNIQUE(title_id, name, version)
        );

        CREATE TABLE cheat_groups (
            id INTEGER PRIMARY KEY,
            game_id INTEGER,
            source_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            format TEXT NOT NULL,
            file_name TEXT NOT NULL,
            asset_name TEXT,
            source_name TEXT,
            size TEXT,
            convertible_count INTEGER DEFAULT 0,
            risky_count INTEGER DEFAULT 0,
            FOREIGN KEY(game_id) REFERENCES games(id),
            FOREIGN KEY(source_id) REFERENCES sources(id)
        );

        CREATE TABLE cheat_group_title_ids (
            group_id INTEGER NOT NULL,
            title_id TEXT NOT NULL,
            PRIMARY KEY(group_id, title_id),
            FOREIGN KEY(group_id) REFERENCES cheat_groups(id)
        );

        CREATE TABLE cheats (
            id INTEGER PRIMARY KEY,
            group_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            author TEXT,
            notes TEXT,
            risk TEXT NOT NULL DEFAULT 'safe',
            enabled_default INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(group_id) REFERENCES cheat_groups(id)
        );

        CREATE TABLE patches (
            id INTEGER PRIMARY KEY,
            cheat_id INTEGER NOT NULL,
            hash_type TEXT,
            hash TEXT,
            patch_type TEXT,
            address TEXT,
            value TEXT,
            raw_yaml TEXT,
            config_yaml TEXT,
            FOREIGN KEY(cheat_id) REFERENCES cheats(id)
        );

        CREATE TABLE raw_files (
            id INTEGER PRIMARY KEY,
            source_id INTEGER NOT NULL,
            file_name TEXT NOT NULL,
            asset_name TEXT,
            sha256 TEXT NOT NULL,
            text TEXT NOT NULL,
            FOREIGN KEY(source_id) REFERENCES sources(id)
        );

        CREATE INDEX idx_games_title_id ON games(title_id);
        CREATE INDEX idx_group_title_ids_title_id ON cheat_group_title_ids(title_id);
        CREATE INDEX idx_groups_format ON cheat_groups(format);
        CREATE INDEX idx_patches_hash ON patches(hash_type, hash);
        """
    )


def insert_source(conn, name, url, source_type):
    cur = conn.execute(
        "INSERT INTO sources(name, url, source_type, fetched_at) VALUES (?, ?, ?, datetime('now'))",
        (name, url, source_type),
    )
    return cur.lastrowid


def upsert_game(conn, title_id, name, version):
    cur = conn.execute(
        """
        INSERT OR IGNORE INTO games(title_id, name, version) VALUES (?, ?, ?)
        """,
        (title_id, name, version),
    )
    if cur.rowcount > 0:
        return cur.lastrowid
    return conn.execute(
        "SELECT id FROM games WHERE title_id = ? AND name = ? AND ifnull(version, '') = ifnull(?, '')",
        (title_id, name, version),
    ).fetchone()[0]


def insert_raw_file(conn, source_id, file_name, asset_name, text):
    conn.execute(
        """
        INSERT INTO raw_files(source_id, file_name, asset_name, sha256, text)
        VALUES (?, ?, ?, ?, ?)
        """,
        (source_id, file_name, asset_name, hash_text(text), text),
    )


def insert_group(conn, game_id, source_id, entry, format_name):
    cur = conn.execute(
        """
        INSERT INTO cheat_groups(
            game_id, source_id, name, format, file_name, asset_name, source_name, size,
            convertible_count, risky_count
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            game_id,
            source_id,
            entry["title"],
            format_name,
            entry["fileName"],
            entry.get("assetName"),
            entry.get("sourceName"),
            entry.get("size"),
            entry.get("convertibleCount") or 0,
            entry.get("riskyCount") or 0,
        ),
    )
    return cur.lastrowid


def insert_group_title_ids(conn, group_id, title_ids):
    for title_id in title_ids:
        conn.execute(
            "INSERT OR IGNORE INTO cheat_group_title_ids(group_id, title_id) VALUES (?, ?)",
            (group_id, title_id),
        )


def insert_cheat(conn, group_id, name, author="", notes="", risk="safe"):
    cur = conn.execute(
        """
        INSERT INTO cheats(group_id, name, author, notes, risk, enabled_default)
        VALUES (?, ?, ?, ?, ?, 0)
        """,
        (group_id, name, author, notes, risk),
    )
    return cur.lastrowid


def add_aldos(conn, assets_dir):
    index_path = assets_dir / "aldos_index.json"
    ncl_dir = assets_dir / "ncl"
    entries = json.loads(index_path.read_text(encoding="utf-8"))
    source_id = insert_source(
        conn,
        "AldosTools / Artemis PS3",
        "http://ps3.aldostools.org/codelist.html",
        "artemis_ncl",
    )

    groups = 0
    for entry in entries:
        ids = codelist_title_ids(entry)
        game_id = None
        if ids:
            game_id = upsert_game(conn, ids[0], entry["title"], entry.get("version") or None)

        group_id = insert_group(conn, game_id, source_id, entry, "artemis_ncl")
        insert_group_title_ids(conn, group_id, ids)
        cheat_id = insert_cheat(
            conn,
            group_id,
            entry["title"],
            notes="Converted on install from bundled Artemis NCL.",
            risk="mixed" if (entry.get("riskyCount") or 0) else "safe",
        )
        asset_name = entry.get("assetName")
        text = ""
        if asset_name:
            file_path = ncl_dir / asset_name
            if file_path.exists():
                text = file_path.read_text(encoding="utf-8", errors="replace")
                insert_raw_file(conn, source_id, entry["fileName"], f"cheats/ncl/{asset_name}", text)
        conn.execute(
            "INSERT INTO patches(cheat_id, raw_yaml) VALUES (?, ?)",
            (cheat_id, text),
        )

        for title_id in ids[1:]:
            upsert_game(conn, title_id, entry["title"], entry.get("version") or None)
        groups += 1

    return groups


def parse_anchor_ops(lines):
    anchors = {}
    in_anchors = False
    current = None
    for line in lines:
        if line.strip() == "Anchors:":
            in_anchors = True
            continue
        if PPU_HASH_RE.match(line):
            break
        if not in_anchors:
            continue

        anchor_match = re.match(r"^\s{2}[A-Za-z0-9_]+:\s*&([A-Za-z0-9_]+)\s*$", line)
        if anchor_match:
            current = anchor_match.group(1)
            anchors[current] = []
            continue

        op_match = PATCH_OP_RE.search(line)
        if current and op_match:
            anchors[current].append(tuple(op_match.group(i).strip() for i in range(1, 4)))

    return anchors


def collect_chidreams_blocks(text):
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    anchors = parse_anchor_ops(lines)
    patches = []
    i = 0
    current_hash = None
    while i < len(lines):
        hash_match = PPU_HASH_RE.match(lines[i])
        if hash_match:
            current_hash = hash_match.group(1).lower()
            i += 1
            continue

        desc_match = re.match(r'^  (\S.*?):\s*$', lines[i])
        if current_hash and desc_match:
            desc = clean_scalar(desc_match.group(1))
            if desc in IGNORED_PATCH_KEYS:
                i += 1
                continue

            block = [lines[i]]
            i += 1
            while i < len(lines):
                if PPU_HASH_RE.match(lines[i]):
                    break
                next_desc = re.match(r'^  (\S.*?):\s*$', lines[i])
                if next_desc and clean_scalar(next_desc.group(1)) not in IGNORED_PATCH_KEYS:
                    break
                block.append(lines[i])
                i += 1
            patches.append(parse_chidreams_patch(current_hash, desc, block, anchors))
            continue

        i += 1
    return [patch for patch in patches if patch is not None]


def parse_chidreams_patch(hash_value, description, block, anchors):
    text = "\n".join(block)
    ids = TITLE_ID_RE.findall(text.upper())
    if not ids:
        return None
    title_id = ids[0]

    game_name = "Unknown Game"
    game_match = re.search(r'^\s{6}"([^"]+)":\s*$', text, re.MULTILINE)
    if game_match:
        game_name = game_match.group(1).strip()

    version = "All"
    version_match = re.search(rf"^\s{{8}}{title_id}:\s*\[\s*([^\]]+?)\s*\]\s*$", text, re.MULTILINE)
    if version_match:
        version = version_match.group(1).strip().strip('"').strip("'")

    author = ""
    author_match = re.search(r"^\s{4}Author:[ \t]*([^\r\n]*)", text, re.MULTILINE)
    if author_match:
        author = clean_scalar(author_match.group(1))

    notes = ""
    notes_match = re.search(r"^\s{4}Notes:[ \t]*([^\r\n]*)", text, re.MULTILINE)
    if notes_match:
        notes = clean_scalar(notes_match.group(1))

    ops = []
    for line in block:
        load_match = LOAD_RE.search(line)
        if load_match:
            ops.extend(anchors.get(load_match.group(1), []))
            continue
        op_match = PATCH_OP_RE.search(line)
        if op_match:
            ops.append(tuple(op_match.group(i).strip() for i in range(1, 4)))

    if not ops:
        return None

    hash_key = f"PPU-{hash_value}"
    patch_body = "\n".join(
        [
            f"{hash_key}:",
            f"  {yaml_key(description)}:",
            "    Games:",
            f"      {yaml_key(game_name)}:",
            f"        {title_id}:",
            f"          - {yaml_key(version)}",
            f"    Author: {yaml_key(author or 'chidreams')}",
            f"    Notes: {yaml_key(notes)}",
            '    Patch Version: "1.0"',
            '    Group: "Chidreams"',
            "    Patch:",
            *[patch_op_line(op) for op in ops],
        ]
    )
    config_body = "\n".join(
        [
            f"{hash_key}:",
            f"  {yaml_key(description)}:",
            f"    {yaml_key(game_name)}:",
            f"      {title_id}:",
            f"        {yaml_key(version)}:",
            "          Enabled: true",
        ]
    )

    return {
        "title": game_name,
        "title_id": title_id,
        "version": version,
        "description": description,
        "author": author or "chidreams",
        "notes": notes,
        "hash": hash_key,
        "ops": ops,
        "patch_body": patch_body,
        "config_body": config_body,
    }


def add_chidreams(conn, assets_dir):
    root = assets_dir / "chidreams"
    if not root.exists():
        return 0

    source_id = insert_source(
        conn,
        "Chidreams RPCS3 imported patches",
        "https://www.reddit.com/r/darkchidreams/",
        "rpcs3_patch",
    )
    count = 0
    for file_path in sorted(root.rglob("*.yml")):
        rel_asset = file_path.relative_to(assets_dir).as_posix()
        text = file_path.read_text(encoding="utf-8", errors="replace")
        insert_raw_file(conn, source_id, file_path.name, f"cheats/{rel_asset}", text)
        for patch in collect_chidreams_blocks(text):
            game_id = upsert_game(conn, patch["title_id"], patch["title"], patch["version"])
            file_name = f"{patch['title']} {patch['title_id']} {patch['version']} - {patch['description']}"
            entry = {
                "title": patch["description"],
                "fileName": file_name,
                "assetName": rel_asset,
                "sourceName": "Chidreams RPCS3 imported_patch.yml",
                "size": f"{len(patch['ops'])} patch ops",
                "convertibleCount": 1,
                "riskyCount": 0,
            }
            group_id = insert_group(conn, game_id, source_id, entry, "rpcs3_patch")
            insert_group_title_ids(conn, group_id, [patch["title_id"]])
            cheat_id = insert_cheat(
                conn,
                group_id,
                patch["description"],
                author=patch["author"],
                notes=patch["notes"],
                risk="safe",
            )
            conn.execute(
                """
                INSERT INTO patches(
                    cheat_id, hash_type, hash, raw_yaml, config_yaml
                ) VALUES (?, 'PPU', ?, ?, ?)
                """,
                (cheat_id, patch["hash"], patch["patch_body"], patch["config_body"]),
            )
            count += 1
    return count


def write_db(assets_dir, output):
    if output.exists():
        output.unlink()

    conn = sqlite3.connect(output)
    try:
        init_schema(conn)
        aldos_count = add_aldos(conn, assets_dir)
        chidreams_count = add_chidreams(conn, assets_dir)
        conn.execute("INSERT INTO meta(key, value) VALUES ('schema_version', '1')")
        conn.execute("INSERT INTO meta(key, value) VALUES ('aldos_entries', ?)", (str(aldos_count),))
        conn.execute("INSERT INTO meta(key, value) VALUES ('chidreams_entries', ?)", (str(chidreams_count),))
        conn.execute("INSERT INTO meta(key, value) VALUES ('generated_by', 'tools/build_cheat_db.py')")
        conn.commit()
        conn.execute("VACUUM")
    finally:
        conn.close()

    return aldos_count, chidreams_count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets-dir", default="app/src/main/assets/cheats")
    parser.add_argument("--output", default="app/src/main/assets/cheats/cheats.db")
    args = parser.parse_args()

    assets_dir = Path(args.assets_dir)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    aldos_count, chidreams_count = write_db(assets_dir, output)
    print(f"Wrote {output} with {aldos_count} Aldos groups and {chidreams_count} Chidreams patches")


if __name__ == "__main__":
    main()
