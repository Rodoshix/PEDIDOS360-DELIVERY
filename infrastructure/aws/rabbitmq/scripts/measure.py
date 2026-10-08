"""Read-only Linux capacity sampling; no AWS API calls or container mutations."""
import argparse
import json
import os
from pathlib import Path
import statistics
import sys
import time

import tools


def cpu():
    return list(map(int, Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=int, default=10)
    parser.add_argument('--interval', type=float, default=30)
    args = parser.parse_args()
    if sys.platform != 'linux' or not 1 <= args.samples <= 120 or args.interval < 1:
        raise ValueError('Require Linux, 1..120 samples and interval >=1 second')
    tools.load_env()
    broker = tools.container()
    # Explicit project filter; no reading Config.Env, credentials or EC2 identifiers.
    existing = tools.run(['docker', 'ps', '-q', '--filter',
                          'label=com.docker.compose.project=pedidos360-aws']).split()
    containers = list(dict.fromkeys(existing + [broker]))
    previous = cpu()
    rows = []
    for index in range(args.samples):
        time.sleep(args.interval)
        current = cpu()
        delta = [a - b for a, b in zip(current, previous)]
        total = sum(delta)
        if total <= 0:
            raise RuntimeError('Invalid CPU sampling interval')
        previous = current
        memory = {k: int(v.split()[0]) * 1024 for k, v in
                  (line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())}
        disk = os.statvfs(os.environ['RABBITMQ_DATA_DIR'])
        stats = tools.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *containers])
        rows.append(dict(sample=index + 1,
                         cpu_busy_pct=100 * sum(delta[i] for i in (0, 1, 2, 5, 6)) / total,
                         iowait_pct=100 * delta[4] / total, steal_pct=100 * delta[7] / total,
                         mem_used_bytes=memory['MemTotal'] - memory['MemAvailable'],
                         mem_available_bytes=memory['MemAvailable'],
                         disk_available_bytes=disk.f_bavail * disk.f_frsize,
                         containers=[{k: row[k] for k in ('Name', 'CPUPerc', 'MemUsage', 'BlockIO', 'PIDs')}
                                     for row in map(json.loads, stats.splitlines())]))
    print(json.dumps(dict(samples=rows, summary=dict(
        cpu_average_pct=statistics.mean(r['cpu_busy_pct'] for r in rows),
        cpu_max_interval_pct=max(r['cpu_busy_pct'] for r in rows),
        mem_used_average_bytes=statistics.mean(r['mem_used_bytes'] for r in rows),
        mem_available_min_bytes=min(r['mem_available_bytes'] for r in rows)),
        limitation='Host observation only; query CPU credits separately. Not a load test.'), indent=2))


if __name__ == '__main__':
    main()
