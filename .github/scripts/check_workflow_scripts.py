import subprocess, sys, yaml
bad = 0
for path in sys.argv[1:]:
    wf = yaml.safe_load(open(path))
    for job_name, job in wf["jobs"].items():
        for step in job.get("steps", []):
            script = step.get("run")
            if not script:
                continue
            r = subprocess.run(["bash", "-n"], input=script, text=True, capture_output=True)
            label = step.get("name") or script.splitlines()[0][:50]
            if r.returncode:
                bad += 1
                print(f"SYNTAX ERROR {path} · {job_name} · {label}: {r.stderr.strip()}")
print("run blocks checked; errors:", bad)
sys.exit(1 if bad else 0)
