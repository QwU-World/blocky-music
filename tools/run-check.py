#!/usr/bin/env python3
"""
Runs one of the tools/check-*.ps1 regressions on macOS or Linux, without PowerShell.

Every check script is the same shape underneath: build a classpath, javac a harness from tools/,
run its main class. Only two things in them are Windows-specific, the JDK path and $env:USERPROFILE,
so this reads the .ps1, lifts out the sources, the classpath groups, the main class and its modes,
and runs the same thing with a JDK 21.

The JDK is looked for in this order: BLOCKY_JDK, JAVA_HOME, the JDK Gradle provisioned for the
toolchain (under jdks/ in GRADLE_USER_HOME or ~/.gradle), `/usr/libexec/java_home -v 21` on macOS,
`java` on PATH. BLOCKY_JDK is taken as given: if it is not a JDK 21 the run stops and says what it
found. The other places are passed over (with a note for JAVA_HOME) until one reports major version
21; if none does, the run stops and lists what it found. The libraries come from Gradle's cache
(GRADLE_USER_HOME or ~/.gradle), so build once with `gradle compileJava` first. LWJGL's natives are
picked for this machine: macOS or Linux, x86-64 or arm64.

    python3 tools/run-check.py dj-workflow
    python3 tools/run-check.py client-defaults --packaged
    python3 tools/run-check.py battle-placement --dry-run
    python3 tools/run-check.py all              # every check-*.ps1, summary at the end
"""
import glob
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
GRADLE_HOME = Path(os.environ["GRADLE_USER_HOME"]).expanduser() if os.environ.get("GRADLE_USER_HOME") \
    else Path.home() / ".gradle"
CACHE = GRADLE_HOME / "caches" / "modules-2" / "files-2.1"
DARWIN = sys.platform == "darwin"


def natives_classifier() -> str:
    """LWJGL's classifier for this machine's natives: natives-macos[-arm64] or natives-linux[-arm64]."""
    arm = platform.machine().lower() in ("arm64", "aarch64")
    if DARWIN:
        return "natives-macos-arm64" if arm else "natives-macos"
    if sys.platform.startswith("linux"):
        return "natives-linux-arm64" if arm else "natives-linux"
    sys.exit(f"run-check.py runs on macOS and Linux; on {sys.platform} use the tools/check-*.ps1 scripts")


NATIVES = natives_classifier()


def java_major(bin_: Path):
    """The major version `java -version` reports for this bin folder, or None if it does not run."""
    try:
        out = subprocess.run([str(bin_ / "java"), "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out.stderr + out.stdout)
    if not m:
        return None
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 and m.group(2) else major


def jdk_candidates():
    """(where it came from, bin folder) in the order run-check tries them."""
    for var in ("BLOCKY_JDK", "JAVA_HOME"):
        if os.environ.get(var):
            yield var, Path(os.environ[var]).expanduser() / "bin"
    for pattern in ("jdks/*/jdk-21*/Contents/Home/bin", "jdks/*/jdk-21*/bin", "jdks/*21*/Contents/Home/bin",
                    "jdks/*21*/bin"):
        for candidate in sorted(glob.glob(str(GRADLE_HOME / pattern))):
            yield f"Gradle's {GRADLE_HOME / 'jdks'}", Path(candidate)
    if DARWIN and Path("/usr/libexec/java_home").exists():
        home = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True).stdout.strip()
        if home:
            yield "java_home -v 21", Path(home) / "bin"
    on_path = shutil.which("java")
    if on_path:
        yield "java on PATH", Path(os.path.realpath(on_path)).parent


_JDK = None


def jdk_bin() -> Path:
    """
    The first JDK candidate that is a JDK 21. BLOCKY_JDK is taken as given, so one that is not 21 stops the run. The
    other places are passed over until one is 21: JAVA_HOME often names a newer JDK set for other work, and
    java_home -v 21 answers with a newer JDK when no 21 is installed.
    """
    global _JDK
    if _JDK is not None:
        return _JDK
    seen = []
    for source, bin_ in jdk_candidates():
        if not (bin_ / "java").exists() or not (bin_ / "javac").exists():
            if source == "BLOCKY_JDK":
                sys.exit(f"BLOCKY_JDK points at {bin_.parent}, which has no bin/java and bin/javac")
            seen.append(f"{source}: {bin_.parent} (no bin/java and bin/javac)")
            continue
        major = java_major(bin_)
        if major == 21:
            _JDK = bin_
            return bin_
        if source == "BLOCKY_JDK":
            sys.exit(f"BLOCKY_JDK points at {bin_.parent}, which is Java {major}; the checks need JDK 21")
        if source == "JAVA_HOME":
            print(f"note: JAVA_HOME is Java {major}, not 21; looking further", file=sys.stderr)
        seen.append(f"{source}: {bin_.parent} (Java {major})")
    sys.exit("no JDK 21 found" + (" (found only " + "; ".join(seen) + ")" if seen else "")
             + ". Set BLOCKY_JDK or JAVA_HOME to a JDK 21, or run `gradle compileJava` once so Gradle provisions one")


def packaged_jar() -> Path:
    """build/libs/blocky_music-<mod_version>.jar, the version read from gradle.properties as Gradle names the jar."""
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("mod_version="):
            return ROOT / "build" / "libs" / f"blocky_music-{line.split('=', 1)[1].strip()}.jar"
    sys.exit("no mod_version in gradle.properties")


def posix(rel: str) -> str:
    return rel.replace("\\", "/").strip("'\"")


def version_key(text: str):
    return tuple(int(p) if p.isdigit() else -1 for p in re.split(r"[.\-_+]", text))


def cache_jars(groups):
    """
    One jar per artifact, the newest cached version. The cache layout is
    files-2.1/<group>/<artifact>/<version>/<hash>/<jar>; several projects share it, so an older
    DataFixerUpper or Guava from another mod sits next to the one NeoForge 21.1 wants, and
    whichever the filesystem lists first would win the classpath.
    """
    newest = {}
    for group in groups:
        base = CACHE / group
        if not base.is_dir():
            continue
        for jar in base.rglob("*.jar"):
            if re.search(r"-sources|-javadoc|-natives-", jar.name):
                continue
            parts = jar.relative_to(CACHE).parts
            if len(parts) < 4:
                continue
            key = (parts[0], parts[1], jar.name.replace(parts[2], ""))
            version = version_key(parts[2])
            if key not in newest or version > newest[key][0]:
                newest[key] = (version, str(jar))
    return [path for _, path in newest.values()]


def native_jars(version="3.3.3"):
    return [str(p) for p in (CACHE / "org.lwjgl").rglob(f"*-{version}-{NATIVES}.jar")]


class Check:
    def __init__(self, name: str, packaged: bool):
        self.name = name
        self.script = TOOLS / f"check-{name}.ps1"
        if not self.script.is_file():
            sys.exit(f"no such check: {self.script.name}")
        self.text = self.script.read_text(encoding="utf-8")
        self.packaged = packaged
        self.out = ROOT / "build" / f"{name}-checks"
        self.parse()

    def parse(self):
        t = self.text
        m = re.search(r"Join-Path\s+\$(?:root|projectDir|projectRoot)\s+'([^']+)'", t)
        if m and "build" in m.group(1):
            self.out = ROOT / posix(m.group(1))

        # sources: Join-Path forms, "$root/tools/X.java" forms, and explicit @sources arrays
        sources = []
        for rel in re.findall(r"Join-Path\s+\$(?:root|projectDir|projectRoot)\s+'([^']+\.java)'", t):
            sources.append(ROOT / posix(rel))
        for rel in re.findall(r"Join-Path\s+\$PSScriptRoot\s+'([^']+\.java)'", t):
            sources.append(TOOLS / posix(rel))
        # "$projectDir/src/.../X.java", '$root/tools/Y.java', "$PSScriptRoot/Z.java": the scripts use
        # every combination of the four root variables and both quote styles.
        for var, rel in re.findall(r"[\"']\$(root|projectDir|projectRoot|PSScriptRoot)/([^\"']+\.java)[\"']", t):
            if "$" in rel:
                continue  # a loop variable; the foreach below expands it
            sources.append((TOOLS if var == "PSScriptRoot" else ROOT) / posix(rel))
        # foreach ($name in @('a','b')) { $sources += "$root/.../$name.java" } — the loop variable
        # and the root variable are both spelled differently from one script to the next.
        for var, values, base, template in re.findall(
                r"foreach\s*\(\s*\$(\w+)\s+in\s+@\(([^)]*)\)\s*\)\s*\{\s*\$sources\s*\+=\s*"
                r"[\"']\$(\w+)/([^\"']+)[\"']", t, re.S):
            root = TOOLS if base == "PSScriptRoot" else ROOT
            for value in re.findall(r"'([^']+)'", values):
                sources.append(root / posix(template.replace(f"${var}", value)))
        # foreach ($name in @('A','B')) { $source = Join-Path $projectDir "src/.../$name.java" ... }
        # The path is built through a temporary, so the direct handler above never sees it.
        for var, values, body in re.findall(r"foreach\s*\(\s*\$(\w+)\s+in\s+@\(([^)]*)\)\s*\)\s*\{(.*?)\}", t, re.S):
            names = re.findall(r"'([^']+)'", values)
            if not names:
                continue
            templates = [(base, rel) for base, rel in
                         re.findall(r"Join-Path\s+\$(root|projectDir|projectRoot|PSScriptRoot)\s+[\"']([^\"']+\.java)[\"']", body)]
            templates += [(base, rel) for base, rel in
                          re.findall(r"[\"']\$(root|projectDir|projectRoot|PSScriptRoot)/([^\"']+\.java)[\"']", body)]
            for base, rel in templates:
                if f"${var}" not in rel:
                    continue
                root = TOOLS if base == "PSScriptRoot" else ROOT
                for name in names:
                    sources.append(root / posix(rel.replace(f"${var}", name)))
        for block in re.findall(r"\$(?:sources|sourcePaths|sourceFiles)\s*=\s*@\(([^)]*)\)", t, re.S):
            for rel in re.findall(r"'([^']+\.java)'", block):
                p = posix(rel)
                sources.append((TOOLS if not p.startswith(("src/", "tools/")) else ROOT) / p)
        seen = set()
        self.sources = [s for s in sources if not (s in seen or seen.add(s))]

        self.groups = []
        m = re.search(r"\$groups\s*=\s*@\(([^)]*)\)", t, re.S)
        if m:
            self.groups = re.findall(r"'([^']+)'", m.group(1))
        # Some scripts never name a $groups array and loop over the group ids inline instead.
        for values in re.findall(r"foreach\s*\(\s*\$group\s+in\s+@\(([^)]*)\)\s*\)", t, re.S):
            for group in re.findall(r"'([^']+)'", values):
                if group not in self.groups:
                    self.groups.append(group)
        # A group picked out of the cache by hand (rg --files (Join-Path $dependencyRoot 'org.lwjgl')).
        for group in re.findall(r"Join-Path\s+\$dependencyRoot\s+'([^']+)'", t):
            if group not in self.groups:
                self.groups.append(group)
        # A single jar fished out of the cache by its full path: .../files-2.1/<group>/<artifact>/<version>.
        for group in re.findall(r"files-2\.1[\\/]([A-Za-z0-9_.\-]+)", t):
            if group not in self.groups:
                self.groups.append(group)
        # A few scripts leave org.lwjgl out of $groups and pick those jars by hand with rg.
        if "lwjgl" in t and "org.lwjgl" not in self.groups:
            self.groups = self.groups + ["org.lwjgl"]

        self.drop = set(re.findall(r"-ne\s+'([^']+\.jar)'", t))
        self.artifacts = [ROOT / posix(a) for a in
                          re.findall(r"build[\\/]moddev[\\/]artifacts[\\/][^\"'\s)]+\.jar", t)]
        self.wants_natives = "natives-windows" in t or "lwjgl" in t
        self.uses_classes = "build/classes/java/main" in t or "build\\classes\\java\\main" in t or "$classes" in t
        self.jar = packaged_jar()

        # The run line, in every shape the scripts use: optional JVM flags, a -cp that is either a
        # variable or a parenthesised expression, then either a class name (possibly fully
        # qualified) or a .java file for the single-file source launcher.
        m = re.search(
            r"java(?:\.exe)?[\"')]*\s+((?:-[^\s]+\s+)*)(?:-cp|--class-path)\s+(?:\$\w+|\([^)]*\))\s+"
            r"(\$\w+|[A-Za-z_][\w.]*|\(?Join-Path[^)]*\)|\"[^\"]+\.java\")([^\n`]*)", t)
        if not m:
            sys.exit(f"{self.script.name}: could not find the java run line")
        self.jvm_flags = [f for f in m.group(1).split() if f]
        raw_main = m.group(2)
        self.main_is_loop_var = raw_main.startswith("$")
        self.source_launcher = ".java" in raw_main
        if self.source_launcher:
            rel = re.search(r"'([^']+\.java)'|\"([^\"]+\.java)\"", raw_main)
            name = posix((rel.group(1) or rel.group(2)) if rel else raw_main)
            self.main = str(TOOLS / name if "/" not in name else ROOT / name)
        else:
            self.main = raw_main
        tail = m.group(3).strip()
        # `(Join-Path $outputDir 'isolated-games')` is one argument, not three tokens. Fold it into a
        # single quoted token before splitting, keeping the variable so resolve_arg still sees it.
        tail = re.sub(r"\(\s*Join-Path\s+(\$\w+)\s+'([^']+)'\s*\)", r"\1/\2", tail)
        raw_args = re.findall(r"@\w+|\$\w+/[^\s)]+|\$\w+|'[^']*'|\"[^\"]*\"|\S+", tail)
        self.args = []
        for a in raw_args:
            if not a.startswith("@"):
                self.args.append(a)
                continue
            # `@arguments` splats an array the script built: $arguments = @($root). Anything added to it
            # conditionally (if ($Packaged) { $arguments += 'packaged' }) is handled by --packaged below.
            block = re.search(r"\$" + a[1:] + r"\s*=\s*@\(([^)]*)\)", t)
            if block:
                self.args += [x for x in re.findall(r"\$\w+|'[^']*'|\"[^\"]*\"", block.group(1)) if x]
            if self.packaged:
                for added in re.findall(r"\$" + a[1:] + r"\s*\+=\s*'([^']+)'", t):
                    self.args.append("'" + added + "'")
        self.modes = []
        mm = re.search(r"foreach\s*\(\s*\$mode\s+in\s+@\(([^)]*)\)\s*\)", t)
        if mm:
            self.modes = re.findall(r"'([^']+)'", mm.group(1))
        # foreach ($main in @('a.B','c.D')) { java ... $main } — several main classes, one compile.
        self.mains = []
        mn = re.search(r"foreach\s*\(\s*\$main\s+in\s+@\(([^)]*)\)\s*\)", t)
        if mn:
            self.mains = re.findall(r"'([^']+)'", mn.group(1))
        if self.main_is_loop_var and not self.mains:
            sys.exit(f"{self.script.name}: the main class comes from {raw_main}, which is not a list I can read")

    def classpath(self):
        cp = [str(self.out)]
        if self.uses_classes:
            cp.append(str(self.jar) if self.packaged else str(ROOT / "build/classes/java/main"))
        cp += [str(a) for a in self.artifacts]
        jars = [j for j in cache_jars(self.groups) if Path(j).name not in self.drop]
        cp += jars
        if self.wants_natives:
            cp += native_jars()
        return cp

    def resolve_arg(self, a: str):
        """A script argument as a real value, or None when only the Windows machine knew it."""
        if a == "$mode":
            return a
        if a in ("$root", "$projectRoot", "$projectDir"):
            return str(ROOT)
        if a == "$PSScriptRoot":
            return str(TOOLS)
        # The scripts hand their own build directory to the harness under several names, and one hands
        # the compiled mod classes. Both are things this runner already knows.
        if a in ("$outputDir", "$out", "$outDir"):
            return str(self.out)
        # `$outputDir/isolated-games`, folded from a Join-Path above.
        prefix = re.match(r"^(\$\w+)/(.+)$", a)
        if prefix:
            base = self.resolve_arg(prefix.group(1))
            return None if base is None else str(Path(base) / posix(prefix.group(2)))
        if a in ("$compiled", "$classes"):
            return str(self.jar if self.packaged else ROOT / "build/classes/java/main")
        # A value the script sets from the project root, such as a default fixture:
        # if (-not $TrackPath) { $TrackPath = Join-Path $projectRoot 'tools/fixtures/...' }
        if a.startswith("$"):
            m = re.search(r"\$" + re.escape(a[1:]) + r"\s*=\s*Join-Path\s+\$(?:root|projectRoot|projectDir)\s+'([^']+)'",
                          self.text)
            if m:
                return str(ROOT / posix(m.group(1)))
        if a.startswith("$"):
            return None
        return posix(a)

    def run(self, dry: bool) -> int:
        bin_ = jdk_bin()
        cp = self.classpath()
        javac = [str(bin_ / "javac"), "-proc:none", "-encoding", "UTF-8", "-d", str(self.out)]
        if len(cp) > 1:
            javac += ["-cp", os.pathsep.join(cp)]
        javac += [str(s) for s in self.sources]
        java = [str(bin_ / "java")]
        if DARWIN:
            java.append("-XstartOnFirstThread")
        java += self.jvm_flags + ["-Djava.awt.headless=true", "-cp", os.pathsep.join(cp)]
        mains = self.mains or [self.main]
        runs = [[self.resolve_arg(a) if a != "$mode" else mode for a in self.args]
                for mode in (self.modes or [None])]
        if dry:
            print(f"# {self.name}")
            print(f"  out      {self.out}")
            print(f"  sources  {[str(s.relative_to(ROOT)) for s in self.sources]}")
            print(f"  groups   {self.groups}  natives={self.wants_natives}  classes={self.uses_classes}")
            print(f"  cp jars  {len(cp)}   jvm={self.jvm_flags or '-'}")
            print(f"  main     {mains} {self.args}  modes={self.modes or '-'}"
                  + ("  [single-file launcher]" if self.source_launcher else ""))
            return 0
        missing = {a for run_args in runs for a, raw in zip(run_args, self.args) if a is None}
        if missing:
            unknown = [raw for raw in self.args if self.resolve_arg(raw) is None and raw != "$mode"]
            print(f"SKIP {self.name}: its script passes {' '.join(unknown)}, which it sets from a parameter or the"
                  " Windows environment and this runner cannot work out; run the .ps1 with that value instead")
            return -1
        self.out.mkdir(parents=True, exist_ok=True)
        if not self.source_launcher:
            r = subprocess.run(javac)
            if r.returncode:
                print(f"FAIL {self.name}: compile")
                return r.returncode
        worst = 0
        for main in mains:
            for run_args in runs:
                label = self.name + (f" [{run_args[0]}]" if self.modes else "")
                label += f" [{main.rsplit('.', 1)[-1]}]" if len(mains) > 1 else ""
                r = subprocess.run(java + [main] + [a for a in run_args if a is not None])
                print(("PASS " if r.returncode == 0 else f"FAIL({r.returncode}) ") + label)
                worst = max(worst, r.returncode)
        return worst


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    packaged = "--packaged" in argv
    dry = "--dry-run" in argv
    names = [a for a in argv if not a.startswith("--")]
    if not dry:
        jdk = jdk_bin()  # stops here, with the reason, when there is no JDK 21
        print(f"JDK 21: {jdk.parent}", flush=True)
        if not CACHE.is_dir():
            sys.exit(f"no Gradle cache at {CACHE}; run `gradle compileJava` once (set GRADLE_USER_HOME if Gradle keeps"
                     " its cache elsewhere)")
    if names == ["all"]:
        names = sorted(p.name[len("check-"):-len(".ps1")] for p in TOOLS.glob("check-*.ps1"))
    results = {}
    for name in names:
        try:
            results[name] = Check(name, packaged).run(dry)
        except SystemExit as e:
            print(f"SKIP {name}: {e}")
            results[name] = -1
    if len(results) > 1 and not dry:
        passed = sum(1 for v in results.values() if v == 0)
        print(f"\n{passed}/{len(results)} passed")
        for name, code in results.items():
            if code:
                print(f"  {'skip' if code < 0 else 'FAIL'}  {name}")
    return max(results.values()) if results else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
