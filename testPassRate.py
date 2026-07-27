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