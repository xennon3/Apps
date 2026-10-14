#!/usr/bin/env python3
"""Builds catalog.json from items/*/item.json.

Run it after adding or changing an item:  python3 scripts/build_catalog.py
Each item folder holds an item.json plus the files Dashplay should download.
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ITEMS = os.path.join(ROOT, "items")

catalog = []
for item_id in sorted(os.listdir(ITEMS)):
    folder = os.path.join(ITEMS, item_id)
    meta_path = os.path.join(folder, "item.json")
    if not os.path.isfile(meta_path):
        continue
    with open(meta_path, encoding="utf-8") as f:
        meta = json.load(f)
    assert meta["id"] == item_id, f"{meta_path}: id must match the folder name"
    files, size = [], 0
    for dirpath, _, names in os.walk(folder):
        for name in sorted(names):
            if name == "item.json":
                continue
            path = os.path.join(dirpath, name)
            files.append(os.path.relpath(path, folder).replace(os.sep, "/"))
            size += os.path.getsize(path)
    assert meta.get("entry", "index.html") in files, f"{item_id}: entry file is missing"
    catalog.append({**meta, "files": sorted(files), "size": size})

with open(os.path.join(ROOT, "catalog.json"), "w", encoding="utf-8") as f:
    json.dump({"version": 1, "items": catalog}, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(f"catalog.json: {len(catalog)} items")
