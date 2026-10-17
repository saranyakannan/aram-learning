#!/usr/bin/env python3
"""Deploys Aram's three scheduled Cloud Run jobs (README 3f). Standard library only; calls Google's REST APIs because the
gcloud run/scheduler commands crash on Python 3.9. Run from backend/:  python3 deploy/jobs.py <step>

Steps (in order):  plan | secret | jobs | iam | schedule | run <job> | resume | status
Nothing here prints a secret. Every step is safe to repeat. Project is always sproutly-508503.
"""
import base64, json, re, subprocess, sys, time, urllib.error, urllib.request

P = 'sproutly-508503'
R = 'us-central1'
IMAGE = f'{R}-docker.pkg.dev/{P}/aram/aram-backend:20261017-4'
RUN = f'https://run.googleapis.com/v2/projects/{P}/locations/{R}'
SCHED = f'https://cloudscheduler.googleapis.com/v1/projects/{P}/locations/{R}'
SECRETS = f'https://secretmanager.googleapis.com/v1/projects/{P}/secrets'
COMMON = {'SPRING_MAIN_WEB_APPLICATION_TYPE': 'none', 'GOOGLE_CLOUD_PROJECT': P}

# name -> (service account, extra env, [(env var, secret)], schedule, time zone, scheduler job name)
JOBS = {
    'aram-analytics-export': ('aram-analytics-export', {'ARAM_EXPORT_ENABLED': 'true'},
                              [('ANALYTICS_HMAC_KEY', 'aram-analytics-hmac-key')], '0 2 * * *', 'aram-analytics-export-daily'),
    'aram-recommendations': ('aram-recommendations', {'ARAM_ML_ENABLED': 'true'}, [], '0 3 * * *',
                             'aram-recommendations-daily'),
    'aram-catalog-refresh': ('aram-catalog-refresh', {'ARAM_REFRESH_ENABLED': 'true'},
                             [('YOUTUBE_API_KEY', 'aram-youtube-api-key')], '0 4 * * 0', 'aram-catalog-refresh-weekly'),
}
TZ = 'Asia/Kolkata'


def sa(name):
    return f'{name}@{P}.iam.gserviceaccount.com'


def token():
    return subprocess.run(['gcloud', 'auth', 'print-access-token'], capture_output=True, text=True, check=True).stdout.strip()


def call(method, url, body=None, ok=(200,), tolerate=(), headers=None):
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Authorization': 'Bearer ' + token(), 'Content-Type': 'application/json', **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        if e.code in tolerate:
            return e.code, {}
        sys.exit(f'HTTP {e.code} from {method} {url.split("?")[0]}\n{text[:600]}')


def wait(op, minutes=20):
    deadline = time.time() + minutes * 60
    while not op.get('done'):
        if time.time() > deadline:
            sys.exit('Timed out waiting for the operation')
        time.sleep(5)
        op = call('GET', 'https://run.googleapis.com/v2/' + op['name'])[1]
    if 'error' in op:
        sys.exit('Operation failed: ' + json.dumps(op['error'])[:500])
    return op


def add_member(url_base, role, member):
    """Adds a role binding to a resource's IAM policy (read, merge, write)."""
    _, policy = call('GET', url_base + ':getIamPolicy')
    bindings = policy.setdefault('bindings', [])
    for b in bindings:
        if b['role'] == role and member in b.get('members', []):
            return False
    for b in bindings:
        if b['role'] == role and 'condition' not in b:
            b['members'].append(member)
            break
    else:
        bindings.append({'role': role, 'members': [member]})
    call('POST', url_base + ':setIamPolicy', {'policy': policy})
    return True


def step_plan():
    print(f'Image: {IMAGE}')
    for name, (acct, env, secrets, cron, sched) in JOBS.items():
        print(f'\nJob {name}: runs as {sa(acct)}, env {env}, secrets {[s for _, s in secrets]}, retries 1, timeout 15 min')
        print(f'  scheduler {sched}: "{cron}" {TZ} (created paused), invoker role on this job only')
    print(f'\nSecret aram-youtube-api-key: created from the youtube api-key in backend/config/application.yml (value never printed);'
          f' accessor: {sa("aram-catalog-refresh")}')


def step_secret():
    text = open('config/application.yml').read()
    m = re.search(r'youtube:\s*\n(?:\s+#.*\n)*\s+api-key:\s*["\']?([^\s"\']+)', text)
    if not m:
        sys.exit('No youtube api-key found in config/application.yml')
    body = {'replication': {'userManaged': {'replicas': [{'location': R}]}}}
    status, _ = call('POST', f'{SECRETS}?secretId=aram-youtube-api-key', body, tolerate=(409,))
    print('secret created' if status == 200 else 'secret already exists')
    _, versions = call('GET', f'{SECRETS}/aram-youtube-api-key/versions')
    if versions.get('versions'):
        print('a version already exists; not adding another')
    else:
        data = base64.b64encode(m.group(1).encode()).decode()
        call('POST', f'{SECRETS}/aram-youtube-api-key:addVersion', {'payload': {'data': data}})
        print('version added (value not shown)')
    changed = add_member(f'{SECRETS}/aram-youtube-api-key', 'roles/secretmanager.secretAccessor', 'serviceAccount:' + sa('aram-catalog-refresh'))
    print('accessor granted to aram-catalog-refresh' if changed else 'accessor already granted')


def job_body(acct, env, secrets):
    envs = [{'name': k, 'value': v} for k, v in {**COMMON, **env}.items()]
    envs += [{'name': var, 'valueSource': {'secretKeyRef': {'secret': s, 'version': 'latest'}}} for var, s in secrets]
    return {'template': {'template': {'containers': [{'image': IMAGE, 'env': envs, 'resources': {'limits': {'cpu': '1', 'memory': '2Gi'}}}],
                                      'maxRetries': 1, 'timeout': '900s', 'serviceAccount': sa(acct)}}}


def step_jobs():
    for name, (acct, env, secrets, _, _) in JOBS.items():
        body = job_body(acct, env, secrets)
        status, op = call('POST', f'{RUN}/jobs?jobId={name}', body, tolerate=(409,))
        if status == 409:
            status, op = call('PATCH', f'{RUN}/jobs/{name}', body)
            print(f'{name}: updated')
        else:
            print(f'{name}: created')
        wait(op)


def step_iam():
    for name, (acct, *_rest) in JOBS.items():
        changed = add_member(f'{RUN}/jobs/{name}', 'roles/run.invoker', 'serviceAccount:' + sa(acct))
        print(f'{name}: invoker {"granted" if changed else "already granted"}')


def step_schedule():
    for name, (acct, _env, _secrets, cron, sched) in JOBS.items():
        body = {'name': f'projects/{P}/locations/{R}/jobs/{sched}', 'schedule': cron, 'timeZone': TZ,
                'httpTarget': {'uri': f'{RUN}/jobs/{name}:run', 'httpMethod': 'POST',
                               'oauthToken': {'serviceAccountEmail': sa(acct), 'scope': 'https://www.googleapis.com/auth/cloud-platform'}}}
        status, _ = call('POST', f'{SCHED}/jobs', body, tolerate=(409,))
        if status == 409:
            call('PATCH', f'{SCHED}/jobs/{sched}?updateMask=schedule,timeZone,httpTarget', body)
            print(f'{sched}: updated (state unchanged)')
        else:
            call('POST', f'{SCHED}/jobs/{sched}:pause', {})
            print(f'{sched}: created, PAUSED')


def step_run(name):
    if name not in JOBS:
        sys.exit('Unknown job. Choose one of: ' + ', '.join(JOBS))
    _, op = call('POST', f'{RUN}/jobs/{name}:run', {})
    print(f'{name}: started, waiting (up to 20 minutes)...')
    op = wait(op)
    ex = op.get('response', {})
    ok, bad = int(ex.get('succeededCount', 0)), int(ex.get('failedCount', 0))
    print(f'{name}: succeeded={ok} failed={bad}' + (f'\nexecution: {ex.get("name")}' if ex.get('name') else ''))
    if bad or not ok:
        sys.exit(f'{name} did not succeed. Read its logs in Cloud Run > Jobs > {name} > Logs.')


def step_resume():
    for _, (_a, _e, _s, _c, sched) in JOBS.items():
        call('POST', f'{SCHED}/jobs/{sched}:resume', {})
        print(f'{sched}: resumed')


def step_status():
    for name, (*_x, sched) in JOBS.items():
        s, job = call('GET', f'{RUN}/jobs/{name}', tolerate=(404,))
        _, sj = call('GET', f'{SCHED}/jobs/{sched}', tolerate=(404,))
        print(f'{name}: {"exists" if s == 200 else "MISSING"}; {sched}: {sj.get("state", "MISSING")} {sj.get("schedule", "")}')


if __name__ == '__main__':
    args = sys.argv[1:]
    steps = {'plan': step_plan, 'secret': step_secret, 'jobs': step_jobs, 'iam': step_iam, 'schedule': step_schedule,
             'resume': step_resume, 'status': step_status}
    if args and args[0] == 'run' and len(args) == 2:
        step_run(args[1])
    elif len(args) == 1 and args[0] in steps:
        steps[args[0]]()
    else:
        sys.exit(__doc__)
