from pathlib import Path

path = Path("core/database/src/debug/kotlin/app/muxtv/database/measurement/RefreshDeltaDatabaseMeasurementRunner.kt")
text = path.read_text(encoding="utf-8")

old_enum = '''    REORDER("reorder"),
    REMOVE_10("remove-10");'''
new_enum = '''    REORDER("reorder"),
    REMOVE_10("remove-10"),
    TOKEN_CHURN("token-churn");'''
if text.count(old_enum) != 1:
    raise SystemExit("C03 scenario enum marker is not unique")
text = text.replace(old_enum, new_enum)

old_when = '''        REORDER -> previous.asReversed().mapIndexed { order, item -> item.copy(order = order) }
        REMOVE_10 -> previous.dropLast(previous.size / 10)
    }'''
new_when = '''        REORDER -> previous.asReversed().mapIndexed { order, item -> item.copy(order = order) }
        REMOVE_10 -> previous.dropLast(previous.size / 10)
        TOKEN_CHURN -> RefreshDeltaDatabaseFixture.tokenizedLocatorChurn(previous)
    }'''
if text.count(old_when) != 1:
    raise SystemExit("C03 scenario apply marker is not unique")
text = text.replace(old_when, new_when)

marker = '''    fun activeDigest(items: List<RefreshDeltaDatabaseItem>): String =
        digestPairs(items.map { it.stableKey to it.contentHash })
'''
insert = '''    fun tokenizedLocatorChurn(
        previous: List<RefreshDeltaDatabaseItem>,
    ): List<RefreshDeltaDatabaseItem> = previous.mapIndexed { index, item ->
        item.withLocator("${item.locator}&token=rotated-$SEED-$index")
    }

'''
if text.count(marker) != 1:
    raise SystemExit("C03 fixture insertion marker is not unique")
text = text.replace(marker, insert + marker)

path.write_text(text, encoding="utf-8")
