#!/usr/bin/env python3
"""Metody mostu (@JavascriptInterface), které UI (app.js / index.html) nikdy nevolá – zbytečná útočná plocha."""
import re, sys
b = open('app/src/main/java/cz/axiom/omega/bridge/OmegaBridge.kt').read()
js = open('app/src/main/assets/ui/app.js').read() + open('app/src/main/assets/ui/index.html').read()
names = re.findall(r'@JavascriptInterface\s*(?:\n\s*)?fun\s+([A-Za-z0-9_]+)', b)
dead = [n for n in names if not re.search(r'\b' + n + r'\b', js)]
print(len(names), 'metod mostu · nevolané z UI:', dead)
sys.exit(1 if dead else 0)
