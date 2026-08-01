#!/usr/bin/env python3
import json
import re
import subprocess
import sys
from datetime import datetime

ROOT = "/opt/xiyiyun"
MYSQL_CMD = [
    "docker", "compose", "-p", "xiyiyun", "-f", "docker-compose.prod.yml", "--env-file", ".env",
    "exec", "-T", "mysql", "sh", "-lc",
    'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" --batch --raw --skip-column-names',
]
PRICE_LIMIT_PATTERN = re.compile(r"限价\s*([0-9]+(?:\.[0-9]+)?)")


def mysql(sql: str) -> str:
    result = subprocess.run(MYSQL_CMD, input=sql.encode("utf-8"), cwd=ROOT, capture_output=True)
    if result.returncode != 0:
        print(result.stderr.decode("utf-8", errors="replace"), file=sys.stderr)
        raise SystemExit(result.returncode)
    return result.stdout.decode("utf-8", errors="replace")


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def main() -> None:
    rows_raw = mysql("""
SELECT
  id,
  COALESCE(name, ''),
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(delivery_template, '$.priceLimitText')), '')
FROM goods;
""")

    updates = []
    for line in rows_raw.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        gid, title, current = parts[:3]
        match = PRICE_LIMIT_PATTERN.search(title)
        if not match:
            continue
        parsed = match.group(1).strip()
        current = "" if current == "NULL" else current.strip()
        if current != parsed:
            updates.append({"id": int(gid), "name": title, "current": current, "parsed": parsed})

    now = datetime.now().strftime("%Y%m%d%H%M%S")
    report_path = f"{ROOT}/.logs/price-limit-sync/price-limit-sync-plan-{now}.json"
    with open(report_path, "w", encoding="utf-8") as report:
        json.dump(updates, report, ensure_ascii=False, indent=2)

    print(json.dumps({"count": len(updates), "report": report_path, "samples": updates[:30]}, ensure_ascii=False, indent=2))

    if "--apply" not in sys.argv or not updates:
        return

    statements = ["START TRANSACTION;"]
    for item in updates:
        statements.append(
            "UPDATE goods "
            "SET delivery_template = JSON_SET(COALESCE(delivery_template, JSON_OBJECT()), "
            "'$.priceLimited', CAST('true' AS JSON), "
            f"'$.priceLimitText', {sql_quote(item['parsed'])}) "
            f"WHERE id = {item['id']};"
        )
    statements.append("COMMIT;")
    mysql("\n".join(statements) + "\n")
    print(json.dumps({"updated": len(updates), "report": report_path}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
