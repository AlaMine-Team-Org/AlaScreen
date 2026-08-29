#!/usr/bin/env python3
"""Check that the Fabric and NeoForge entrypoints still agree.

    python3 tools/loader_parity_check.py

Every part of this mod a player touches - the command tree, the capture, the
confirmation screen, the translations, the history file - lives in `common` and
`common-mc` and is compiled into both jars from one source. Writing a feature
twice is therefore not possible here.

Forgetting to *call* it on one loader is. An entrypoint missing `warmUp()`
compiles, loads and runs: the mod simply never asks the site for its limits and
starts refusing uploads against stale numbers. The compiler says nothing, the
unit tests say nothing, and the player finds out first.

So this script compares the only two files that are written twice, and only for
things that can actually diverge. A check that compares a value against itself
is worse than no check - it costs a CI minute and buys the reassurance of having
tested nothing. That is why the shared command tree, the chat components and the
bundled assets are absent from the list below: one copy each, already verified
inside the built jar by verify-artifact.sh.

What is compared:

  0. The two entrypoints call the SAME set of methods on shared objects. This is
     the check that catches what nobody thought to list: the hand-written list in
     (1) only covers steps someone remembered, and the first real divergence was
     one nobody had - the NeoForge side never called api.close(), so an upload
     still in flight when the player quit died with the process instead of being
     given the few seconds of grace the Fabric side gives it. The list said
     nothing, because api.close() was not on it.
  1. Both entrypoints perform the same shared steps.
  2. The default key matches. Different defaults per loader is a different mod
     for anyone who installs the other jar.
  3. Each entrypoint passes its OWN loader tag. Equality would be the bug here:
     the tag goes into the User-Agent and the upload metadata, so a copied one
     means half the numbers are wrong.
  4. Both loaders have a mixin, declared where their loader looks for it. A
     mixin that is not applied breaks nothing at build time - the vanilla F2
     message just stops offering [share], which is the main way into the mod.
  5. Both are declared client-side. A mod that claims to be server-side ends up
     in server packs, doing nothing.

Exit codes: 0 parity holds, 1 the entrypoints diverged, 2 the check could not run.
"""
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

FABRIC_ENTRY = "fabric/src/main/java/day/alacraft/alascreen/fabric/AlaScreenFabric.java"
NEOFORGE_ENTRY = "neoforge/src/main/java/day/alacraft/alascreen/neoforge/AlaScreenNeoForge.java"

FABRIC_MIXIN = "fabric/src/main/java/day/alacraft/alascreen/fabric/mixin/ScreenshotMixin.java"
NEOFORGE_MIXIN = "neoforge/src/main/java/day/alacraft/alascreen/neoforge/mixin/ScreenshotMixin.java"

FABRIC_META = "fabric/src/main/resources/fabric.mod.json"
NEOFORGE_META = "neoforge/src/main/resources/META-INF/neoforge.mods.toml"

# Steps each entrypoint has to perform. Not one of them is enforced by the
# compiler: dropping any leaves the mod building and apparently working.
REQUIRED_CALLS = [
    ("Config.load(", "the config is never read, so player settings are ignored"),
    ("new UploadHistory(", "no history, so [delete] stops working after a restart"),
    ("new ShareController(", "nothing handles the keypress"),
    ("CommandTree.build(", "the command tree is not registered, so /alascreen does not exist"),
    ("service.warmUp()", "limits are never fetched, so uploads are refused instead of downscaled"),
    ("consumeClick()", "the key is never polled, so pressing it does nothing"),
    ("captureAndShare(", "the keypress leads nowhere"),
    ("buildApi(", "there is no API client, so there is nowhere to upload"),
]

# Objects whose methods are shared logic, so a call on one of them belongs on
# both sides. Instance names and type names both appear because the entrypoints
# use a mix of the two.
#
# This list is kept honest by check_owner_list_current(): a shared type imported
# by an entrypoint but missing here fails the run, so the list cannot quietly
# fall behind the code it is supposed to cover.
SHARED_OWNERS = {
    "service", "api", "controller", "config",
    "AlaScreen", "BuildInfo", "Config", "UploadService",
    "AlaScreenApi", "UploadHistory", "CommandTree", "ShareController",
}

CALL_PATTERN = re.compile(r"(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*([a-zA-Z_][A-Za-z0-9_]*)\s*\(")
IMPORT_PATTERN = re.compile(r"^import\s+day\.alacraft\.alascreen\.(?:[a-z0-9_]+\.)*([A-Z][A-Za-z0-9_]*)\s*;", re.M)

failures = []
notes = []


def reject_text_blocks(source, where):
    """Refuse to scan a file containing a Java text block.

    `strip_comments` reads `\"\"\"` as an empty literal followed by the start of a
    new one, so everything after it is mis-parsed - and the dangerous direction
    is silent: calls swallowed into a phantom literal simply stop being
    compared, and the check reports agreement it never established.

    There are none in this codebase today. Stopping with a clear message is the
    honest response to the day someone adds one; teaching the scanner text
    blocks the day it is actually needed beats carrying untested code for it now.
    """
    if '\"\"\"' in source:
        print("FATAL: %s contains a Java text block, which this scanner cannot parse "
              "correctly. Teach strip_comments about text blocks before relying on "
              "this check again." % where, file=sys.stderr)
        sys.exit(2)


def strip_comments(source):
    """Remove comments from Java source without touching string literals.

    Without this the whole check is defeated by two slashes: a commented-out
    `// service.warmUp();` still CONTAINS the substring being searched for, so a
    plain `in` test happily counts a call that no longer happens. The mutation
    suite caught exactly that on its first run.

    The naive fix breaks the opposite way. `"https://alacraft.day"` is not a
    comment, and eating the rest of that line would delete real code. So literal
    state is tracked properly here, escapes included.
    """
    out = []
    i = 0
    n = len(source)
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if c == '"' or c == "'":
            quote = c
            out.append(c)
            i += 1
            while i < n:
                if source[i] == "\\":          # escape: skip the pair as a unit
                    out.append("  ")
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == quote:
                    i += 1
                    break
                i += 1
            continue

        if c == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue

        if c == "/" and nxt == "*":
            i += 2
            while i < n - 1 and not (source[i] == "*" and source[i + 1] == "/"):
                if source[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
            continue

        out.append(c)
        i += 1
    return "".join(out)


def _selftest():
    """Test the scanner everything else rests on.

    A broken `strip_comments` does not start failing this check - it starts
    passing it. That is why the self-test always runs instead of hiding behind
    a flag.
    """
    cases = [
        ("a(); // b();", "a()", "b()"),
        ("/* c(); */ d();", "d()", "c()"),
        ('String u = "https://x"; // e();', '"https://x"', "e()"),
        # An escaped quote must not close the literal. This case breaks in both
        # directions if it does: everything after would fall into a new literal,
        # so f() would survive (its comment unrecognised) and g() would vanish
        # (swallowed by the literal).
        ('String q = "a\\""; // f();\n g();', "g()", "f()"),
    ]
    for source, must_keep, must_drop in cases:
        cleaned = strip_comments(source)
        if must_keep not in cleaned or must_drop in cleaned:
            print("FATAL: strip_comments is broken on %r -> %r" % (source, cleaned), file=sys.stderr)
            sys.exit(2)

    # The call extractor needs the same treatment, and for the same reason: an
    # extractor that finds nothing reports "identical on both" and waves every
    # divergence through. It shipped broken once - a mangled escape left a
    # literal backspace in the pattern, so it matched nothing and said the
    # loaders agreed.
    sample = "service.warmUp(); api.close(); other.thing(); config.load();"
    found = shared_calls(sample)
    if not {"service.warmUp", "api.close"} <= found or "other.thing" in found:
        print("FATAL: shared_calls is broken - got %r" % sorted(found), file=sys.stderr)
        sys.exit(2)


def read(relative):
    path = os.path.join(ROOT, relative)
    if not os.path.exists(path):
        print("FATAL: missing %s - nothing to check" % relative, file=sys.stderr)
        sys.exit(2)
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def check_shared_calls(fabric, neoforge):
    for call, consequence in REQUIRED_CALLS:
        in_fabric = call in fabric
        in_neoforge = call in neoforge

        if in_fabric and in_neoforge:
            continue

        missing = "NeoForge" if in_fabric else "Fabric"
        failures.append("%s never calls %s - %s" % (missing, call, consequence))


def shared_calls(source):
    """Every method called on a shared object, as "owner.method"."""
    return {owner + "." + method
            for owner, method in CALL_PATTERN.findall(source)
            if owner in SHARED_OWNERS}


def check_shared_call_sets(fabric, neoforge):
    """The two entrypoints must call the same things on the shared objects.

    Unlike REQUIRED_CALLS this needs no foresight: anything one side does to the
    shared logic and the other does not shows up here on its own. That is the
    difference between guarding the steps someone listed and guarding the steps
    that exist.
    """
    only_fabric = shared_calls(fabric) - shared_calls(neoforge)
    only_neoforge = shared_calls(neoforge) - shared_calls(fabric)

    for call in sorted(only_fabric):
        failures.append("%s() happens on Fabric but not on NeoForge" % call)
    for call in sorted(only_neoforge):
        failures.append("%s() happens on NeoForge but not on Fabric" % call)

    if not only_fabric and not only_neoforge:
        notes.append("shared calls compared: %d, identical on both" % len(shared_calls(fabric)))


def check_owner_list_current(fabric, neoforge, fabric_raw, neoforge_raw):
    """Keep SHARED_OWNERS from falling behind the code.

    A new shared type used by an entrypoint but absent from the list would be
    excluded from the comparison above - silently, and in the direction that
    makes the check pass. So an import that is not covered fails the run.

    Imports are read from the RAW source: they sit above the class and are not
    affected by comment stripping, and reading them here rather than guessing
    from usage is what makes this exact rather than approximate.
    """
    imported = set(IMPORT_PATTERN.findall(fabric_raw)) | set(IMPORT_PATTERN.findall(neoforge_raw))
    uncovered = imported - SHARED_OWNERS

    for name in sorted(uncovered):
        failures.append(
            "%s is imported from the shared modules but is not in SHARED_OWNERS - "
            "calls on it are not being compared between loaders" % name)


def check_default_key(fabric, neoforge):
    pattern = re.compile(r"GLFW_KEY_([A-Z0-9_]+)")
    fabric_keys = set(pattern.findall(fabric))
    neoforge_keys = set(pattern.findall(neoforge))

    if not fabric_keys or not neoforge_keys:
        failures.append("could not find a default key on at least one loader")
        return

    if fabric_keys != neoforge_keys:
        failures.append("default key diverged: Fabric %s, NeoForge %s"
                        % (sorted(fabric_keys), sorted(neoforge_keys)))
    else:
        notes.append("default key: %s" % sorted(fabric_keys)[0])


def check_loader_tags(fabric, neoforge):
    # Equality would be a defect here, not parity.
    if '"fabric"' not in fabric:
        failures.append('Fabric does not pass the "fabric" tag - upload stats would lie')
    if '"neoforge"' not in neoforge:
        failures.append('NeoForge does not pass the "neoforge" tag - upload stats would lie')
    if '"neoforge"' in fabric or '"fabric"' in neoforge:
        failures.append("a loader tag was copied into the wrong entrypoint")


def check_mixins():
    for entry, config in (
            (FABRIC_MIXIN, "fabric/src/main/resources/alascreen.mixins.json"),
            (NEOFORGE_MIXIN, "neoforge/src/main/resources/alascreen.mixins.json")):
        if not os.path.exists(os.path.join(ROOT, entry)):
            failures.append("missing mixin class %s - F2 would stop offering [share]" % entry)
        if not os.path.exists(os.path.join(ROOT, config)):
            failures.append("missing mixin config %s - the mixin would not apply, silently" % config)

    # NeoForge reads its mixin configs from mods.toml; one that is not declared
    # there simply never applies.
    #
    # The match has to run to the closing quote: a substring test would also
    # accept "alascreen.mixins.json.disabled", a file the loader cannot find.
    declared = re.compile(r'"alascreen\.mixins\.json"')

    if not declared.search(read(NEOFORGE_META)):
        failures.append("mixin not declared in neoforge.mods.toml - it would never apply, without an error")
    if not declared.search(read(FABRIC_META)):
        failures.append("mixin not declared in fabric.mod.json")


def check_client_only():
    if '"environment": "client"' not in read(FABRIC_META):
        failures.append("fabric.mod.json does not declare environment=client")
    if 'side = "CLIENT"' not in read(NEOFORGE_META):
        failures.append("neoforge.mods.toml does not declare side=CLIENT")


def main():
    _selftest()

    # Comments are dropped first: commented-out code does not run, and counting
    # it as a call would be guarding nothing.
    fabric_raw = read(FABRIC_ENTRY)
    neoforge_raw = read(NEOFORGE_ENTRY)

    reject_text_blocks(fabric_raw, FABRIC_ENTRY)
    reject_text_blocks(neoforge_raw, NEOFORGE_ENTRY)

    fabric = strip_comments(fabric_raw)
    neoforge = strip_comments(neoforge_raw)

    check_shared_calls(fabric, neoforge)
    check_shared_call_sets(fabric, neoforge)
    check_owner_list_current(fabric, neoforge, fabric_raw, neoforge_raw)
    check_default_key(fabric, neoforge)
    check_loader_tags(fabric, neoforge)
    check_mixins()
    check_client_only()

    for note in notes:
        print("  %s" % note)

    if failures:
        print()
        print("The entrypoints have diverged:")
        for failure in failures:
            print("  FAIL  %s" % failure)
        print()
        print("A mod whose loaders disagree is two mods sharing a name.")
        sys.exit(1)

    print("  shared steps checked: %d" % len(REQUIRED_CALLS))
    print()
    print("Loaders agree.")


if __name__ == "__main__":
    main()
