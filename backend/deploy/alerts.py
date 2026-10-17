#!/usr/bin/env python3
"""Cloud Monitoring alert for the three scheduled Cloud Run jobs (README 3f). Standard library only. Run from backend/:
    python3 deploy/alerts.py <step> [--email you@example.com]

Steps (in order):  plan | channel --email <address> | policy | status
  channel  creates an email notification channel called "Aram alerts" (reuses it if it exists)
  policy   creates/updates the policy "Aram scheduled job failed": any execution of any job that ends with result=failed
           (export, recommendations, catalog refresh) sends an email within about 5 minutes; it closes itself after 30 minutes
  status   shows the channel, whether it is verified, and the policy

An alert only fires when a job runs and fails. A job that never starts (for example a paused schedule) is not caught by this policy.
"""
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from jobs import P, call  # noqa: E402

MON = f'https://monitoring.googleapis.com/v3/projects/{P}'
HDR = {'X-Goog-User-Project': P}
CHANNEL_NAME = 'Aram alerts'
POLICY_NAME = 'Aram scheduled job failed'


def find(kind, display):
    _, data = call('GET', f'{MON}/{kind}', headers=HDR)
    for item in data.get(kind, []):
        if item.get('displayName') == display:
            return item
    return None


def step_plan():
    print(f'channel: email notification channel "{CHANNEL_NAME}" for the address you pass with --email')
    print(f'policy:  "{POLICY_NAME}": metric run.googleapis.com/job/completed_execution_count, result=failed, any job; > 0 in 5 minutes')
    print('         emails the channel; auto-closes after 30 minutes')


def step_channel():
    if '--email' not in sys.argv:
        sys.exit('Pass your address: python3 deploy/alerts.py channel --email you@example.com')
    email = sys.argv[sys.argv.index('--email') + 1]
    existing = find('notificationChannels', CHANNEL_NAME)
    body = {'type': 'email', 'displayName': CHANNEL_NAME, 'labels': {'email_address': email}}
    if existing:
        call('PATCH', f'https://monitoring.googleapis.com/v3/{existing["name"]}?updateMask=labels', body, headers=HDR)
        print('channel updated')
    else:
        call('POST', f'{MON}/notificationChannels', body, headers=HDR)
        print('channel created')


def step_policy():
    channel = find('notificationChannels', CHANNEL_NAME)
    if not channel:
        sys.exit('Create the channel first: python3 deploy/alerts.py channel --email you@example.com')
    body = {
        'displayName': POLICY_NAME,
        'combiner': 'OR',
        'conditions': [{
            'displayName': 'A Cloud Run job execution failed',
            'conditionThreshold': {
                'filter': 'metric.type="run.googleapis.com/job/completed_execution_count" AND resource.type="cloud_run_job" '
                          'AND metric.labels.result="failed"',
                'aggregations': [{'alignmentPeriod': '300s', 'perSeriesAligner': 'ALIGN_SUM', 'crossSeriesReducer': 'REDUCE_SUM',
                                  'groupByFields': ['resource.labels.job_name']}],
                'comparison': 'COMPARISON_GT', 'thresholdValue': 0, 'duration': '0s',
                'trigger': {'count': 1},
            },
        }],
        'notificationChannels': [channel['name']],
        'alertStrategy': {'autoClose': '1800s'},
        'documentation': {
            'mimeType': 'text/markdown',
            'content': 'A scheduled Aram job failed (`aram-analytics-export`, `aram-recommendations` or `aram-catalog-refresh`).\n\n'
                       'Open Cloud Run > Jobs > the job named in the alert > Logs, fix the cause, then run it again with '
                       '`python3 deploy/jobs.py run <job>`.'},
        'enabled': True,
    }
    existing = find('alertPolicies', POLICY_NAME)
    if existing:
        call('PATCH', f'https://monitoring.googleapis.com/v3/{existing["name"]}', body, headers=HDR)
        print('policy updated')
    else:
        call('POST', f'{MON}/alertPolicies', body, headers=HDR)
        print('policy created')


def step_status():
    channel = find('notificationChannels', CHANNEL_NAME)
    print('channel:', 'missing' if not channel else f'{channel["labels"].get("email_address")} (verification: {channel.get("verificationStatus", "n/a")})')
    policy = find('alertPolicies', POLICY_NAME)
    print('policy: ', 'missing' if not policy else f'enabled={policy.get("enabled")}, channels={len(policy.get("notificationChannels", []))}')


STEPS = {'plan': step_plan, 'channel': step_channel, 'policy': step_policy, 'status': step_status}

if __name__ == '__main__':
    steps = [a for a in sys.argv[1:] if not a.startswith('--') and '@' not in a]
    if len(steps) == 1 and steps[0] in STEPS:
        STEPS[steps[0]]()
    else:
        sys.exit(__doc__)
