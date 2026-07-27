#!/usr/bin/env python3
"""
compare_deps.py - Compare two mvn dependency:tree outputs.

Usage:
    python compare_deps.py <original.txt> <modified.txt> [--mode exact|upgraded]

Modes:
    exact    - Check both trees contain the exact same dependencies (default)
    upgraded - Check modified tree contains all original deps, but allows newer versions

Flags:
    --ignore-scope  Ignore scope differences (e.g. compile vs test)
"""

import re
import sys
import argparse
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class Dependency:
    group_id: str
    artifact_id: str
    packaging: str
    version: str
    scope: str
    classifier: Optional[str] = None

    @property
    def ga(self):
        """GroupId:ArtifactId key"""
        return f"{self.group_id}:{self.artifact_id}"

    @property
    def coord_key(self):
        """Unique coordinate key excluding version and scope"""
        if self.classifier:
            return f"{self.group_id}:{self.artifact_id}:{self.packaging}:{self.classifier}"
        return f"{self.group_id}:{self.artifact_id}:{self.packaging}"

    def __str__(self):
        if self.classifier:
            return f"{self.group_id}:{self.artifact_id}:{self.packaging}:{self.classifier}:{self.version}:{self.scope}"
        return f"{self.group_id}:{self.artifact_id}:{self.packaging}:{self.version}:{self.scope}"


def parse_version(v: str):
    """Parse a version string into a tuple for comparison."""
    parts = re.split(r'[.\-]', v)
    result = []
    for p in parts:
        try:
            result.append((0, int(p)))
        except ValueError:
            result.append((1, p))
    return result


def is_newer_or_equal(v1: str, v2: str) -> bool:
    """Return True if v2 >= v1."""
    try:
        return parse_version(v2) >= parse_version(v1)
    except Exception:
        return v2 >= v1


def parse_dep_line(line: str) -> Optional[Dependency]:
    """
    Parse a single dependency line from mvn dependency:tree output positional format.
    Handles parenthetical updates, omissions, and optional classifiers seamlessly.
    """
    line = re.sub(r'^\[INFO\]\s*', '', line)
    line = re.sub(r'^[\s|+\\\-]+', '', line).strip()
    
    # Handle omitted lines wrapped in outer parentheses
    line = line.lstrip('(')

    # Valid segment characters (alphanumeric, dots, hyphens, underscores, plus signs)
    seg = r'[\w.\-+]+'

    # Try 6 parts: g:a:p:c:v:s
    match6 = re.match(r'^(' + seg + r'):(' + seg + r'):(' + seg + r'):(' + seg + r'):(' + seg + r'):(' + seg + r')', line)
    if match6:
        g, a, p, c, v, s = match6.groups()
        return Dependency(group_id=g, artifact_id=a, packaging=p, classifier=c, version=v, scope=s)

    # Try 5 parts: g:a:p:v:s
    match5 = re.match(r'^(' + seg + r'):(' + seg + r'):(' + seg + r'):(' + seg + r'):(' + seg + r')', line)
    if match5:
        g, a, p, v, s = match5.groups()
        return Dependency(group_id=g, artifact_id=a, packaging=p, version=v, scope=s)

    # Try 4 parts: g:a:p:v (e.g. root module)
    match4 = re.match(r'^(' + seg + r'):(' + seg + r'):(' + seg + r'):(' + seg + r')', line)
    if match4:
        g, a, p, v = match4.groups()
        return Dependency(group_id=g, artifact_id=a, packaging=p, version=v, scope='compile')

    return None


def parse_module_name(line: str) -> Optional[str]:
    """Extract module name from a 'Building ...' line."""
    match = re.search(r'Building (.+?)\s+\[', line)
    if match:
        return match.group(1).strip()
    return None


def parse_tree_file(filepath: str) -> dict[str, set[Dependency]]:
    """Parse dependency:tree file and group entries by module."""
    modules = {}
    current_module = "__root__"
    current_deps = set()

    with open(filepath, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.rstrip()

            module_name = parse_module_name(line)
            if module_name:
                if current_deps:
                    modules[current_module] = current_deps
                current_module = module_name
                current_deps = set()
                continue

            dep = parse_dep_line(line)
            if dep:
                current_deps.add(dep)

    if current_deps:
        modules[current_module] = current_deps

    return modules


def flatten_to_coord_map(deps: set[Dependency]) -> dict[str, Dependency]:
    """Map unique coordinate string -> Dependency."""
    result = {}
    for dep in deps:
        key = dep.coord_key
        if key not in result or is_newer_or_equal(result[key].version, dep.version):
            result[key] = dep
    return result


def normalize_deps(deps: set[Dependency], ignore_scope: bool) -> set[Dependency]:
    """Mask scope details if ignore_scope is active."""
    if not ignore_scope:
        return deps
    return {Dependency(d.group_id, d.artifact_id, d.packaging, d.version, "__any__", d.classifier) for d in deps}


def compare_modules(
    orig_deps: set[Dependency],
    mod_deps: set[Dependency],
    mode: str,
    ignore_scope: bool = False,
) -> dict:
    """Compare two sets of dependencies for one module."""
    raw_orig_map = flatten_to_coord_map(orig_deps)
    raw_mod_map  = flatten_to_coord_map(mod_deps)

    orig_map = flatten_to_coord_map(normalize_deps(orig_deps, ignore_scope))
    mod_map  = flatten_to_coord_map(normalize_deps(mod_deps, ignore_scope))

    missing = []       
    extra = []         
    version_changes = []  
    version_errors = []   
    scope_changes = []    

    orig_keys = set(orig_map.keys())
    mod_keys = set(mod_map.keys())

    for key in orig_keys - mod_keys:
        missing.append(raw_orig_map[key])

    for key in mod_keys - orig_keys:
        extra.append(raw_mod_map[key])

    for key in orig_keys & mod_keys:
        o = orig_map[key]
        m = mod_map[key]
        
        version_differ = o.version != m.version
        
        raw_o = raw_orig_map.get(key, o)
        raw_m = raw_mod_map.get(key, m)
        scope_differ = raw_o.scope != raw_m.scope

        if version_differ:
            version_changes.append((raw_o, raw_m))
            if mode == 'upgraded' and not is_newer_or_equal(o.version, m.version):
                version_errors.append((raw_o, raw_m))
        
        if scope_differ:
            if not ignore_scope:
                if not version_differ:
                    version_changes.append((raw_o, raw_m))
            else:
                scope_changes.append((raw_o, raw_m))

    passed = (
        len(missing) == 0 and
        (mode == 'upgraded' or len(extra) == 0) and
        (mode == 'exact' and len(version_changes) == 0 or
         mode == 'upgraded' and len(version_errors) == 0)
    )

    return {
        'passed': passed,
        'missing': missing,
        'extra': extra,
        'version_changes': version_changes,
        'version_errors': version_errors,
        'scope_changes': scope_changes,
    }


def print_report(
    orig_modules: dict,
    mod_modules: dict,
    mode: str,
    orig_path: str,
    mod_path: str,
    ignore_scope: bool = False,
):
    all_modules = sorted(set(orig_modules.keys()) | set(mod_modules.keys()))
    total_pass = 0
    total_fail = 0

    print("=" * 70)
    print(f"  Dependency Tree Comparison")
    print(f"  Original : {orig_path}")
    print(f"  Modified : {mod_path}")
    print(f"  Mode     : {mode}" + (" (scope ignored)" if ignore_scope else ""))
    print("=" * 70)

    for module in all_modules:
        orig_deps = orig_modules.get(module, set())
        mod_deps = mod_modules.get(module, set())

        if not orig_deps and not mod_deps:
            continue

        result = compare_modules(orig_deps, mod_deps, mode, ignore_scope)
        status = "PASS" if result['passed'] else "FAIL"

        if result['passed']:
            total_pass += 1
        else:
            total_fail += 1

        print(f"\n[{status}] {module}")

        if result['missing']:
            print("  MISSING (in original, not in modified):")
            for d in sorted(result['missing'], key=str):
                print(f"    - {d}")

        if result['extra'] and mode == 'exact':
            print("  EXTRA (in modified, not in original):")
            for d in sorted(result['extra'], key=str):
                print(f"    + {d}")
        elif result['extra'] and mode == 'upgraded':
            print("  NEW (added in modified):")
            for d in sorted(result['extra'], key=str):
                print(f"    + {d}")

        if result['version_changes']:
            errors = set(str(e[0].coord_key) for e in result['version_errors'])
            for orig_d, mod_d in sorted(result['version_changes'], key=lambda x: str(x[0])):
                tag = "ERR" if orig_d.coord_key in errors else "CHG"
                scope_note = f" [scope: {orig_d.scope} -> {mod_d.scope}]" if orig_d.scope != mod_d.scope else ""
                print(f"  [{tag}] {orig_d.ga}: {orig_d.version} -> {mod_d.version}{scope_note}")

        if result['scope_changes']:
            print("  SCOPE (ignored, informational):")
            for orig_d, mod_d in sorted(result['scope_changes'], key=lambda x: str(x[0])):
                print(f"  [SCP] {orig_d.ga}:{orig_d.version} [{orig_d.scope} -> {mod_d.scope}]")

    print("\n" + "=" * 70)
    total = total_pass + total_fail
    pct = (total_pass / total * 100) if total else 0
    print(f"  Result: {total_pass}/{total} modules passed ({pct:.1f}%)")

    if total_fail == 0:
        print("  Overall: PASS ✓")
    else:
        print(f"  Overall: FAIL ✗  ({total_fail} module(s) with issues)")
    print("=" * 70)

    return total_fail == 0


def main():
    parser = argparse.ArgumentParser(description="Compare two mvn dependency:tree outputs.")
    parser.add_argument("original", help="Path to original dependency:tree output")
    parser.add_argument("modified", help="Path to modified dependency:tree output")
    parser.add_argument(
        "--mode",
        choices=["exact", "upgraded"],
        default="exact",
        help="exact: same deps/versions; upgraded: newer versions allowed (default: exact)"
    )
    parser.add_argument(
        "--ignore-scope",
        action="store_true",
        help="Ignore scope differences (e.g. compile vs test) when comparing"
    )
    args = parser.parse_args()

    orig_modules = parse_tree_file(args.original)
    mod_modules = parse_tree_file(args.modified)

    passed = print_report(orig_modules, mod_modules, args.mode, args.original, args.modified, args.ignore_scope)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()