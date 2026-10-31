"""Builds src/main/resources/whitebag/event-items.txt for the Holidays page: event|months|tier|item.

It reads ut_items.json (the same file build_collection.py uses) and the collection list, and sorts every item under
"Events and seasonal" into the holiday or event it drops in, by the names of the bosses that drop it.

    python tools/build_events.py path/to/ut_items.json
"""
import json
import os
import re
import sys

# event name, months it usually runs (1-12), words in the droppers' names (or the item's)
EVENTS = [
    ("Oryxmas", "12,1", r"christmas|oryxmas|snowman|rudolph|loot elf|frost present|jolly|santa|gingerbread|snow|winter|frost|icicle|present|yuletide|salju|carol"),
    ("Halloween", "10,11", r"halloween|candy|pumpkin|spooky|haunted|sweettreat|jack-o|totalia|moonlight"),
    ("Valentine's", "2", r"valentine|cupid|lovestruck|sweetheart"),
    ("Lunar New Year", "1,2", r"lunar|wyvern"),
    ("Easter", "3,4", r"easter|bunny|eggshell|carrot|fudge"),
    ("Anniversary", "6,7", r"anniversary|venerable"),
    ("Summer festival", "7,8", r"kitsune|hanabi|summer|beach"),
    ("Eternal Phoenix", "", r"phoenix"),
    ("Mad God Mayhem", "", r"mad god mayhem"),
    ("Season missions", "", r"season \d+ missions"),
]
OTHER = ("Other events", "")


def main(src, coll, out):
    data = json.load(open(src, encoding="utf-8"))
    by_name = {i["name"].strip(): i for i in data["items"].values() if i.get("name")}
    rows = []
    for line in open(coll, encoding="utf-8"):
        if not line.startswith("Events and seasonal|"):
            continue
        _, tier, name = line.rstrip("\n").split("|")
        item = by_name.get(name, {})
        texts = [t.lower() for t in item.get("drops", [])]
        best, score = None, 0
        for ev, months, words in EVENTS:
            # the item's own name counts most ("Icicle", "Sweettreats"), then its droppers' names
            n = 10 * len(re.findall(words, name.lower())) + sum(len(re.findall(words, t)) for t in texts)
            if n > score:
                best, score = (ev, months), n
        ev, months = best or OTHER
        rows.append(f"{ev}|{months}|{tier}|{name}")
    with open(out, "w", encoding="utf-8") as f:
        f.write(f"# White Bag Pup holiday items: event|usual months|tier|item (from the RealmEye wiki, {data.get('generated', '?')})\n")
        for r in sorted(rows):
            f.write(r + "\n")
    print(f"{len(rows)} event items -> {out}")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    res = os.path.join(here, "..", "src", "main", "resources", "whitebag")
    main(sys.argv[1] if len(sys.argv) > 1 else "ut_items.json", os.path.join(res, "collection-items.txt"),
         os.path.join(res, "event-items.txt"))
