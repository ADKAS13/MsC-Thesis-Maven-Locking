# Plugin commands

`mvn com.baeldung:counter-maven-plugin:1.0-SNAPSHOT:init -DallowVersionRanges=`
-DallowVersionRanges:
major
minor
patch

`mvn com.baeldung:counter-maven-plugin:1.0-SNAPSHOT:flatten`

## Telegram bots

`mvn clean install -Denforcer.skip=true -Dgpg.skip=true`
`mvn dependency:tree > telegrambotsOG.txt`
`mvn dependency:tree > telegrabotsMod.txt`
`python3 compareDeps.py trees/telegrambots/telegrambotsOG.txt trees/telegrambots/telegrambotsMod.txt`

338 Total tests

## Automatalib

`mvn clean install -Darchetype.test.skip=true`
`mvn dependency:tree > automatalibOG.txt`
`mvn dependency:tree > automatalibMod.txt`

`python3 compareDeps.py trees/automatalib/automatalibOG.txt trees/automatalib/automatalibMod.txt`

```bash
mvn clean install \
  -Dmaven.compiler.fork=true \
  -Dmaven.compiler.executable=$(sdk home java 8.0.392-tem)/bin/javac \
  -Darchetype.test.skip=true
```

## Test success rates

Seeing test pass rates:
Maven Surefire (the test runner) generates reports in target/surefire-reports/ for each module. After a build run:

bash

```bash
# Quick summary per module
find . -path "*/surefire-reports/TEST-*.xml" | xargs grep -h "tests=" | sed 's/.*tests="\([^"]*\)".*/Tests: \1/'
```

Or a more readable summary:

```bash
  find . -name "TEST-*.xml" -path "*/surefire-reports/*" | xargs python3 -c "
  import sys, xml.etree.ElementTree as ET
  total, failures, errors, skipped = 0, 0, 0, 0
  for f in sys.argv[1:]:
      r = ET.parse(f).getroot()
      total += int(r.get('tests', 0))
      failures += int(r.get('failures', 0))
      errors += int(r.get('errors', 0))
      skipped += int(r.get('skipped', 0))
  passed = total - failures - errors - skipped
  print(f'Total: {total}, Passed: {passed}, Failed: {failures}, Errors: {errors}, Skipped: {skipped}')
  print(f'Pass rate: {passed/total*100:.1f}%' if total else 'No tests')
  "
```

useful flags:
-fae -> fail at end, still marks build failure, but allows non-impacted builds to continue
