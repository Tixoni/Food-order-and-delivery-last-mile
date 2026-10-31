"""
Планирование из командной строки:

    python -m delivery.lastmile ../data/moscow_sample.json
    python -m delivery.lastmile ../data/moscow_sample.json --json
"""

import json
import sys

from .plan import format_report, make_plan
from .problem import load_problem


def main(argv):
    if not argv:
        print("укажите файл задачи: python -m delivery.lastmile задача.json [--json]")
        return 2
    plan = make_plan(load_problem(argv[0]))
    if "--json" in argv:
        print(json.dumps(plan, ensure_ascii=False, indent=2, default=str))
    else:
        print(format_report(plan))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
