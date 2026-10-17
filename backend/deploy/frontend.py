#!/usr/bin/env python3
"""Deploys the Aram web app as the Cloud Run service `aram-frontend` (README 3f). Standard library only. Run from backend/:
    python3 deploy/frontend.py <step>

Steps (in order):  plan | deploy | public | url | authorize-domain | status
  deploy            creates or updates the service from the image built from frontend/ (static files served by nginx)
  public            lets anyone open the site (it is a public web app; every data call still needs a Firebase sign-in)
  authorize-domain  adds the service's host to Firebase Authentication's authorized domains, which Google sign-in requires
  url / status      print the address / check that it answers

Afterwards allow the new address in the backend's CORS list:
    python3 deploy/service.py deploy --origins http://localhost:5173,<the frontend url>
"""
import sys
import urllib.request
from urllib.parse import urlparse

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from jobs import P, R, RUN, add_member, call, wait  # noqa: E402

NAME = 'aram-frontend'
IMAGE = f'{R}-docker.pkg.dev/{P}/aram/aram-frontend:20261017-10'
AUTH_CONFIG = f'https://identitytoolkit.googleapis.com/admin/v2/projects/{P}/config'


def step_plan():
    print(f'Image {IMAGE}\nService {NAME} in {R}: nginx serving the built app, 1 CPU (billed only while handling requests) / 512 MiB, 0-3 instances, public.')
    print('authorize-domain: adds the service host to Firebase Authentication authorized domains (needed for Google sign-in).')


def step_deploy():
    body = {'ingress': 'INGRESS_TRAFFIC_ALL',
            'template': {'timeout': '30s', 'maxInstanceRequestConcurrency': 80, 'scaling': {'minInstanceCount': 0, 'maxInstanceCount': 3},
                         'containers': [{'image': IMAGE, 'ports': [{'containerPort': 8080}],
                                         'resources': {'limits': {'cpu': '1', 'memory': '512Mi'}, 'cpuIdle': True}}]}}
    status, op = call('POST', f'{RUN}/services?serviceId={NAME}', body, tolerate=(409,))
    if status == 409:
        status, op = call('PATCH', f'{RUN}/services/{NAME}', body)
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
    print('Web app URL:', service_url())


def step_authorize_domain():
    host = urlparse(service_url()).hostname
    headers = {'X-Goog-User-Project': P}
    _, config = call('GET', AUTH_CONFIG, headers=headers)
    domains = config.get('authorizedDomains', [])
    if host in domains:
        print(host, 'is already authorized')
        return
    call('PATCH', AUTH_CONFIG + '?updateMask=authorizedDomains', {'authorizedDomains': domains + [host]}, headers=headers)
    print('authorized', host)


def step_status():
    url = service_url()
    print('Web app URL:', url)
    with urllib.request.urlopen(url + '/', timeout=30) as r:
        html = r.read().decode()
    print('GET / ->', r.status, '(app shell served)' if '<div id="root">' in html else '(unexpected content)')
    with urllib.request.urlopen(url + '/parent/channels', timeout=30) as r:
        print('GET /parent/channels ->', r.status, '(deep links fall back to the app)')


STEPS = {'plan': step_plan, 'deploy': step_deploy, 'public': step_public, 'url': step_url, 'authorize-domain': step_authorize_domain,
         'status': step_status}

if __name__ == '__main__':
    if len(sys.argv) == 2 and sys.argv[1] in STEPS:
        STEPS[sys.argv[1]]()
    else:
        sys.exit(__doc__)
