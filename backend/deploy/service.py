#!/usr/bin/env python3
"""Deploys the Aram backend as the Cloud Run service `aram-backend` (README 3f). Standard library only; REST because the gcloud
run commands crash on Python 3.9. Run from backend/:  python3 deploy/service.py <step> [--origins a,b]

Steps (in order):  plan | secret-access | deploy | public | url | status
  plan           prints what each step does; calls nothing
  secret-access  lets the service account read the YouTube key secret (aram-youtube-api-key)
  deploy         creates or updates the service (re-run after pushing a new image, or with different --origins)
  public         allows unauthenticated HTTPS calls to the service URL; the API itself rejects any request without a valid
                 Firebase ID token (only /api/tags is public), so browsers can call it directly
  url / status   print the service address / health
"""
import json, sys, urllib.request

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from jobs import P, R, RUN, add_member, call, sa, wait  # noqa: E402

NAME = 'aram-backend'
ACCOUNT = 'aram-backend'
IMAGE = f'{R}-docker.pkg.dev/{P}/aram/aram-backend:20261017-4'
DEFAULT_ORIGINS = 'http://localhost:5173'


def origins_arg():
    if '--origins' in sys.argv:
        return sys.argv[sys.argv.index('--origins') + 1]
    return DEFAULT_ORIGINS


def body(origins):
    env = [{'name': 'GOOGLE_CLOUD_PROJECT', 'value': P},
           {'name': 'ARAM_ALLOWED_ORIGINS', 'value': origins},
           {'name': 'ARAM_ML_BLEND_ENABLED', 'value': 'true'},
           {'name': 'ARAM_AGENT_ENGINE', 'value': 'adk'},
           {'name': 'ARAM_AGENT_SESSIONS_ENABLED', 'value': 'true'},
           {'name': 'YOUTUBE_API_KEY', 'valueSource': {'secretKeyRef': {'secret': 'aram-youtube-api-key', 'version': 'latest'}}}]
    return {'ingress': 'INGRESS_TRAFFIC_ALL',
            'template': {'serviceAccount': sa(ACCOUNT), 'timeout': '120s', 'maxInstanceRequestConcurrency': 40,
                         'scaling': {'minInstanceCount': 0, 'maxInstanceCount': 3},
                         'containers': [{'image': IMAGE, 'env': env, 'ports': [{'containerPort': 8080}],
                                         'resources': {'limits': {'cpu': '1', 'memory': '1Gi'}, 'cpuIdle': True, 'startupCpuBoost': True}}]}}


def step_plan():
    print(f'Image {IMAGE}\nService {NAME} in {R}, runs as {sa(ACCOUNT)} (Firestore user, Vertex AI user), 1 CPU (billed only while handling requests) / 1 GiB, 0-3 instances,')
    print(f'request timeout 120 s, CORS origins: {origins_arg()}')
    print('Env: GOOGLE_CLOUD_PROJECT, ARAM_ALLOWED_ORIGINS, ARAM_ML_BLEND_ENABLED=true, ARAM_AGENT_ENGINE=adk, ARAM_AGENT_SESSIONS_ENABLED=true; YOUTUBE_API_KEY from Secret Manager')
    print('public: allUsers may invoke the URL; every /api/me/... call still needs a verified Firebase token')


def step_secret_access():
    changed = add_member(f'https://secretmanager.googleapis.com/v1/projects/{P}/secrets/aram-youtube-api-key',
                         'roles/secretmanager.secretAccessor', 'serviceAccount:' + sa(ACCOUNT))
    print('secret access granted' if changed else 'secret access already granted')


def step_deploy():
    status, op = call('POST', f'{RUN}/services?serviceId={NAME}', body(origins_arg()), tolerate=(409,))
    if status == 409:
        status, op = call('PATCH', f'{RUN}/services/{NAME}', body(origins_arg()))
        print('updating the service...')
    else:
        print('creating the service...')
    wait(op)
    step_url()


def step_public():
    changed = add_member(f'{RUN}/services/{NAME}', 'roles/run.invoker', 'allUsers')
    print('now public' if changed else 'already public')


def service_url():
    return call('GET', f'{RUN}/services/{NAME}')[1].get('uri')


def step_url():
    print('Service URL:', service_url())


def step_status():
    url = service_url()
    print('Service URL:', url)
    try:
        with urllib.request.urlopen(url + '/api/tags', timeout=30) as r:
            print('GET /api/tags ->', r.status, '(public endpoint answers)')
    except Exception as e:  # noqa: BLE001
        print('GET /api/tags failed:', e)
    try:
        urllib.request.urlopen(url + '/api/me/profile', timeout=30)
        print('WARNING: /api/me/profile answered without a token')
    except urllib.error.HTTPError as e:
        print('GET /api/me/profile without a token ->', e.code, '(expected 401)')


STEPS = {'plan': step_plan, 'secret-access': step_secret_access, 'deploy': step_deploy, 'public': step_public, 'url': step_url,
         'status': step_status}

if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if '--origins' in sys.argv:
        args = [a for a in args if a != origins_arg()]
    if len(args) == 1 and args[0] in STEPS:
        STEPS[args[0]]()
    else:
        sys.exit(__doc__)
