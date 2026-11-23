"""Controleert dat elke gewijzigde regel in src/main/java door een test geraakt wordt.

Leest het JSON-rapport van diff-cover. Een ongedekte regel mag alleen blijven als hij of de
regel direct erboven het commentaar `// onbereikbaar: <reden>` heeft, voor code die geen test
kan bereiken, zoals een catch op NoSuchAlgorithmException voor een algoritme dat elke JVM levert.
"""

import json
import re
import sys
from pathlib import Path

MARKER = re.compile(r"//\s*onbereikbaar:\s*\S")


def is_uitgezonderd(regels, nummer):
    # nummer is 1-based; kijk naar de regel zelf en de regel erboven.
    for index in (nummer - 1, nummer - 2):
        if 0 <= index < len(regels) and MARKER.search(regels[index]):
            return True

    return False


def main(rapport_pad):
    rapport = json.loads(Path(rapport_pad).read_text(encoding="utf-8"))
    fouten = []
    uitzonderingen = []

    for pad, statistiek in sorted(rapport.get("src_stats", {}).items()):
        regels = Path(pad).read_text(encoding="utf-8").splitlines()

        for nummer in statistiek.get("violation_lines", []):
            doel = uitzonderingen if is_uitgezonderd(regels, nummer) else fouten
            doel.append(f"{pad}:{nummer}")

    for plek in uitzonderingen:
        print(f"uitgezonderd (onbereikbaar): {plek}")

    if fouten:
        print(f"\n{len(fouten)} gewijzigde regel(s) niet gedekt door een test:")
        for plek in fouten:
            print(f"  {plek}")
        print("\nSchrijf een test, haal ongebruikte code weg, of markeer onbereikbare code met "
              "'// onbereikbaar: <reden>' op de regel zelf of de regel erboven.")
        return 1

    print(f"Alle gewijzigde regels gedekt ({rapport.get('total_num_lines', 0)} meetbare regels).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
